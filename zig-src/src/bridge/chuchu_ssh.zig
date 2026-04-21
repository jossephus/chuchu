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
            return jniNewByteArrayOrNull(env, &.{});
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
