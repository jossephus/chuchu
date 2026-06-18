const std = @import("std");

const c = @cImport({
    @cInclude("android/log.h");
    @cInclude("jni.h");
    @cInclude("libssh2.h");
    @cInclude("libssh2_sftp.h");
    @cInclude("sys/socket.h");
    @cInclude("netdb.h");
    @cInclude("arpa/inet.h");
    @cInclude("unistd.h");
    @cInclude("fcntl.h");
    @cInclude("errno.h");
    @cInclude("poll.h");
});
const ipc = @import("ipc.zig");

const allocator = std.heap.c_allocator;
const LOG_TAG = "ChuKittySSH";
const verbose_ssh_logs = false;

fn androidLog(prio: c_int, comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    _ = c.__android_log_print(prio, LOG_TAG, "%.*s", @as(c_int, @intCast(line.len)), line.ptr);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    if (!verbose_ssh_logs) return;
    androidLog(4, fmt, args); // ANDROID_LOG_INFO = 4
}

fn logError(comptime fmt: []const u8, args: anytype) void {
    androidLog(6, fmt, args); // ANDROID_LOG_ERROR = 6
}
const setup_wait_timeout_ms = 10_000;
const io_wait_timeout_ms = 120;
// SFTP servers may make progress between short 120ms socket waits; use a
// longer idle cap before failing directory listing/realpath as truly stalled.
const sftp_idle_limit_ms: i64 = 15_000;

const NativeSshSession = struct {
    socket_fd: c_int = -1,
    session: ?*c.LIBSSH2_SESSION = null,
    channel: ?*c.LIBSSH2_CHANNEL = null,
    sftp: ?*c.LIBSSH2_SFTP = null,
    upload_handle: ?*c.LIBSSH2_SFTP_HANDLE = null,
    username: ?[]u8 = null,
    hostkey_ptr: ?[*]const u8 = null,
    hostkey_len: usize = 0,
    hostkey_type: c_int = 0,
    hostkey_copy: ?[]u8 = null,
    last_error: std.ArrayListUnmanaged(u8) = .empty,
    empty_reads: u32 = 0,
};

fn sessionFromHandle(handle: c.jlong) ?*NativeSshSession {
    if (handle == 0) return null;
    const raw_handle: u64 = @bitCast(handle);
    return @ptrFromInt(@as(usize, @truncate(raw_handle)));
}

fn handleFromSession(session: *NativeSshSession) c.jlong {
    const raw_ptr: u64 = @intCast(@intFromPtr(session));
    return @bitCast(raw_ptr);
}

fn setError(session: *NativeSshSession, comptime fmt: []const u8, args: anytype) void {
    session.last_error.clearRetainingCapacity();
    std.fmt.format(session.last_error.writer(allocator), fmt, args) catch return;
}

fn setLibssh2Error(session: *NativeSshSession, prefix: []const u8, rc: c_int) void {
    var errmsg_ptr: [*c]const u8 = null;
    var errmsg_len: c_int = 0;
    if (session.session) |ssh_session| {
        _ = c.libssh2_session_last_error(ssh_session, @ptrCast(&errmsg_ptr), &errmsg_len, 0);
    }
    if (errmsg_ptr != null and errmsg_len > 0) {
        setError(session, "{s}: {s}", .{ prefix, errmsg_ptr[0..@intCast(errmsg_len)] });
    } else {
        setError(session, "{s}: rc={}", .{ prefix, rc });
    }
}

fn sftpStatusName(code: c_ulong) []const u8 {
    return switch (code) {
        c.LIBSSH2_FX_OK => "ok",
        c.LIBSSH2_FX_EOF => "eof",
        c.LIBSSH2_FX_NO_SUCH_FILE => "no such file",
        c.LIBSSH2_FX_PERMISSION_DENIED => "permission denied",
        c.LIBSSH2_FX_FAILURE => "failure",
        c.LIBSSH2_FX_BAD_MESSAGE => "bad message",
        c.LIBSSH2_FX_NO_CONNECTION => "no connection",
        c.LIBSSH2_FX_CONNECTION_LOST => "connection lost",
        c.LIBSSH2_FX_OP_UNSUPPORTED => "operation unsupported",
        c.LIBSSH2_FX_INVALID_HANDLE => "invalid handle",
        c.LIBSSH2_FX_NO_SUCH_PATH => "no such path",
        c.LIBSSH2_FX_FILE_ALREADY_EXISTS => "file already exists",
        c.LIBSSH2_FX_WRITE_PROTECT => "write protected",
        c.LIBSSH2_FX_NO_MEDIA => "no media",
        c.LIBSSH2_FX_NO_SPACE_ON_FILESYSTEM => "no space on filesystem",
        c.LIBSSH2_FX_QUOTA_EXCEEDED => "quota exceeded",
        c.LIBSSH2_FX_UNKNOWN_PRINCIPAL => "unknown principal",
        c.LIBSSH2_FX_LOCK_CONFLICT => "lock conflict",
        c.LIBSSH2_FX_DIR_NOT_EMPTY => "directory not empty",
        c.LIBSSH2_FX_NOT_A_DIRECTORY => "not a directory",
        c.LIBSSH2_FX_INVALID_FILENAME => "invalid filename",
        c.LIBSSH2_FX_LINK_LOOP => "link loop",
        else => "unknown status",
    };
}

fn setSftpError(session: *NativeSshSession, sftp: *c.LIBSSH2_SFTP, prefix: []const u8, rc: c_int) void {
    var errmsg_ptr: [*c]const u8 = null;
    var errmsg_len: c_int = 0;
    if (session.session) |ssh_session| {
        _ = c.libssh2_session_last_error(ssh_session, @ptrCast(&errmsg_ptr), &errmsg_len, 0);
    }
    const status = c.libssh2_sftp_last_error(sftp);
    const detail = if (errmsg_ptr != null and errmsg_len > 0) errmsg_ptr[0..@intCast(errmsg_len)] else "libssh2 SFTP error";
    setError(session, "{s}: {s} (SFTP status {d}: {s}, rc={d})", .{ prefix, detail, status, sftpStatusName(status), rc });
}

fn closeSocket(fd: c_int) void {
    if (fd >= 0) _ = c.close(fd);
}

fn setSocketNonBlocking(fd: c_int) void {
    const flags = c.fcntl(fd, c.F_GETFL, @as(c_int, 0));
    if (flags < 0) {
        return;
    }
    if (c.fcntl(fd, c.F_SETFL, flags | c.O_NONBLOCK) != 0) {
        return;
    }
}

fn clearHostKeyCopy(session: *NativeSshSession) void {
    if (session.hostkey_copy) |copy| allocator.free(copy);
    session.hostkey_copy = null;
    session.hostkey_ptr = null;
    session.hostkey_len = 0;
    session.hostkey_type = 0;
}

fn waitSocket(session: *NativeSshSession, timeout_ms: c_int) bool {
    const ssh_session = session.session orelse return false;
    if (session.socket_fd < 0) return false;

    const directions = c.libssh2_session_block_directions(ssh_session);
    var events: c_short = 0;
    if ((directions & c.LIBSSH2_SESSION_BLOCK_INBOUND) != 0) {
        events |= c.POLLIN;
    }
    if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) {
        events |= c.POLLOUT;
    }
    if (events == 0) {
        events = c.POLLIN | c.POLLOUT;
    }

    var poll_fds: [1]c.struct_pollfd = .{.{
        .fd = session.socket_fd,
        .events = events,
        .revents = 0,
    }};
    const poll_rc = c.poll(&poll_fds, 1, timeout_ms);
    return poll_rc > 0;
}

fn trySetChannelEnv(session: *NativeSshSession, channel: *c.LIBSSH2_CHANNEL, name: []const u8, value: []const u8) void {
    while (true) {
        const rc = c.libssh2_channel_setenv_ex(
            channel,
            name.ptr,
            @intCast(name.len),
            value.ptr,
            @intCast(value.len),
        );
        if (rc == 0) return;
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logInfo("Ignoring rejected channel-setenv {s} rc={}", .{ name, rc });
            return;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            logInfo("Ignoring timed out channel-setenv {s}", .{name});
            return;
        }
    }
}

fn nowMs() i64 {
    return std.time.milliTimestamp();
}

fn destroyNativeSshSession(session: *NativeSshSession) void {
    if (session.upload_handle) |uh| {
        _ = closeSftpHandle(session, uh, "destroy upload handle");
        session.upload_handle = null;
    }
    if (session.sftp) |sftp| {
        _ = shutdownSftp(session, sftp, "destroy session");
        session.sftp = null;
    }
    if (session.channel) |channel| {
        _ = c.libssh2_channel_close(channel);
        _ = c.libssh2_channel_free(channel);
    }
    if (session.session) |ssh_session| {
        _ = c.libssh2_session_disconnect_ex(ssh_session, c.SSH_DISCONNECT_BY_APPLICATION, "bye", "en");
        _ = c.libssh2_session_free(ssh_session);
    }
    closeSocket(session.socket_fd);
    if (session.username) |username| allocator.free(username);
    clearHostKeyCopy(session);
    session.last_error.deinit(allocator);
    allocator.destroy(session);
}

fn ensureSftp(session: *NativeSshSession) ?*c.LIBSSH2_SFTP {
    if (session.sftp) |sftp| return sftp;
    const ssh_session = session.session orelse {
        setError(session, "SSH session not connected", .{});
        return null;
    };
    while (true) {
        const sftp = c.libssh2_sftp_init(ssh_session);
        if (sftp != null) {
            session.sftp = sftp;
            return sftp;
        }
        const rc = c.libssh2_session_last_errno(ssh_session);
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "SFTP init failed", rc);
            return null;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "SFTP init timed out", .{});
            return null;
        }
    }
}

/// Result of waiting for the SFTP socket after a libssh2 EAGAIN.
const SftpProgress = enum {
    /// Socket signalled activity, or we are still inside the idle budget; retry.
    retry,
    /// No progress for longer than sftp_idle_limit_ms; the peer is stalled.
    stalled,
    /// No socket to wait on (session torn down mid-operation).
    no_socket,
};

/// Block briefly on the SFTP socket after an EAGAIN and decide whether the
/// caller should retry. `idle_since_ms` is reset whenever the socket signals
/// activity so the idle budget only measures genuinely silent periods.
fn awaitSftpProgress(session: *NativeSshSession, idle_since_ms: *i64) SftpProgress {
    if (session.session == null or session.socket_fd < 0) return .no_socket;
    if (waitSocket(session, io_wait_timeout_ms)) {
        idle_since_ms.* = nowMs();
        return .retry;
    }
    if (nowMs() - idle_since_ms.* > sftp_idle_limit_ms) return .stalled;
    return .retry;
}

fn shutdownSftp(session: *NativeSshSession, sftp: *c.LIBSSH2_SFTP, reason: []const u8) bool {
    var idle_since_ms = nowMs();
    while (true) {
        const rc = c.libssh2_sftp_shutdown(sftp);
        if (rc == 0) return true;
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logInfo("SFTP shutdown ended during {s} rc={}", .{ reason, rc });
            return true;
        }
        switch (awaitSftpProgress(session, &idle_since_ms)) {
            .retry => {},
            .no_socket => {
                setError(session, "SFTP shutdown would block during {s} without an active socket", .{reason});
                return false;
            },
            .stalled => {
                const idle_ms = nowMs() - idle_since_ms;
                setError(session, "SFTP shutdown stalled during {s} (idle {d}ms)", .{ reason, idle_ms });
                logError("SFTP shutdown stalled during {s} idle={}ms", .{ reason, idle_ms });
                return false;
            },
        }
    }
}

fn closeSftpHandle(session: *NativeSshSession, handle: *c.LIBSSH2_SFTP_HANDLE, reason: []const u8) bool {
    var idle_since_ms = nowMs();
    while (true) {
        const rc = c.libssh2_sftp_close_handle(handle);
        if (rc == 0) return true;
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, reason, @intCast(rc));
            return false;
        }
        switch (awaitSftpProgress(session, &idle_since_ms)) {
            .retry => {},
            .no_socket => {
                setError(session, "{s} would block without an active socket", .{reason});
                return false;
            },
            .stalled => {
                const idle_ms = nowMs() - idle_since_ms;
                setError(session, "{s} stalled (idle {d}ms)", .{ reason, idle_ms });
                logError("{s} stalled idle={}ms", .{ reason, idle_ms });
                return false;
            },
        }
    }
}

fn resetSftpAfterFailure(session: *NativeSshSession, reason: []const u8) void {
    if (session.sftp == null) return;
    if (session.upload_handle != null) {
        logError("Skipping SFTP reset after {s}: upload active", .{reason});
        return;
    }
    logError("Resetting SFTP subsystem after {s}", .{reason});
    if (session.sftp) |sftp| {
        if (shutdownSftp(session, sftp, reason)) {
            session.sftp = null;
        }
    }
}

fn sftpOpenDir(session: *NativeSshSession, sftp: *c.LIBSSH2_SFTP, path_z: [:0]u8) ?*c.LIBSSH2_SFTP_HANDLE {
    const started_ms = nowMs();
    var idle_since_ms = started_ms;
    var eagain_count: u32 = 0;
    while (true) {
        const dir = c.libssh2_sftp_opendir(sftp, path_z.ptr);
        if (dir != null) return dir;
        const rc = c.libssh2_session_last_errno(session.session.?);
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setSftpError(session, sftp, "SFTP opendir failed", rc);
            return null;
        }

        eagain_count +%= 1;
        switch (awaitSftpProgress(session, &idle_since_ms)) {
            .retry => {},
            .no_socket, .stalled => {
                const now = nowMs();
                setError(
                    session,
                    "SFTP opendir timed out for {s} after {d}ms (idle {d}ms, EAGAIN={d})",
                    .{ path_z, now - started_ms, now - idle_since_ms, eagain_count },
                );
                logError(
                    "SFTP opendir timeout path={s} elapsed={}ms idle={}ms eagain={}",
                    .{ path_z, now - started_ms, now - idle_since_ms, eagain_count },
                );
                return null;
            },
        }
    }
}

fn connectSocket(host: [:0]const u8, port: u16) !c_int {
    var hints: c.struct_addrinfo = std.mem.zeroes(c.struct_addrinfo);
    hints.ai_family = c.AF_UNSPEC;
    hints.ai_socktype = c.SOCK_STREAM;

    var service_buf: [16]u8 = undefined;
    const service = try std.fmt.bufPrintZ(&service_buf, "{}", .{port});

    var addr_list: ?*c.struct_addrinfo = null;
    const rc = c.getaddrinfo(host.ptr, service.ptr, &hints, &addr_list);
    if (rc != 0) return error.AddressLookupFailed;
    defer if (addr_list != null) c.freeaddrinfo(addr_list);

    var cur = addr_list;
    while (cur) |info| : (cur = info.ai_next) {
        const fd = c.socket(info.ai_family, info.ai_socktype, info.ai_protocol);
        if (fd < 0) continue;
        if (c.connect(fd, info.ai_addr, info.ai_addrlen) == 0) return fd;
        closeSocket(fd);
    }
    return error.ConnectFailed;
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

fn jniNewStringOrNull(env: *c.JNIEnv, bytes: []const u8) c.jstring {
    if (bytes.len == 0) return null;
    var buf = allocator.allocSentinel(u8, bytes.len, 0) catch return null;
    defer allocator.free(buf);
    @memcpy(buf[0..bytes.len], bytes);
    return env.*.*.NewStringUTF.?(env, buf.ptr);
}

fn jniNewByteArrayOrNull(env: *c.JNIEnv, bytes: []const u8) c.jbyteArray {
    const array = env.*.*.NewByteArray.?(env, @intCast(bytes.len));
    if (array == null) return null;
    if (bytes.len > 0) {
        env.*.*.SetByteArrayRegion.?(env, array, 0, @intCast(bytes.len), @ptrCast(bytes.ptr));
    }
    return array;
}

fn hostkeyAlgorithmName(kind: c_int) []const u8 {
    return switch (kind) {
        c.LIBSSH2_HOSTKEY_TYPE_RSA => "RSA",
        c.LIBSSH2_HOSTKEY_TYPE_DSS => "DSA",
        c.LIBSSH2_HOSTKEY_TYPE_ECDSA_256 => "ECDSA",
        c.LIBSSH2_HOSTKEY_TYPE_ECDSA_384 => "ECDSA",
        c.LIBSSH2_HOSTKEY_TYPE_ECDSA_521 => "ECDSA",
        c.LIBSSH2_HOSTKEY_TYPE_ED25519 => "ED25519",
        else => "UNKNOWN",
    };
}

fn appendErrorFrame(response: *std.ArrayList(u8), session: *NativeSshSession, fallback: []const u8) void {
    const message = if (session.last_error.items.len > 0) session.last_error.items else fallback;
    ipc.appendMessage(allocator, response, .Error, message) catch {};
}

fn writeChannel(session: *NativeSshSession, bytes: []const u8) c.jint {
    const channel = session.channel orelse {
        setError(session, "Shell not open", .{});
        return -1;
    };
    if (bytes.len == 0) return 0;
    var total_written: usize = 0;
    var stalled_loops: u32 = 0;
    while (total_written < bytes.len) {
        const chunk = bytes[total_written..];
        const rc = c.libssh2_channel_write_ex(channel, 0, @ptrCast(chunk.ptr), @intCast(chunk.len));
        if (rc == c.LIBSSH2_ERROR_EAGAIN or rc == 0) {
            stalled_loops +%= 1;
            if (stalled_loops > 64) {
                break;
            }
            if (!waitSocket(session, io_wait_timeout_ms)) {
                break;
            }
            continue;
        }
        if (rc < 0) {
            setLibssh2Error(session, "Write failed", @intCast(rc));
            return -1;
        }
        stalled_loops = 0;
        total_written += @intCast(rc);
    }
    return @intCast(total_written);
}

fn readChannel(alloc: std.mem.Allocator, session: *NativeSshSession, max_bytes: usize) ?[]u8 {
    const channel = session.channel orelse return null;
    const cap = @max(max_bytes, 1);
    const buf = alloc.alloc(u8, cap) catch return null;
    defer alloc.free(buf);
    var total_read: usize = 0;
    // Read both stdout and stderr — exec channels may send
    // diagnostics on stderr, blocking EOF if we ignore it.
    const streams = [_]c_int{ 0, 1 };
    for (streams) |stream_id| {
        while (true) {
            if (total_read >= buf.len) break;
            const rc = c.libssh2_channel_read_ex(channel, stream_id, @ptrCast(buf.ptr + total_read), @intCast(buf.len - total_read));
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                session.empty_reads +%= 1;
                break;
            }
            if (rc == 0) {
                break;
            }
            if (rc < 0) {
                setLibssh2Error(session, "Read failed", @intCast(rc));
                return null;
            }
            total_read += @intCast(rc);
            session.empty_reads = 0;
        }
    }
    if (total_read == 0) {
        return alloc.dupe(u8, &.{}) catch null;
    }
    return alloc.dupe(u8, buf[0..total_read]) catch null;
}

fn readJByteArray(env: *c.JNIEnv, array: c.jbyteArray) ?[]u8 {
    if (array == null) return null;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len <= 0) return &.{};
    const out = allocator.alloc(u8, @intCast(len)) catch return null;
    env.*.*.GetByteArrayRegion.?(env, array, 0, len, @ptrCast(out.ptr));
    return out;
}

comptime {
    _ = c.libssh2_init;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeCreateSession(env: *c.JNIEnv, thiz: c.jobject) callconv(.c) c.jlong {
    _ = env;
    _ = thiz;
    _ = c.libssh2_init(0);
    const session = allocator.create(NativeSshSession) catch return 0;
    session.* = .{};
    return handleFromSession(session);
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeDestroySession(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) void {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return;
    destroyNativeSshSession(session);
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeConnect(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, host: c.jstring, port: c.jint, username: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const host_slice = jniDupString(env, host) orelse {
        setError(session, "Missing host", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(host_slice);
    const host_z = dupSentinel(host_slice) orelse {
        setError(session, "Host alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(host_z);
    const username_slice = jniDupString(env, username) orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(username_slice);

    logInfo("nativeConnect host={s} port={} user={s}", .{ host_slice, port, username_slice });

    session.last_error.clearRetainingCapacity();
    clearHostKeyCopy(session);
    if (session.username) |old| allocator.free(old);
    session.username = allocator.dupe(u8, username_slice) catch {
        setError(session, "Username alloc failed", .{});
        return c.JNI_FALSE;
    };

    const fd = connectSocket(host_z, @intCast(@max(port, 0))) catch {
        logError("Socket connect failed host={s} port={}", .{ host_slice, port });
        setError(session, "Socket connect failed host={s} port={}", .{ host_slice, port });
        return c.JNI_FALSE;
    };
    logInfo("Socket connected fd={}", .{fd});
    session.socket_fd = fd;

    const ssh_session = c.libssh2_session_init_ex(null, null, null, null) orelse {
        setError(session, "libssh2_session_init_ex failed", .{});
        closeSocket(fd);
        session.socket_fd = -1;
        return c.JNI_FALSE;
    };
    session.session = ssh_session;
    c.libssh2_session_set_blocking(ssh_session, 0);
    logInfo("Starting SSH handshake...", .{});
    while (true) {
        const handshake_rc = c.libssh2_session_handshake(ssh_session, fd);
        if (handshake_rc == 0) break;
        if (handshake_rc != c.LIBSSH2_ERROR_EAGAIN) {
            logError("SSH handshake failed rc={}", .{handshake_rc});
            setLibssh2Error(session, "SSH handshake failed", handshake_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            logError("SSH handshake timed out", .{});
            setError(session, "SSH handshake timed out", .{});
            return c.JNI_FALSE;
        }
    }
    logInfo("SSH handshake completed", .{});

    var hostkey_len: usize = 0;
    const hostkey_ptr = c.libssh2_session_hostkey(ssh_session, &hostkey_len, &session.hostkey_type);
    if (hostkey_ptr == null or hostkey_len == 0) {
        setError(session, "Missing server host key", .{});
        return c.JNI_FALSE;
    }
    const hostkey_copy = allocator.alloc(u8, hostkey_len) catch {
        setError(session, "Host key copy alloc failed", .{});
        return c.JNI_FALSE;
    };
    @memcpy(hostkey_copy, hostkey_ptr[0..hostkey_len]);
    session.hostkey_copy = hostkey_copy;
    session.hostkey_ptr = hostkey_copy.ptr;
    session.hostkey_len = hostkey_len;
    return c.JNI_TRUE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeAuthenticateNone(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);
    logInfo("nativeAuthenticateNone user={s}", .{username_slice});
    if (c.libssh2_userauth_authenticated(ssh_session) != 0) {
        logInfo("None auth already authenticated after handshake", .{});
        return c.JNI_TRUE;
    }

    const deadline_ms = nowMs() + setup_wait_timeout_ms;
    var attempts: u32 = 0;
    while (true) {
        attempts += 1;
        logInfo("None auth probe methods attempt={}", .{attempts});
        const userauth_list = c.libssh2_userauth_list(ssh_session, username_slice.ptr, @intCast(username_slice.len));
        logInfo("None auth probe returned attempt={}", .{attempts});
        if (userauth_list == null) {
            const rc = c.libssh2_session_last_errno(ssh_session);
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                if ((attempts % 32) == 0) {
                    logInfo("None auth waiting (EAGAIN) attempts={}", .{attempts});
                }
                if (nowMs() >= deadline_ms) {
                    setError(session, "None auth timed out after {} attempts", .{attempts});
                    return c.JNI_FALSE;
                }
                if (!waitSocket(session, setup_wait_timeout_ms)) {
                    setError(session, "None auth timed out", .{});
                    return c.JNI_FALSE;
                }
                continue;
            }
            if (c.libssh2_userauth_authenticated(ssh_session) != 0) {
                logInfo("None auth succeeded - session authenticated", .{});
                return c.JNI_TRUE;
            }
            logError("None auth failed: no methods and not authenticated rc={}", .{rc});
            setError(session, "None auth failed: server returned no methods and session not authenticated", .{});
            return c.JNI_FALSE;
        }
        logError("None auth rejected, server requires: {s}", .{std.mem.span(userauth_list)});
        setError(session, "None auth rejected, server requires: {s}", .{std.mem.span(userauth_list)});
        return c.JNI_FALSE;
    }
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeGetLastError(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jstring {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    return jniNewStringOrNull(env, session.last_error.items);
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeGetHostKey(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jbyteArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    if (session.hostkey_ptr == null or session.hostkey_len == 0) return null;
    return jniNewByteArrayOrNull(env, session.hostkey_ptr.?[0..session.hostkey_len]);
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeGetHostKeyAlgorithm(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jstring {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    return jniNewStringOrNull(env, hostkeyAlgorithmName(session.hostkey_type));
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeAuthenticatePassword(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, password: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    const password_slice = jniDupString(env, password) orelse {
        setError(session, "Missing password", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(password_slice);
    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);
    logInfo("Password auth attempt start username_len={} password_len={}", .{ username_slice.len, password_slice.len });
    const password_deadline_ms = nowMs() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_password_ex(ssh_session, username_slice.ptr, @intCast(username_slice.len), password_slice.ptr, @intCast(password_slice.len), null);
        if (rc == 0) {
            logInfo("Password auth succeeded", .{});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logError("Password auth failed rc={} (keyboard-interactive fallback disabled for diagnostics)", .{rc});
            setLibssh2Error(session, "Password auth failed", rc);
            return c.JNI_FALSE;
        }
        if (nowMs() >= password_deadline_ms) {
            logError("Password auth timed out (keyboard-interactive fallback disabled for diagnostics)", .{});
            setError(session, "Password auth timed out", .{});
            return c.JNI_FALSE;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeAuthenticatePublicKey(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, key_path: c.jstring, passphrase: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    const key_path_slice = jniDupString(env, key_path) orelse {
        setError(session, "Missing key path", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(key_path_slice);
    const key_path_z = dupSentinel(key_path_slice) orelse {
        setError(session, "Failed to allocate key path", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(key_path_z);

    const passphrase_slice = jniDupString(env, passphrase);
    defer if (passphrase_slice) |ps| allocator.free(ps);
    const passphrase_z = if (passphrase_slice) |ps| dupSentinel(ps) else null;
    defer if (passphrase_z) |pz| allocator.free(pz);
    const passphrase_ptr: ?[*:0]const u8 = if (passphrase_z) |pz| pz.ptr else null;

    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);

    logInfo("Public key auth attempt key={s}", .{key_path_slice});
    const deadline_ms = nowMs() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_publickey_fromfile_ex(
            ssh_session,
            username_slice.ptr,
            @intCast(username_slice.len),
            null,
            key_path_z.ptr,
            passphrase_ptr,
        );
        if (rc == 0) {
            logInfo("Public key auth succeeded", .{});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logError("Public key auth failed rc={} key={s}", .{ rc, key_path_slice });
            var errmsg_ptr: [*c]const u8 = null;
            var errmsg_len: c_int = 0;
            _ = c.libssh2_session_last_error(ssh_session, @ptrCast(&errmsg_ptr), &errmsg_len, 0);
            if (errmsg_ptr != null and errmsg_len > 0) {
                setError(session, "Public key auth failed (rc={}): {s}", .{ rc, errmsg_ptr[0..@intCast(errmsg_len)] });
            } else {
                setError(session, "Public key auth failed (rc={})", .{rc});
            }
            return c.JNI_FALSE;
        }
        if (nowMs() >= deadline_ms) {
            setError(session, "Public key auth timed out", .{});
            return c.JNI_FALSE;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }

    return c.JNI_FALSE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeAuthenticatePublicKeyMemory(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, public_key_open_ssh: c.jstring, private_key_pem: c.jstring, passphrase: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const username_slice = session.username orelse {
        setError(session, "Missing username", .{});
        return c.JNI_FALSE;
    };
    const private_key_slice = jniDupString(env, private_key_pem) orelse {
        setError(session, "Missing private key", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(private_key_slice);

    const public_key_slice = jniDupString(env, public_key_open_ssh);
    defer if (public_key_slice) |pk| allocator.free(pk);
    const trimmed_public = if (public_key_slice) |pk| std.mem.trim(u8, pk, " \t\r\n") else "";
    const compact_public: ?[]u8 = blk: {
        if (trimmed_public.len == 0) break :blk null;
        var it = std.mem.tokenizeAny(u8, trimmed_public, " \t\r\n");
        const t0 = it.next() orelse break :blk null;
        const t1 = it.next() orelse break :blk null;
        break :blk std.fmt.allocPrint(allocator, "{s} {s}", .{ t0, t1 }) catch null;
    };
    defer if (compact_public) |pk| allocator.free(pk);

    const passphrase_slice = jniDupString(env, passphrase);
    defer if (passphrase_slice) |ps| allocator.free(ps);
    const passphrase_z = if (passphrase_slice) |ps| dupSentinel(ps) else null;
    defer if (passphrase_z) |pz| allocator.free(pz);
    const passphrase_ptr: ?[*:0]const u8 = if (passphrase_z) |pz| pz.ptr else null;

    c.libssh2_session_set_blocking(ssh_session, 1);
    defer c.libssh2_session_set_blocking(ssh_session, 0);

    const auth_list_ptr = c.libssh2_userauth_list(ssh_session, username_slice.ptr, @intCast(username_slice.len));
    if (auth_list_ptr != null) {
        logInfo("Public key memory auth methods user={s} methods={s}", .{ username_slice, std.mem.span(auth_list_ptr) });
    } else {
        logInfo("Public key memory auth methods user={s} methods=<none>", .{username_slice});
    }

    logInfo(
        "Public key memory auth attempt user={s} publicLen={} privateLen={} passphraseLen={}",
        .{ username_slice, if (compact_public) |pk| pk.len else @as(usize, 0), private_key_slice.len, if (passphrase_slice) |ps| ps.len else @as(usize, 0) },
    );

    const deadline_ms = nowMs() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_publickey_frommemory(
            ssh_session,
            username_slice.ptr,
            username_slice.len,
            if (compact_public) |pk| pk.ptr else null,
            if (compact_public) |pk| pk.len else 0,
            private_key_slice.ptr,
            private_key_slice.len,
            passphrase_ptr,
        );
        if (rc == 0) {
            logInfo("Public key memory auth succeeded user={s}", .{username_slice});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            const last_errno = c.libssh2_session_last_errno(ssh_session);
            logError("Public key memory auth failed rc={} last_errno={}", .{ rc, last_errno });
            var errmsg_ptr: [*c]const u8 = null;
            var errmsg_len: c_int = 0;
            _ = c.libssh2_session_last_error(ssh_session, @ptrCast(&errmsg_ptr), &errmsg_len, 0);
            if (errmsg_ptr != null and errmsg_len > 0) {
                setError(session, "Public key memory auth failed (rc={} last_errno={}): {s}", .{ rc, last_errno, errmsg_ptr[0..@intCast(errmsg_len)] });
            } else {
                setError(session, "Public key memory auth failed (rc={} last_errno={})", .{ rc, last_errno });
            }
            return c.JNI_FALSE;
        }
        if (nowMs() >= deadline_ms) {
            setError(session, "Public key memory auth timed out", .{});
            return c.JNI_FALSE;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }

    return c.JNI_FALSE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeOpenShell(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint, term: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const term_slice = jniDupString(env, term) orelse allocator.dupe(u8, "xterm-ghostty") catch return c.JNI_FALSE;
    defer allocator.free(term_slice);
    var channel: ?*c.LIBSSH2_CHANNEL = null;
    while (channel == null) {
        channel = c.libssh2_channel_open_ex(ssh_session, "session", 7, c.LIBSSH2_CHANNEL_WINDOW_DEFAULT, c.LIBSSH2_CHANNEL_PACKET_DEFAULT, null, 0);
        if (channel != null) break;
        const open_rc = c.libssh2_session_last_errno(ssh_session);
        if (open_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Channel open failed", open_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Channel open timed out", .{});
            return c.JNI_FALSE;
        }
    }
    if (session.channel) |old_ch| {
        _ = c.libssh2_channel_close(old_ch);
        _ = c.libssh2_channel_free(old_ch);
    }
    session.channel = channel;
    c.libssh2_channel_set_blocking(channel.?, 0);
    var term_buf = allocator.allocSentinel(u8, term_slice.len, 0) catch {
        setError(session, "TERM alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(term_buf);
    @memcpy(term_buf[0..term_slice.len], term_slice);

    while (true) {
        const pty_rc = c.libssh2_channel_request_pty_ex(channel.?, term_buf.ptr, @intCast(term_slice.len), null, 0, cols, rows, width_px, height_px);
        if (pty_rc == 0) break;
        if (pty_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "PTY request failed", pty_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "PTY request timed out", .{});
            return c.JNI_FALSE;
        }
    }
    // PTY negotiation already conveys TERM. Extra env vars are best-effort
    // because many OpenSSH servers reject channel-setenv unless AcceptEnv
    // explicitly allows them.
    trySetChannelEnv(session, channel.?, "COLORTERM", "truecolor");
    trySetChannelEnv(session, channel.?, "TERM_PROGRAM", "ghostty");
    trySetChannelEnv(session, channel.?, "TERM_PROGRAM_VERSION", "1");
    while (true) {
        const startup_rc = c.libssh2_channel_process_startup(channel.?, "shell", 5, null, 0);
        if (startup_rc == 0) break;
        if (startup_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Shell start failed", startup_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Shell start timed out", .{});
            return c.JNI_FALSE;
        }
    }
    setSocketNonBlocking(session.socket_fd);
    c.libssh2_session_set_blocking(ssh_session, 0);
    c.libssh2_channel_set_blocking(channel.?, 0);
    return c.JNI_TRUE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeOpenExec(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, command: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const command_slice = jniDupString(env, command) orelse {
        setError(session, "Missing exec command", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(command_slice);
    // Open a fresh session channel for the exec request. We do NOT request a
    // PTY here — exec channels for non-interactive commands like
    // `mosh-server new` should run without a TTY so the server can detach
    // cleanly, write its output to stdout, and close the channel (giving us
    // a real EOF signal instead of relying on a deadline).
    var channel: ?*c.LIBSSH2_CHANNEL = null;
    while (channel == null) {
        channel = c.libssh2_channel_open_ex(ssh_session, "session", 7, c.LIBSSH2_CHANNEL_WINDOW_DEFAULT, c.LIBSSH2_CHANNEL_PACKET_DEFAULT, null, 0);
        if (channel != null) break;
        const open_rc = c.libssh2_session_last_errno(ssh_session);
        if (open_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Channel open failed", open_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Channel open timed out", .{});
            return c.JNI_FALSE;
        }
    }
    c.libssh2_channel_set_blocking(channel.?, 0);

    while (true) {
        const startup_rc = c.libssh2_channel_process_startup(channel.?, "exec", 4, command_slice.ptr, @intCast(command_slice.len));
        if (startup_rc == 0) break;
        if (startup_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Exec start failed", startup_rc);
            _ = c.libssh2_channel_close(channel.?);
            _ = c.libssh2_channel_free(channel.?);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Exec start timed out", .{});
            _ = c.libssh2_channel_close(channel.?);
            _ = c.libssh2_channel_free(channel.?);
            return c.JNI_FALSE;
        }
    }
    if (session.channel) |old_ch| {
        _ = c.libssh2_channel_close(old_ch);
        _ = c.libssh2_channel_free(old_ch);
    }
    session.channel = channel;
    setSocketNonBlocking(session.socket_fd);
    c.libssh2_session_set_blocking(ssh_session, 0);
    c.libssh2_channel_set_blocking(channel.?, 0);
    return c.JNI_TRUE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeOpenExecPty(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, command: c.jstring, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint, term: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const command_slice = jniDupString(env, command) orelse {
        setError(session, "Missing exec command", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(command_slice);
    const term_slice = jniDupString(env, term) orelse {
        setError(session, "Missing TERM", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(term_slice);

    var channel: ?*c.LIBSSH2_CHANNEL = null;
    while (channel == null) {
        channel = c.libssh2_channel_open_ex(ssh_session, "session", 7, c.LIBSSH2_CHANNEL_WINDOW_DEFAULT, c.LIBSSH2_CHANNEL_PACKET_DEFAULT, null, 0);
        if (channel != null) break;
        const open_rc = c.libssh2_session_last_errno(ssh_session);
        if (open_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Channel open failed", open_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Channel open timed out", .{});
            return c.JNI_FALSE;
        }
    }
    if (session.channel) |old_ch| {
        _ = c.libssh2_channel_close(old_ch);
        _ = c.libssh2_channel_free(old_ch);
    }
    session.channel = channel;
    c.libssh2_channel_set_blocking(channel.?, 0);

    var term_buf = allocator.allocSentinel(u8, term_slice.len, 0) catch {
        setError(session, "TERM alloc failed", .{});
        return c.JNI_FALSE;
    };
    defer allocator.free(term_buf);
    @memcpy(term_buf[0..term_slice.len], term_slice);

    while (true) {
        const pty_rc = c.libssh2_channel_request_pty_ex(channel.?, term_buf.ptr, @intCast(term_slice.len), null, 0, cols, rows, width_px, height_px);
        if (pty_rc == 0) break;
        if (pty_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "PTY request failed", pty_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "PTY request timed out", .{});
            return c.JNI_FALSE;
        }
    }
    trySetChannelEnv(session, channel.?, "COLORTERM", "truecolor");
    trySetChannelEnv(session, channel.?, "TERM_PROGRAM", "ghostty");
    trySetChannelEnv(session, channel.?, "TERM_PROGRAM_VERSION", "1");

    while (true) {
        const startup_rc = c.libssh2_channel_process_startup(channel.?, "exec", 4, command_slice.ptr, @intCast(command_slice.len));
        if (startup_rc == 0) break;
        if (startup_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Exec start failed", startup_rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "Exec start timed out", .{});
            return c.JNI_FALSE;
        }
    }
    setSocketNonBlocking(session.socket_fd);
    c.libssh2_session_set_blocking(ssh_session, 0);
    c.libssh2_channel_set_blocking(channel.?, 0);
    return c.JNI_TRUE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeChannelEof(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_TRUE;
    const channel = session.channel orelse return c.JNI_TRUE;
    // libssh2_channel_eof returns 1 when the remote sent EOF on the channel
    // (e.g. mosh-server printed its CONNECT line and detached). Returning
    // EOF as "true" lets the Kotlin bootstrap loop exit immediately on
    // success instead of waiting out the full deadline.
    return if (c.libssh2_channel_eof(channel) == 1) c.JNI_TRUE else c.JNI_FALSE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeResize(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const channel = session.channel orelse return c.JNI_FALSE;
    while (true) {
        const rc = c.libssh2_channel_request_pty_size_ex(channel, cols, rows, width_px, height_px);
        if (rc == 0) return c.JNI_TRUE;
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "PTY resize failed", rc);
            return c.JNI_FALSE;
        }
        if (!waitSocket(session, setup_wait_timeout_ms)) {
            setError(session, "PTY resize timed out", .{});
            return c.JNI_FALSE;
        }
    }
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeIpcExchange(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, request: c.jbyteArray) callconv(.c) c.jbyteArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const req_bytes = readJByteArray(env, request) orelse return null;
    defer if (req_bytes.len > 0) allocator.free(req_bytes);

    var response: std.ArrayList(u8) = .empty;
    defer response.deinit(allocator);

    const frame = ipc.parse(req_bytes) catch {
        setError(session, "Invalid IPC frame", .{});
        appendErrorFrame(&response, session, "Invalid IPC frame");
        return jniNewByteArrayOrNull(env, response.items);
    };

    switch (frame.header.tag) {
        .Write => {
            const written = writeChannel(session, frame.payload);
            if (written < 0) {
                appendErrorFrame(&response, session, "Write failed");
                return jniNewByteArrayOrNull(env, response.items);
            }
            const written_u32: u32 = @intCast(written);
            ipc.appendMessage(allocator, &response, .Ack, std.mem.asBytes(&written_u32)) catch {};
        },
        .Read => {
            if (frame.payload.len != @sizeOf(u32)) {
                setError(session, "Invalid read request payload", .{});
                appendErrorFrame(&response, session, "Invalid read request payload");
                return jniNewByteArrayOrNull(env, response.items);
            }
            const max_bytes = std.mem.bytesToValue(u32, frame.payload[0..@sizeOf(u32)]);
            const bytes = readChannel(allocator, session, @intCast(@max(max_bytes, 1))) orelse {
                appendErrorFrame(&response, session, "Read failed");
                return jniNewByteArrayOrNull(env, response.items);
            };
            defer allocator.free(bytes);
            ipc.appendMessage(allocator, &response, .Data, bytes) catch {};
        },
        else => {
            setError(session, "Unsupported IPC tag {}", .{@intFromEnum(frame.header.tag)});
            appendErrorFrame(&response, session, "Unsupported IPC tag");
        },
    }

    return jniNewByteArrayOrNull(env, response.items);
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeClose(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) void {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return;
    // Best-effort SFTP teardown: even if it stalls (e.g. the peer is gone), we
    // must still free the channel, session, and socket below. libssh2_session_free
    // releases any remaining SFTP state, so a stalled shutdown is not fatal here.
    if (session.upload_handle) |upload_handle| {
        _ = closeSftpHandle(session, upload_handle, "native close upload handle");
        session.upload_handle = null;
    }
    if (session.sftp) |sftp| {
        _ = shutdownSftp(session, sftp, "native close");
        session.sftp = null;
    }
    if (session.channel) |channel| {
        _ = c.libssh2_channel_close(channel);
        _ = c.libssh2_channel_free(channel);
        session.channel = null;
    }
    if (session.session) |ssh_session| {
        _ = c.libssh2_session_disconnect_ex(ssh_session, c.SSH_DISCONNECT_BY_APPLICATION, "bye", "en");
        _ = c.libssh2_session_free(ssh_session);
        session.session = null;
    }
    closeSocket(session.socket_fd);
    session.socket_fd = -1;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpInit(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    return if (ensureSftp(session) != null) c.JNI_TRUE else c.JNI_FALSE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpListDirectory(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jobjectArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const sftp = ensureSftp(session) orelse return null;
    const path_bytes = jniDupString(env, path) orelse return null;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return null;
    defer allocator.free(path_z);

    const dir = sftpOpenDir(session, sftp, path_z) orelse {
        resetSftpAfterFailure(session, "opendir failure");
        return null;
    };

    var names: std.ArrayList([]u8) = .empty;
    defer {
        for (names.items) |n| allocator.free(n);
        names.deinit(allocator);
    }

    var entry_buf: [1024]u8 = undefined;
    var attrs: c.LIBSSH2_SFTP_ATTRIBUTES = undefined;
    const started_ms = nowMs();
    var idle_since_ms = started_ms;
    var eagain_count: u32 = 0;
    var read_failed = false;
    while (true) {
        const rc = c.libssh2_sftp_readdir_ex(dir, &entry_buf, @intCast(entry_buf.len), null, 0, &attrs);
        if (rc > 0) {
            const name = entry_buf[0..@intCast(rc)];
            if (std.mem.eql(u8, name, ".") or std.mem.eql(u8, name, "..")) continue;
            const kind: []const u8 = blk: {
                const has_perms = (attrs.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) != 0;
                if (!has_perms) break :blk "other";
                const mode: c_ulong = attrs.permissions;
                const kind_bits: c_ulong = mode & 0o170000;
                if (kind_bits == 0o040000) break :blk "dir";
                if (kind_bits == 0o100000) break :blk "file";
                if (kind_bits == 0o120000) break :blk "link";
                break :blk "other";
            };
            const size: u64 = if ((attrs.flags & c.LIBSSH2_SFTP_ATTR_SIZE) != 0) @intCast(attrs.filesize) else 0;
            const mtime: u64 = if ((attrs.flags & c.LIBSSH2_SFTP_ATTR_ACMODTIME) != 0) @intCast(attrs.mtime) else 0;
            const perms: u64 = if ((attrs.flags & c.LIBSSH2_SFTP_ATTR_PERMISSIONS) != 0) @intCast(attrs.permissions) else 0;
            const line = std.fmt.allocPrint(allocator, "{s}\t{s}\t{}\t{}\t{}", .{ name, kind, size, mtime, perms }) catch {
                setError(session, "SFTP list allocation failed after {} entries", .{names.items.len});
                read_failed = true;
                break;
            };
            const copy = line;
            names.append(allocator, copy) catch {
                allocator.free(copy);
                setError(session, "SFTP list allocation failed after {} entries", .{names.items.len});
                read_failed = true;
                break;
            };
            idle_since_ms = nowMs();
            continue;
        }
        if (rc == 0) break;
        if (rc == c.LIBSSH2_ERROR_EAGAIN) {
            eagain_count +%= 1;
            switch (awaitSftpProgress(session, &idle_since_ms)) {
                .retry => continue,
                .no_socket, .stalled => {
                    const now = nowMs();
                    setError(
                        session,
                        "SFTP readdir timed out for {s} after {d}ms (idle {d}ms, EAGAIN={d}, entries={d})",
                        .{ path_z, now - started_ms, now - idle_since_ms, eagain_count, names.items.len },
                    );
                    logError(
                        "SFTP readdir timeout path={s} elapsed={}ms idle={}ms eagain={} entries={}",
                        .{ path_z, now - started_ms, now - idle_since_ms, eagain_count, names.items.len },
                    );
                    read_failed = true;
                    break;
                },
            }
        }
        setSftpError(session, sftp, "SFTP readdir failed", @intCast(rc));
        read_failed = true;
        break;
    }

    if (read_failed) {
        _ = closeSftpHandle(session, dir, "SFTP closedir after readdir failure");
        resetSftpAfterFailure(session, "readdir failure");
        return null;
    }

    if (!closeSftpHandle(session, dir, "SFTP closedir after list")) {
        resetSftpAfterFailure(session, "closedir failure");
        return null;
    }

    const string_class = env.*.*.FindClass.?(env, "java/lang/String") orelse return null;
    const result = env.*.*.NewObjectArray.?(env, @intCast(names.items.len), string_class, null) orelse return null;
    for (names.items, 0..) |name, idx| {
        const j_name = jniNewStringOrNull(env, name) orelse continue;
        env.*.*.SetObjectArrayElement.?(env, result, @intCast(idx), j_name);
    }
    return result;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpRealpath(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jstring {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const sftp = ensureSftp(session) orelse return null;
    const path_bytes = jniDupString(env, path) orelse return null;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return null;
    defer allocator.free(path_z);

    var out_buf: [2048]u8 = undefined;
    const started_ms = nowMs();
    var idle_since_ms = started_ms;
    var eagain_count: u32 = 0;
    while (true) {
        const rc = c.libssh2_sftp_realpath(sftp, path_z.ptr, &out_buf, @as(c_int, @intCast(out_buf.len)));
        if (rc > 0) return jniNewStringOrNull(env, out_buf[0..@intCast(rc)]);
        const last_rc = c.libssh2_session_last_errno(session.session.?);
        if (last_rc != c.LIBSSH2_ERROR_EAGAIN) {
            setSftpError(session, sftp, "SFTP realpath failed", last_rc);
            return null;
        }

        eagain_count +%= 1;
        switch (awaitSftpProgress(session, &idle_since_ms)) {
            .retry => {},
            .no_socket, .stalled => {
                const now = nowMs();
                setError(
                    session,
                    "SFTP realpath timed out for {s} after {d}ms (idle {d}ms, EAGAIN={d})",
                    .{ path_z, now - started_ms, now - idle_since_ms, eagain_count },
                );
                logError(
                    "SFTP realpath timeout path={s} elapsed={}ms idle={}ms eagain={}",
                    .{ path_z, now - started_ms, now - idle_since_ms, eagain_count },
                );
                resetSftpAfterFailure(session, "realpath timeout");
                return null;
            },
        }
    }
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpOpenWrite(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const sftp = ensureSftp(session) orelse return c.JNI_FALSE;
    const path_bytes = jniDupString(env, path) orelse return c.JNI_FALSE;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return c.JNI_FALSE;
    defer allocator.free(path_z);

    if (session.upload_handle) |stale| {
        while (true) {
            const rc = c.libssh2_sftp_close_handle(stale);
            if (rc != c.LIBSSH2_ERROR_EAGAIN) break;
            if (!waitSocket(session, io_wait_timeout_ms)) break;
        }
        session.upload_handle = null;
    }

    const flags: c_ulong = c.LIBSSH2_FXF_WRITE | c.LIBSSH2_FXF_CREAT | c.LIBSSH2_FXF_TRUNC;
    const mode: c_long = c.LIBSSH2_SFTP_S_IRUSR | c.LIBSSH2_SFTP_S_IWUSR | c.LIBSSH2_SFTP_S_IRGRP | c.LIBSSH2_SFTP_S_IROTH;
    const file = sftpOpenFile(session, sftp, path_z, flags, mode) orelse return c.JNI_FALSE;
    session.upload_handle = file;
    return c.JNI_TRUE;
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpWriteChunk(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, data: c.jbyteArray) callconv(.c) c.jint {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return -1;
    const sftp_handle = session.upload_handle orelse return -1;

    const data_len = env.*.*.GetArrayLength.?(env, data);
    if (data_len == 0) return 0;
    const data_ptr = env.*.*.GetByteArrayElements.?(env, data, null) orelse return -1;
    defer env.*.*.ReleaseByteArrayElements.?(env, data, data_ptr, c.JNI_ABORT);
    const ptr: [*]const u8 = @ptrCast(data_ptr);
    const sftp_write_idle_limit_ms: i64 = 30_000;
    var written: usize = 0;
    var idle_since_ms: i64 = std.time.milliTimestamp();
    while (written < @as(usize, @intCast(data_len))) {
        const remaining: usize = @as(usize, @intCast(data_len)) - written;
        const chunk_size: usize = @min(remaining, 32768);
        const rc = c.libssh2_sftp_write(sftp_handle, ptr + written, chunk_size);
        if (rc == c.LIBSSH2_ERROR_EAGAIN or rc == 0) {
            if (waitSocket(session, io_wait_timeout_ms)) {
                idle_since_ms = std.time.milliTimestamp();
            } else if (std.time.milliTimestamp() - idle_since_ms > sftp_write_idle_limit_ms) {
                setError(session, "SFTP write stalled (no socket activity for {d}ms)", .{sftp_write_idle_limit_ms});
                return -1;
            }
            continue;
        }
        if (rc < 0) {
            setLibssh2Error(session, "SFTP write failed", @intCast(rc));
            return -1;
        }
        written += @intCast(rc);
        idle_since_ms = std.time.milliTimestamp();
    }

    return @intCast(written);
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpCloseWrite(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = thiz;
    _ = env;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const sftp_handle = session.upload_handle orelse return c.JNI_FALSE;
    session.upload_handle = null;
    const close_idle_limit_ms: i64 = 10_000;
    var idle_since_ms: i64 = std.time.milliTimestamp();
    while (true) {
        const rc = c.libssh2_sftp_close_handle(sftp_handle);
        if (rc == 0) return c.JNI_TRUE;
        if (rc == c.LIBSSH2_ERROR_EAGAIN) {
            if (waitSocket(session, io_wait_timeout_ms)) {
                idle_since_ms = std.time.milliTimestamp();
                continue;
            }
            if (std.time.milliTimestamp() - idle_since_ms < close_idle_limit_ms) continue;
            setError(session, "SFTP close stalled (no socket activity for {d}ms)", .{close_idle_limit_ms});
            return c.JNI_FALSE;
        }
        setLibssh2Error(session, "SFTP close failed", @intCast(rc));
        return c.JNI_FALSE;
    }
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpReadFile(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring, max_bytes: c.jint) callconv(.c) c.jbyteArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const sftp = ensureSftp(session) orelse return null;
    const path_bytes = jniDupString(env, path) orelse return null;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return null;
    defer allocator.free(path_z);
    const file = sftpOpenFile(session, sftp, path_z, c.LIBSSH2_FXF_READ, 0) orelse return null;
    defer {
        while (true) {
            const rc = c.libssh2_sftp_close_handle(file);
            if (rc == 0) break;
            if (rc == c.LIBSSH2_ERROR_EAGAIN) {
                if (waitSocket(session, io_wait_timeout_ms)) continue;
            }
            logInfo("SFTP close handle failed rc={}", .{rc});
            break;
        }
    }

    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(allocator);
    const limit: usize = if (max_bytes <= 0) 4096 else @intCast(max_bytes);
    var buf: [32768]u8 = undefined;
    while (out.items.len < limit) {
        const want = @min(buf.len, limit - out.items.len);
        const rc = c.libssh2_sftp_read(file, &buf, want);
        if (rc > 0) {
            out.appendSlice(allocator, buf[0..@intCast(rc)]) catch return null;
            continue;
        }
        if (rc == 0) break;
        if (rc == c.LIBSSH2_ERROR_EAGAIN and waitSocket(session, io_wait_timeout_ms)) continue;
        setLibssh2Error(session, "SFTP read failed", @intCast(rc));
        return null;
    }
    return jniNewByteArrayOrNull(env, out.items);
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpDeleteFile(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const sftp = ensureSftp(session) orelse return c.JNI_FALSE;
    const path_bytes = jniDupString(env, path) orelse return c.JNI_FALSE;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return c.JNI_FALSE;
    defer allocator.free(path_z);
    while (true) {
        const rc = c.libssh2_sftp_unlink_ex(sftp, path_z.ptr, @intCast(path_z.len));
        if (rc == 0) return c.JNI_TRUE;
        if (rc == c.LIBSSH2_ERROR_EAGAIN and waitSocket(session, io_wait_timeout_ms)) continue;
        setLibssh2Error(session, "SFTP delete file failed", @intCast(rc));
        return c.JNI_FALSE;
    }
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeSftpDeleteDirectory(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const sftp = ensureSftp(session) orelse return c.JNI_FALSE;
    const path_bytes = jniDupString(env, path) orelse return c.JNI_FALSE;
    defer allocator.free(path_bytes);
    const path_z = dupSentinel(path_bytes) orelse return c.JNI_FALSE;
    defer allocator.free(path_z);
    while (true) {
        const rc = c.libssh2_sftp_rmdir_ex(sftp, path_z.ptr, @intCast(path_z.len));
        if (rc == 0) return c.JNI_TRUE;
        if (rc == c.LIBSSH2_ERROR_EAGAIN and waitSocket(session, io_wait_timeout_ms)) continue;
        setLibssh2Error(session, "SFTP delete directory failed", @intCast(rc));
        return c.JNI_FALSE;
    }
}

fn sftpOpenFile(session: *NativeSshSession, sftp: *c.LIBSSH2_SFTP, path_z: [:0]u8, flags: c_ulong, mode: c_long) ?*c.LIBSSH2_SFTP_HANDLE {
    while (true) {
        const file = c.libssh2_sftp_open(sftp, path_z.ptr, flags, mode);
        if (file != null) return file;
        const rc = c.libssh2_session_last_errno(session.session.?);
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "SFTP open failed", rc);
            return null;
        }
        if (!waitSocket(session, io_wait_timeout_ms)) {
            setError(session, "SFTP open timed out", .{});
            return null;
        }
    }
}

// ---------------------------------------------------------------------------
// Ed25519 key generation in OpenSSH format (openssh-key-v1)
// ---------------------------------------------------------------------------

const Ed25519 = std.crypto.sign.Ed25519;
const Aes256 = std.crypto.core.aes.Aes256;
const bcrypt = std.crypto.pwhash.bcrypt;

const openssh_auth_magic = "openssh-key-v1\x00";
const openssh_kdf_rounds: u32 = 16;
const openssh_salt_len = 16;
// AES-256-CTR block size
const aes_block_len = 16;

/// Append a uint32 big-endian to an ArrayList.
fn sshPutU32(list: *std.ArrayListUnmanaged(u8), value: u32) !void {
    const bytes: [4]u8 = @bitCast(std.mem.nativeToBig(u32, value));
    try list.appendSlice(allocator, &bytes);
}

/// Append a length-prefixed SSH string to an ArrayList.
fn sshPutString(list: *std.ArrayListUnmanaged(u8), data: []const u8) !void {
    try sshPutU32(list, @intCast(data.len));
    try list.appendSlice(allocator, data);
}

/// Build the public key blob: string "ssh-ed25519" + string <32-byte pubkey>
fn buildPublicKeyBlob(pub_key: [32]u8) ![]u8 {
    var blob: std.ArrayListUnmanaged(u8) = .empty;
    errdefer blob.deinit(allocator);
    try sshPutString(&blob, "ssh-ed25519");
    try sshPutString(&blob, &pub_key);
    return blob.toOwnedSlice(allocator);
}

/// Build the unencrypted private section of the OpenSSH key.
fn buildPrivateSection(
    seed: [32]u8,
    pub_key: [32]u8,
    comment: []const u8,
    check: u32,
) ![]u8 {
    var section: std.ArrayListUnmanaged(u8) = .empty;
    errdefer section.deinit(allocator);

    // Two identical check integers
    try sshPutU32(&section, check);
    try sshPutU32(&section, check);

    // keytype
    try sshPutString(&section, "ssh-ed25519");
    // public key (32 bytes, length-prefixed)
    try sshPutString(&section, &pub_key);
    // private key: 64 bytes = seed(32) || pubkey(32), length-prefixed
    var priv_blob: [64]u8 = undefined;
    @memcpy(priv_blob[0..32], &seed);
    @memcpy(priv_blob[32..64], &pub_key);
    try sshPutString(&section, &priv_blob);
    // comment
    try sshPutString(&section, comment);

    // Padding: 1, 2, 3, ... up to block alignment
    const pad_block = aes_block_len;
    const remainder = section.items.len % pad_block;
    if (remainder != 0) {
        const pad_len = pad_block - remainder;
        var i: u8 = 1;
        while (i <= pad_len) : (i += 1) {
            try section.append(allocator, i);
        }
    }

    return section.toOwnedSlice(allocator);
}

/// Encode a raw blob as a PEM block with the given header/footer.
fn encodePem(
    header: []const u8,
    footer: []const u8,
    data: []const u8,
) ![]u8 {
    const b64_encoder = std.base64.standard.Encoder;
    const encoded_len = b64_encoder.calcSize(data.len);
    // Number of lines: ceil(encoded_len / 70)
    const num_lines = (encoded_len + 69) / 70;
    // Total: header + encoded chars + newlines after each line + footer
    const total = header.len + encoded_len + num_lines + footer.len;
    const buf = try allocator.alloc(u8, total);
    errdefer allocator.free(buf);

    @memcpy(buf[0..header.len], header);
    var pos: usize = header.len;

    // Base64-encode and wrap at 70 characters
    const encoded = try allocator.alloc(u8, encoded_len);
    defer allocator.free(encoded);
    _ = b64_encoder.encode(encoded, data);

    var offset: usize = 0;
    while (offset < encoded.len) {
        const chunk_end = @min(offset + 70, encoded.len);
        const chunk = encoded[offset..chunk_end];
        @memcpy(buf[pos..][0..chunk.len], chunk);
        pos += chunk.len;
        buf[pos] = '\n';
        pos += 1;
        offset = chunk_end;
    }

    @memcpy(buf[pos..][0..footer.len], footer);
    pos += footer.len;

    return buf[0..pos];
}

/// Generate an Ed25519 keypair and encode as OpenSSH format.
/// If passphrase is non-empty, encrypt with bcrypt + aes256-ctr.
/// Returns the private key PEM and public key OpenSSH string via out params.
fn generateEd25519Key(
    comment: []const u8,
    passphrase: []const u8,
    out_private_pem: *[]u8,
    out_public_openssh: *[]u8,
) !void {
    const key_pair = Ed25519.KeyPair.generate();
    const pub_key: [32]u8 = key_pair.public_key.bytes;
    const seed: [32]u8 = key_pair.secret_key.seed();

    const check = std.crypto.random.int(u32);

    // Build private section (plaintext)
    const private_section = try buildPrivateSection(seed, pub_key, comment, check);
    defer allocator.free(private_section);

    // Build public key blob
    const pub_blob = try buildPublicKeyBlob(pub_key);
    defer allocator.free(pub_blob);

    // Assemble the full openssh-key-v1 binary
    var key_data: std.ArrayListUnmanaged(u8) = .empty;
    defer key_data.deinit(allocator);

    // Magic
    try key_data.appendSlice(allocator, openssh_auth_magic);

    const encrypted = passphrase.len > 0;

    if (encrypted) {
        // ciphername, kdfname
        try sshPutString(&key_data, "aes256-ctr");
        try sshPutString(&key_data, "bcrypt");

        // kdfoptions: string(salt) + uint32(rounds)
        var kdf_opts: std.ArrayListUnmanaged(u8) = .empty;
        defer kdf_opts.deinit(allocator);
        var salt: [openssh_salt_len]u8 = undefined;
        std.crypto.random.bytes(&salt);
        try sshPutString(&kdf_opts, &salt);
        try sshPutU32(&kdf_opts, openssh_kdf_rounds);
        try sshPutString(&key_data, kdf_opts.items);

        // number of keys
        try sshPutU32(&key_data, 1);
        // public key blob
        try sshPutString(&key_data, pub_blob);

        // Derive 48 bytes: 32 for AES key + 16 for IV
        var derived: [48]u8 = undefined;
        try bcrypt.opensshKdf(passphrase, &salt, &derived, openssh_kdf_rounds);
        const aes_key: [32]u8 = derived[0..32].*;
        const iv: [aes_block_len]u8 = derived[32..48].*;

        // Encrypt private section in-place with AES-256-CTR
        const encrypted_section = try allocator.alloc(u8, private_section.len);
        defer allocator.free(encrypted_section);
        const ctx = Aes256.initEnc(aes_key);
        std.crypto.core.modes.ctr(
            @TypeOf(ctx),
            ctx,
            encrypted_section,
            private_section,
            iv,
            .big,
        );

        try sshPutString(&key_data, encrypted_section);
    } else {
        // No encryption
        try sshPutString(&key_data, "none"); // ciphername
        try sshPutString(&key_data, "none"); // kdfname
        try sshPutString(&key_data, ""); // kdfoptions (empty)
        try sshPutU32(&key_data, 1); // number of keys
        try sshPutString(&key_data, pub_blob); // public key
        try sshPutString(&key_data, private_section); // private section
    }

    // Encode as PEM
    out_private_pem.* = try encodePem(
        "-----BEGIN OPENSSH PRIVATE KEY-----\n",
        "-----END OPENSSH PRIVATE KEY-----\n",
        key_data.items,
    );

    // Build "ssh-ed25519 <base64> <comment>" public key line
    const b64_encoder = std.base64.standard.Encoder;
    const b64_len = b64_encoder.calcSize(pub_blob.len);
    // "ssh-ed25519 " + base64 + " " + comment + "\n"
    const pub_line_len = 12 + b64_len + 1 + comment.len + 1;
    const pub_line = try allocator.alloc(u8, pub_line_len);
    errdefer allocator.free(pub_line);

    @memcpy(pub_line[0..12], "ssh-ed25519 ");
    _ = b64_encoder.encode(pub_line[12..][0..b64_len], pub_blob);
    pub_line[12 + b64_len] = ' ';
    @memcpy(pub_line[12 + b64_len + 1 ..][0..comment.len], comment);
    pub_line[pub_line_len - 1] = '\n';

    out_public_openssh.* = pub_line;
}

/// JNI entry point: generate an Ed25519 key pair in OpenSSH format.
/// Returns a String array [privateKeyPem, publicKeyOpenSsh], or null on error.
export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeGenerateEd25519Key(
    env: *c.JNIEnv,
    thiz: c.jobject,
    j_comment: c.jstring,
    j_passphrase: c.jstring,
) callconv(.c) c.jobjectArray {
    _ = thiz;

    const comment_owned = jniDupString(env, j_comment);
    defer if (comment_owned) |s| allocator.free(s);
    const comment_slice: []const u8 = comment_owned orelse "chuchu";

    const passphrase_owned = jniDupString(env, j_passphrase);
    defer if (passphrase_owned) |s| allocator.free(s);
    const passphrase_slice: []const u8 = passphrase_owned orelse "";

    var private_pem: []u8 = undefined;
    var public_openssh: []u8 = undefined;

    generateEd25519Key(comment_slice, passphrase_slice, &private_pem, &public_openssh) catch |err| {
        logError("Ed25519 keygen failed: {}", .{err});
        return null;
    };
    defer allocator.free(private_pem);
    defer allocator.free(public_openssh);

    // Build a String[2] to return
    const string_class = env.*.*.FindClass.?(env, "java/lang/String") orelse return null;
    const result = env.*.*.NewObjectArray.?(env, 2, string_class, null) orelse return null;

    const j_priv = jniNewStringOrNull(env, private_pem);
    const j_pub = jniNewStringOrNull(env, public_openssh);
    if (j_priv == null or j_pub == null) return null;

    env.*.*.SetObjectArrayElement.?(env, result, 0, j_priv);
    env.*.*.SetObjectArrayElement.?(env, result, 1, j_pub);

    return result;
}
