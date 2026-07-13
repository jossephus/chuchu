//! JNI bridge for Chuchu's in-app Android local shell PTY backend.
const std = @import("std");

const c = @cImport({
    @cInclude("android/log.h");
    @cInclude("jni.h");
    @cInclude("errno.h");
    @cInclude("fcntl.h");
    @cInclude("poll.h");
    @cInclude("signal.h");
    @cInclude("stdlib.h");
    @cInclude("sys/ioctl.h");
    @cInclude("sys/wait.h");
    @cInclude("termios.h");
    @cInclude("unistd.h");
});

const allocator = std.heap.c_allocator;
const LOG_TAG = "ChuLocalShell";
const read_wait_timeout_ms = 0;
const write_stall_attempts = 64;
const write_stall_us = 2_000;
const close_wait_attempts = 20;
const close_wait_us = 10_000;

const NativeLocalShellSession = struct {
    master_fd: c_int = -1,
    child_pid: c.pid_t = -1,
    last_error: std.ArrayListUnmanaged(u8) = .empty,
};

const LocalShellArgs = struct {
    command: [:0]u8,
    home_dir: [:0]u8,
    temp_dir: [:0]u8,
    term: [:0]u8,

    fn deinit(self: LocalShellArgs) void {
        allocator.free(self.command);
        allocator.free(self.home_dir);
        allocator.free(self.temp_dir);
        allocator.free(self.term);
    }
};

const SpawnedPty = struct {
    master_fd: c_int,
    child_pid: c.pid_t,
};

fn sessionFromHandle(handle: c.jlong) ?*NativeLocalShellSession {
    if (handle == 0) return null;
    const raw_handle: u64 = @bitCast(handle);
    return @ptrFromInt(@as(usize, @truncate(raw_handle)));
}

fn handleFromSession(session: *NativeLocalShellSession) c.jlong {
    const raw_ptr: u64 = @intCast(@intFromPtr(session));
    return @bitCast(raw_ptr);
}

fn errnoValue() c_int {
    return c.__errno().*;
}

fn isWouldBlock(err: c_int) bool {
    return err == c.EAGAIN or err == c.EWOULDBLOCK;
}

fn setError(session: *NativeLocalShellSession, comptime fmt: []const u8, args: anytype) void {
    session.last_error.clearRetainingCapacity();
    std.fmt.format(session.last_error.writer(allocator), fmt, args) catch return;
}

fn setErrnoError(session: *NativeLocalShellSession, prefix: []const u8) void {
    setError(session, "{s}: errno={}", .{ prefix, errnoValue() });
}

fn jniDupString(env: *c.JNIEnv, s: c.jstring) ?[]u8 {
    if (s == null) return null;
    const chars = env.*.*.GetStringUTFChars.?(env, s, null);
    if (chars == null) return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, s, chars);
    return allocator.dupe(u8, std.mem.span(chars)) catch null;
}

fn dupSentinel(bytes: []const u8) ?[:0]u8 {
    const out = allocator.allocSentinel(u8, bytes.len, 0) catch return null;
    @memcpy(out[0..bytes.len], bytes);
    return out;
}

fn jniNewByteArray(env: *c.JNIEnv, bytes: []const u8) c.jbyteArray {
    const array = env.*.*.NewByteArray.?(env, @intCast(bytes.len));
    if (array == null) return null;
    if (bytes.len > 0) {
        env.*.*.SetByteArrayRegion.?(env, array, 0, @intCast(bytes.len), @ptrCast(bytes.ptr));
    }
    return array;
}

fn jniEmptyByteArray(env: *c.JNIEnv) c.jbyteArray {
    return jniNewByteArray(env, &.{});
}

fn jniNewStringOrNull(env: *c.JNIEnv, bytes: []const u8) c.jstring {
    if (bytes.len == 0) return null;
    var buf = allocator.allocSentinel(u8, bytes.len, 0) catch return null;
    defer allocator.free(buf);
    @memcpy(buf[0..bytes.len], bytes);
    return env.*.*.NewStringUTF.?(env, buf.ptr);
}

fn closeFd(fd: *c_int) void {
    if (fd.* >= 0) {
        _ = c.close(fd.*);
        fd.* = -1;
    }
}

fn setFdNonBlocking(fd: c_int) bool {
    const flags = c.fcntl(fd, c.F_GETFL, @as(c_int, 0));
    if (flags < 0) return false;
    return c.fcntl(fd, c.F_SETFL, flags | c.O_NONBLOCK) == 0;
}

fn applyWindowSize(fd: c_int, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint) bool {
    var size: c.struct_winsize = std.mem.zeroes(c.struct_winsize);
    size.ws_col = @intCast(@max(cols, 1));
    size.ws_row = @intCast(@max(rows, 1));
    size.ws_xpixel = @intCast(@max(width_px, 0));
    size.ws_ypixel = @intCast(@max(height_px, 0));
    return c.ioctl(fd, c.TIOCSWINSZ, &size) == 0;
}

fn configurePty(fd: c_int) void {
    var tios: c.struct_termios = undefined;
    if (c.tcgetattr(fd, &tios) != 0) return;
    tios.c_iflag |= @as(@TypeOf(tios.c_iflag), @intCast(c.IUTF8));
    tios.c_iflag &= ~@as(@TypeOf(tios.c_iflag), @intCast(c.IXON | c.IXOFF));
    _ = c.tcsetattr(fd, c.TCSANOW, &tios);
}

fn unblockChildSignals() void {
    var signals_to_unblock: c.sigset_t = undefined;
    if (c.sigfillset(&signals_to_unblock) != 0) return;
    _ = c.sigprocmask(c.SIG_UNBLOCK, &signals_to_unblock, null);
}

fn reapChild(session: *NativeLocalShellSession) void {
    if (session.child_pid <= 0) return;
    var status: c_int = 0;
    const rc = c.waitpid(session.child_pid, &status, c.WNOHANG);
    if (rc == session.child_pid) {
        session.child_pid = -1;
    }
}

fn terminateChild(session: *NativeLocalShellSession) void {
    const pid = session.child_pid;
    if (pid <= 0) return;

    _ = c.kill(-pid, c.SIGHUP);
    _ = c.kill(-pid, c.SIGTERM);

    var status: c_int = 0;
    var attempts: usize = 0;
    while (attempts < close_wait_attempts) : (attempts += 1) {
        const rc = c.waitpid(pid, &status, c.WNOHANG);
        if (rc == pid or rc < 0) {
            session.child_pid = -1;
            return;
        }
        _ = c.usleep(close_wait_us);
    }

    _ = c.kill(-pid, c.SIGKILL);
    _ = c.waitpid(pid, &status, 0);
    session.child_pid = -1;
}

fn destroySession(session: *NativeLocalShellSession) void {
    closeFd(&session.master_fd);
    terminateChild(session);
    session.last_error.deinit(allocator);
    allocator.destroy(session);
}

fn openPty(session: *NativeLocalShellSession, slave_name: *[128]u8) ?c_int {
    const master_fd = c.posix_openpt(c.O_RDWR | c.O_NOCTTY | c.O_CLOEXEC);
    if (master_fd < 0) {
        setErrnoError(session, "posix_openpt failed");
        return null;
    }

    if (c.grantpt(master_fd) != 0) {
        setErrnoError(session, "grantpt failed");
        _ = c.close(master_fd);
        return null;
    }
    if (c.unlockpt(master_fd) != 0) {
        setErrnoError(session, "unlockpt failed");
        _ = c.close(master_fd);
        return null;
    }
    if (c.ptsname_r(master_fd, slave_name[0..].ptr, slave_name.len) != 0) {
        setErrnoError(session, "ptsname_r failed");
        _ = c.close(master_fd);
        return null;
    }
    return master_fd;
}

fn childExec(
    master_fd: c_int,
    slave_name: [*:0]const u8,
    command: [*:0]const u8,
    home_dir: [*:0]const u8,
    term: [*:0]const u8,
    temp_dir: [*:0]const u8,
    cols: c.jint,
    rows: c.jint,
    width_px: c.jint,
    height_px: c.jint,
) noreturn {
    unblockChildSignals();
    _ = c.close(master_fd);
    if (c.setsid() < 0) c._exit(124);

    const slave_fd = c.open(slave_name, c.O_RDWR);
    if (slave_fd < 0) c._exit(126);

    if (c.ioctl(slave_fd, c.TIOCSCTTY, @as(c_int, 0)) != 0) c._exit(123);
    configurePty(slave_fd);
    _ = applyWindowSize(slave_fd, cols, rows, width_px, height_px);
    if (c.chdir(home_dir) != 0) c._exit(125);

    if (c.dup2(slave_fd, 0) < 0) c._exit(122);
    if (c.dup2(slave_fd, 1) < 0) c._exit(121);
    if (c.dup2(slave_fd, 2) < 0) c._exit(120);
    if (slave_fd > 2) _ = c.close(slave_fd);

    _ = c.clearenv();
    _ = c.setenv("TERM", term, 1);
    _ = c.setenv("COLORTERM", "truecolor", 1);
    _ = c.setenv("TERM_PROGRAM", "ghostty", 1);
    _ = c.setenv("TERM_PROGRAM_VERSION", "1", 1);
    _ = c.setenv("HOME", home_dir, 1);
    _ = c.setenv("TMPDIR", temp_dir, 1);
    _ = c.setenv("SHELL", command, 1);
    _ = c.setenv("PATH", "/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin", 1);

    const argv = [_:null][*c]const u8{command};
    _ = c.execv(command, @ptrCast(&argv));
    c._exit(127);
}

export fn Java_com_jossephus_chuchu_service_terminal_NativeLocalShellBridge_nativeCreateSession(env: *c.JNIEnv, thiz: c.jobject) callconv(.c) c.jlong {
    _ = env;
    _ = thiz;
    const session = allocator.create(NativeLocalShellSession) catch return 0;
    session.* = .{};
    return handleFromSession(session);
}

export fn Java_com_jossephus_chuchu_service_terminal_NativeLocalShellBridge_nativeDestroySession(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) void {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return;
    destroySession(session);
}

fn jniParseLocalShellArgs(
    env: *c.JNIEnv,
    session: *NativeLocalShellSession,
    command_jstring: c.jstring,
    home_dir_jstring: c.jstring,
    temp_dir_jstring: c.jstring,
    term_jstring: c.jstring,
) !LocalShellArgs {
    const command_slice = jniDupString(env, command_jstring) orelse {
        setError(session, "Missing local shell command", .{});
        return error.InvalidArgs;
    };
    defer allocator.free(command_slice);

    const command = dupSentinel(command_slice) orelse {
        setError(session, "Local shell command allocation failed", .{});
        return error.OutOfMemory;
    };
    errdefer allocator.free(command);

    if (c.access(command.ptr, c.X_OK) != 0) {
        setErrnoError(session, "local shell command is not executable");
        return error.InvalidArgs;
    }

    const home_dir = try jniParseRequiredPath(env, session, home_dir_jstring, "home directory");
    errdefer allocator.free(home_dir);
    const temp_dir = try jniParseRequiredPath(env, session, temp_dir_jstring, "temp directory");
    errdefer allocator.free(temp_dir);
    const term = try jniParseTerm(env, session, term_jstring);
    errdefer allocator.free(term);

    return .{
        .command = command,
        .home_dir = home_dir,
        .temp_dir = temp_dir,
        .term = term,
    };
}

fn jniParseRequiredPath(
    env: *c.JNIEnv,
    session: *NativeLocalShellSession,
    value_jstring: c.jstring,
    label: []const u8,
) ![:0]u8 {
    const value_slice = jniDupString(env, value_jstring) orelse {
        setError(session, "Missing local shell {s}", .{label});
        return error.InvalidArgs;
    };
    defer allocator.free(value_slice);

    return dupSentinel(value_slice) orelse {
        setError(session, "Local shell {s} allocation failed", .{label});
        return error.OutOfMemory;
    };
}

fn jniParseTerm(
    env: *c.JNIEnv,
    session: *NativeLocalShellSession,
    term_jstring: c.jstring,
) ![:0]u8 {
    const term_slice = jniDupString(env, term_jstring) orelse blk: {
        const fallback = allocator.dupe(u8, "xterm-ghostty") catch {
            setError(session, "Local shell TERM allocation failed", .{});
            return error.OutOfMemory;
        };
        break :blk fallback;
    };
    defer allocator.free(term_slice);

    return dupSentinel(term_slice) orelse {
        setError(session, "Local shell TERM allocation failed", .{});
        return error.OutOfMemory;
    };
}

fn spawnLocalShellPty(
    session: *NativeLocalShellSession,
    args: LocalShellArgs,
    cols: c.jint,
    rows: c.jint,
    width_px: c.jint,
    height_px: c.jint,
) ?SpawnedPty {
    var slave_name: [128]u8 = undefined;
    const master_fd = openPty(session, &slave_name) orelse return null;

    const pid = c.fork();
    if (pid < 0) {
        setErrnoError(session, "fork failed");
        _ = c.close(master_fd);
        return null;
    }
    if (pid == 0) {
        const slave_name_z: [*:0]const u8 = @ptrCast(slave_name[0..].ptr);
        childExec(
            master_fd,
            slave_name_z,
            args.command.ptr,
            args.home_dir.ptr,
            args.term.ptr,
            args.temp_dir.ptr,
            cols,
            rows,
            width_px,
            height_px,
        );
    }

    return .{ .master_fd = master_fd, .child_pid = pid };
}

export fn Java_com_jossephus_chuchu_service_terminal_NativeLocalShellBridge_nativeStart(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, command_jstring: c.jstring, home_dir_jstring: c.jstring, temp_dir_jstring: c.jstring, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint, term_jstring: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    if (session.master_fd >= 0 or session.child_pid > 0) {
        setError(session, "Local shell already started", .{});
        return c.JNI_FALSE;
    }

    const args = jniParseLocalShellArgs(
        env,
        session,
        command_jstring,
        home_dir_jstring,
        temp_dir_jstring,
        term_jstring,
    ) catch return c.JNI_FALSE;
    defer args.deinit();

    const spawned = spawnLocalShellPty(session, args, cols, rows, width_px, height_px) orelse return c.JNI_FALSE;
    session.master_fd = spawned.master_fd;
    session.child_pid = spawned.child_pid;

    if (!setFdNonBlocking(session.master_fd)) {
        setErrnoError(session, "failed to make local shell PTY nonblocking");
        closeFd(&session.master_fd);
        terminateChild(session);
        return c.JNI_FALSE;
    }
    _ = applyWindowSize(session.master_fd, cols, rows, width_px, height_px);
    return c.JNI_TRUE;
}

export fn Java_com_jossephus_chuchu_service_terminal_NativeLocalShellBridge_nativeRead(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, max_bytes: c.jint) callconv(.c) c.jbyteArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const fd = session.master_fd;
    if (fd < 0) return null;

    var poll_fd: [1]c.struct_pollfd = .{.{
        .fd = fd,
        .events = c.POLLIN | c.POLLHUP | c.POLLERR,
        .revents = 0,
    }};
    const poll_rc = c.poll(&poll_fd, 1, read_wait_timeout_ms);
    if (poll_rc == 0) {
        reapChild(session);
        return jniEmptyByteArray(env);
    }
    if (poll_rc < 0) {
        const err = errnoValue();
        if (err == c.EINTR) return jniEmptyByteArray(env);
        setErrnoError(session, "local shell poll failed");
        return null;
    }

    const cap: usize = @intCast(@max(max_bytes, 1));
    const capped = @min(cap, 1024 * 1024);
    const buf = allocator.alloc(u8, capped) catch {
        setError(session, "local shell read allocation failed", .{});
        return null;
    };
    defer allocator.free(buf);

    const rc = c.read(fd, buf.ptr, capped);
    if (rc > 0) {
        return jniNewByteArray(env, buf[0..@intCast(rc)]);
    }
    if (rc == 0) {
        reapChild(session);
        return null;
    }

    const err = errnoValue();
    if (err == c.EINTR or isWouldBlock(err)) {
        return jniEmptyByteArray(env);
    }
    if (err == c.EIO) {
        reapChild(session);
        return null;
    }
    setErrnoError(session, "local shell read failed");
    return null;
}

fn writeAllToPty(session: *NativeLocalShellSession, fd: c_int, bytes: []const u8) c.jint {
    var offset: usize = 0;
    var stalled_attempts: usize = 0;
    while (offset < bytes.len) {
        const rc = c.write(fd, bytes[offset..].ptr, bytes.len - offset);
        if (rc > 0) {
            offset += @intCast(rc);
            stalled_attempts = 0;
            continue;
        }
        if (rc == 0) {
            stalled_attempts += 1;
        } else {
            const err = errnoValue();
            if (err != c.EINTR and !isWouldBlock(err)) {
                setErrnoError(session, "local shell write failed");
                return -1;
            }
            stalled_attempts += 1;
        }
        if (stalled_attempts > write_stall_attempts) {
            setError(session, "Local shell write stalled", .{});
            return -1;
        }
        _ = c.usleep(write_stall_us);
    }
    return @intCast(offset);
}

export fn Java_com_jossephus_chuchu_service_terminal_NativeLocalShellBridge_nativeWrite(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, data: c.jbyteArray) callconv(.c) c.jint {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return -1;
    const fd = session.master_fd;
    if (fd < 0) {
        setError(session, "Local shell is not open", .{});
        return -1;
    }
    if (data == null) return 0;
    const len = env.*.*.GetArrayLength.?(env, data);
    if (len <= 0) return 0;
    var is_copy: c.jboolean = c.JNI_FALSE;
    const bytes = env.*.*.GetByteArrayElements.?(env, data, &is_copy);
    if (bytes == null) {
        setError(session, "Local shell write buffer unavailable", .{});
        return -1;
    }
    defer env.*.*.ReleaseByteArrayElements.?(env, data, bytes, c.JNI_ABORT);

    const slice: []const u8 = @as([*]const u8, @ptrCast(bytes))[0..@intCast(len)];
    return writeAllToPty(session, fd, slice);
}

export fn Java_com_jossephus_chuchu_service_terminal_NativeLocalShellBridge_nativeResize(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    if (session.master_fd < 0) return c.JNI_FALSE;
    if (!applyWindowSize(session.master_fd, cols, rows, width_px, height_px)) {
        setErrnoError(session, "local shell resize failed");
        return c.JNI_FALSE;
    }
    if (session.child_pid > 0) {
        _ = c.kill(-session.child_pid, c.SIGWINCH);
    }
    return c.JNI_TRUE;
}

export fn Java_com_jossephus_chuchu_service_terminal_NativeLocalShellBridge_nativeGetLastError(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jstring {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    return jniNewStringOrNull(env, session.last_error.items);
}
