const std = @import("std");

const c = @cImport({
    @cInclude("android/log.h");
    @cInclude("jni.h");
    @cInclude("libssh2.h");
    @cInclude("sys/socket.h");
    @cInclude("netdb.h");
    @cInclude("arpa/inet.h");
    @cInclude("unistd.h");
    @cInclude("fcntl.h");
    @cInclude("errno.h");
    @cInclude("poll.h");
});

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

const NativeSshSession = struct {
    socket_fd: c_int = -1,
    session: ?*c.LIBSSH2_SESSION = null,
    channel: ?*c.LIBSSH2_CHANNEL = null,
    username: ?[]u8 = null,
    hostkey_ptr: ?[*]const u8 = null,
    hostkey_len: usize = 0,
    hostkey_type: c_int = 0,
    hostkey_copy: ?[]u8 = null,
    last_error: std.ArrayListUnmanaged(u8) = .empty,
    empty_reads: u32 = 0,
};

const KeyboardAuthContext = struct {
    password: []const u8,
};

fn keyboardInteractiveCallback(
    name: [*c]const u8,
    name_len: c_int,
    instruction: [*c]const u8,
    instruction_len: c_int,
    num_prompts: c_int,
    prompts: [*c]const c.LIBSSH2_USERAUTH_KBDINT_PROMPT,
    responses: [*c]c.LIBSSH2_USERAUTH_KBDINT_RESPONSE,
    abstract: ?*?*anyopaque,
) callconv(.c) void {
    _ = name;
    _ = name_len;
    _ = instruction;
    _ = instruction_len;
    if (abstract == null or abstract.?.* == null or num_prompts <= 0) return;

    const ctx: *KeyboardAuthContext = @ptrCast(@alignCast(abstract.?.*));
    const count: usize = @intCast(num_prompts);
    var i: usize = 0;
    while (i < count) : (i += 1) {
        _ = prompts[i];
        const dup = allocator.alloc(u8, ctx.password.len) catch {
            responses[i].text = null;
            responses[i].length = 0;
            continue;
        };
        @memcpy(dup, ctx.password);
        responses[i].text = @ptrCast(dup.ptr);
        responses[i].length = @intCast(dup.len);
    }
}

fn sessionFromHandle(handle: c.jlong) ?*NativeSshSession {
    if (handle == 0) return null;
    return @ptrFromInt(@as(usize, @bitCast(handle)));
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
    return @bitCast(@intFromPtr(session));
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
    logInfo("Password auth attempt start", .{});
    const password_deadline_ms = nowMs() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_password_ex(ssh_session, username_slice.ptr, @intCast(username_slice.len), password_slice.ptr, @intCast(password_slice.len), null);
        if (rc == 0) {
            logInfo("Password auth succeeded", .{});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            logError("Password auth failed rc={} (trying keyboard-interactive)", .{rc});
            break;
        }
        if (nowMs() >= password_deadline_ms) {
            logError("Password auth timed out (trying keyboard-interactive)", .{});
            break;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }

    logInfo("Keyboard-interactive auth attempt start", .{});
    var ctx = KeyboardAuthContext{ .password = password_slice };
    const abstract_ptr = c.libssh2_session_abstract(ssh_session);
    if (abstract_ptr != null) {
        abstract_ptr.* = @ptrCast(&ctx);
    }
    defer if (abstract_ptr != null) {
        abstract_ptr.* = null;
    };

    const kbd_deadline_ms = nowMs() + setup_wait_timeout_ms;
    while (true) {
        const rc = c.libssh2_userauth_keyboard_interactive_ex(
            ssh_session,
            username_slice.ptr,
            @intCast(username_slice.len),
            keyboardInteractiveCallback,
        );
        if (rc == 0) {
            logInfo("Keyboard-interactive auth succeeded", .{});
            return c.JNI_TRUE;
        }
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            setLibssh2Error(session, "Keyboard-interactive auth failed", rc);
            return c.JNI_FALSE;
        }
        if (nowMs() >= kbd_deadline_ms) {
            setError(session, "Keyboard-interactive auth timed out", .{});
            return c.JNI_FALSE;
        }
        _ = waitSocket(session, io_wait_timeout_ms);
    }

    return c.JNI_FALSE;
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

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeOpenShell(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, cols: c.jint, rows: c.jint, width_px: c.jint, height_px: c.jint, term: c.jstring) callconv(.c) c.jboolean {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return c.JNI_FALSE;
    const ssh_session = session.session orelse {
        setError(session, "Not connected", .{});
        return c.JNI_FALSE;
    };
    const term_slice = jniDupString(env, term) orelse allocator.dupe(u8, "xterm-kitty") catch return c.JNI_FALSE;
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

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeWrite(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, data: c.jbyteArray) callconv(.c) c.jint {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return -1;
    const channel = session.channel orelse {
        setError(session, "Shell not open", .{});
        return -1;
    };
    const bytes = readJByteArray(env, data) orelse return -1;
    defer if (bytes.len > 0) allocator.free(bytes);
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

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeRead(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, max_bytes: c.jint) callconv(.c) c.jbyteArray {
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return null;
    const channel = session.channel orelse return null;
    const cap: usize = @intCast(@max(max_bytes, 1));
    const buf = allocator.alloc(u8, cap) catch return null;
    defer allocator.free(buf);
    var total_read: usize = 0;
    while (true) {
        const rc = c.libssh2_channel_read_ex(channel, 0, @ptrCast(buf.ptr + total_read), @intCast(buf.len - total_read));
        if (rc == c.LIBSSH2_ERROR_EAGAIN) {
            session.empty_reads +%= 1;
            if (total_read > 0) {
                session.empty_reads = 0;
                return jniNewByteArrayOrNull(env, buf[0..total_read]);
            }
            if (!waitSocket(session, io_wait_timeout_ms)) {
                return jniNewByteArrayOrNull(env, &.{});
            }
            continue;
        }
        if (rc == 0) {
            if (total_read > 0) {
                session.empty_reads = 0;
                return jniNewByteArrayOrNull(env, buf[0..total_read]);
            }
            return jniNewByteArrayOrNull(env, &.{});
        }
        if (rc < 0) {
            setLibssh2Error(session, "Read failed", @intCast(rc));
            return null;
        }
        total_read += @intCast(rc);
        if (total_read >= buf.len) {
            session.empty_reads = 0;
            return jniNewByteArrayOrNull(env, buf[0..total_read]);
        }
    }
}

export fn Java_com_jossephus_chuchu_service_ssh_NativeSshBridge_nativeClose(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) void {
    _ = env;
    _ = thiz;
    const session = sessionFromHandle(handle) orelse return;
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
