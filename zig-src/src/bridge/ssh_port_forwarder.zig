const Self = @This();

const std = @import("std");

const c = @import("c");
const Buffer = @import("ssh_jump_relay.zig").Buffer;

const allocator = std.heap.c_allocator;
const idle_poll_timeout_ms = 250;
const setup_timeout_ms = 10_000;
const keepalive_interval_s = 30;
const listen_backlog = 16;
const max_rules = 16;
const max_connections = 64;
const loopback_v4: u32 = 0x7f000001;

pub const Kind = enum(u8) { local = 0, remote = 1 };

pub const SetupError = error{
    TooManyRules,
    PortInUse,
    PortNotPermitted,
    LocalListenFailed,
    RemoteListenRefused,
    RemoteListenTimedOut,
    OutOfMemory,
};

const Rule = struct {
    kind: Kind,
    bind_port: u16,
    target_host: [:0]u8,
    target_port: u16,
    listen_fd: c_int = -1,
    listener: ?*c.LIBSSH2_LISTENER = null,
};

const Connection = struct {
    channel: ?*c.LIBSSH2_CHANNEL = null,
    fd: c_int,
    pending_rule: ?usize = null,
    to_local: Buffer = .{},
    to_remote: Buffer = .{},
    local_eof: bool = false,
    remote_eof: bool = false,
    eof_sent: bool = false,
    local_shut: bool = false,
    dead: bool = false,
};

const Step = enum { idle, progressed };

session: *c.LIBSSH2_SESSION,
ssh_fd: c_int,
rules: std.ArrayList(Rule) = .empty,
connections: std.ArrayList(*Connection) = .empty,
stop: std.atomic.Value(bool) = .init(false),
running: std.atomic.Value(bool) = .init(false),
thread: ?std.Thread = null,

pub fn init(session: *c.LIBSSH2_SESSION, ssh_fd: c_int) !*Self {
    std.debug.assert(ssh_fd >= 0);
    const self = try allocator.create(Self);
    self.* = .{ .session = session, .ssh_fd = ssh_fd };
    setNonBlocking(ssh_fd);
    return self;
}

pub fn deinit(self: *Self) void {
    self.stop.store(true, .release);
    if (self.thread) |thread| thread.join();
    for (self.connections.items) |conn| destroyConnection(conn);
    self.connections.deinit(allocator);
    for (self.rules.items) |*rule| {
        if (rule.listen_fd >= 0) _ = c.close(rule.listen_fd);
        if (rule.listener) |listener| _ = c.libssh2_channel_forward_cancel(listener);
        allocator.free(rule.target_host);
    }
    self.rules.deinit(allocator);
    allocator.destroy(self);
}

pub fn addLocal(self: *Self, bind_port: u16, target_host: []const u8, target_port: u16) SetupError!void {
    std.debug.assert(self.thread == null);
    if (self.rules.items.len >= max_rules) return error.TooManyRules;
    const fd = try listenLoopback(bind_port);
    errdefer _ = c.close(fd);
    try self.appendRule(.{
        .kind = .local,
        .bind_port = bind_port,
        .target_host = try dupHost(target_host),
        .target_port = target_port,
        .listen_fd = fd,
    });
}

pub fn addRemote(self: *Self, bind_port: u16, target_host: []const u8, target_port: u16) SetupError!void {
    std.debug.assert(self.thread == null);
    if (self.rules.items.len >= max_rules) return error.TooManyRules;
    const listener = try self.forwardListen(bind_port);
    errdefer _ = c.libssh2_channel_forward_cancel(listener);
    try self.appendRule(.{
        .kind = .remote,
        .bind_port = bind_port,
        .target_host = try dupHost(target_host),
        .target_port = target_port,
        .listener = listener,
    });
}

pub fn start(self: *Self) !void {
    std.debug.assert(self.thread == null);
    c.libssh2_keepalive_config(self.session, 1, keepalive_interval_s);
    self.running.store(true, .release);
    errdefer self.running.store(false, .release);
    self.thread = try std.Thread.spawn(.{ .stack_size = 256 * 1024 }, run, .{self});
}

pub fn isRunning(self: *const Self) bool {
    return self.running.load(.acquire);
}

fn appendRule(self: *Self, rule: Rule) SetupError!void {
    self.rules.append(allocator, rule) catch {
        allocator.free(rule.target_host);
        return error.OutOfMemory;
    };
}

fn dupHost(host: []const u8) SetupError![:0]u8 {
    const out = allocator.allocSentinel(u8, host.len, 0) catch return error.OutOfMemory;
    @memcpy(out[0..host.len], host);
    return out;
}

fn listenLoopback(port: u16) SetupError!c_int {
    const fd = c.socket(c.AF_INET, c.SOCK_STREAM, 0);
    if (fd < 0) return error.LocalListenFailed;
    errdefer _ = c.close(fd);
    const reuse: c_int = 1;
    _ = c.setsockopt(fd, c.SOL_SOCKET, c.SO_REUSEADDR, &reuse, @sizeOf(c_int));
    var addr: c.struct_sockaddr_in = std.mem.zeroes(c.struct_sockaddr_in);
    addr.sin_family = c.AF_INET;
    addr.sin_port = std.mem.nativeToBig(u16, port);
    addr.sin_addr.s_addr = std.mem.nativeToBig(u32, loopback_v4);
    if (c.bind(fd, @ptrCast(&addr), @sizeOf(c.struct_sockaddr_in)) != 0) {
        return switch (c.__errno().*) {
            c.EADDRINUSE => error.PortInUse,
            c.EACCES, c.EPERM => error.PortNotPermitted,
            else => error.LocalListenFailed,
        };
    }
    if (c.listen(fd, listen_backlog) != 0) return error.LocalListenFailed;
    setNonBlocking(fd);
    return fd;
}

fn forwardListen(self: *Self, port: u16) SetupError!*c.LIBSSH2_LISTENER {
    var bound_port: c_int = 0;
    var rounds: usize = 0;
    while (rounds < setup_timeout_ms / idle_poll_timeout_ms) : (rounds += 1) {
        const listener = c.libssh2_channel_forward_listen_ex(self.session, "127.0.0.1", port, &bound_port, listen_backlog);
        if (listener) |opened| return opened;
        if (c.libssh2_session_last_errno(self.session) != c.LIBSSH2_ERROR_EAGAIN) return error.RemoteListenRefused;
        self.waitSession(idle_poll_timeout_ms);
    }
    return error.RemoteListenTimedOut;
}

fn waitSession(self: *Self, timeout_ms: c_int) void {
    var poll_fd: c.struct_pollfd = .{ .fd = self.ssh_fd, .events = self.sessionEvents(), .revents = 0 };
    _ = c.poll(&poll_fd, 1, timeout_ms);
}

fn sessionEvents(self: *Self) c_short {
    const directions = c.libssh2_session_block_directions(self.session);
    var events: c_short = c.POLLIN;
    if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) events |= c.POLLOUT;
    return events;
}

fn run(self: *Self) void {
    defer self.running.store(false, .release);
    while (!self.stop.load(.acquire)) {
        var progressed = false;
        if (self.acceptLocal() == .progressed) progressed = true;
        if (self.acceptRemote() == .progressed) progressed = true;
        if (self.openPending() == .progressed) progressed = true;
        if (self.pumpAll() == .progressed) progressed = true;
        self.reapDead();
        var next_keepalive: c_int = 0;
        _ = c.libssh2_keepalive_send(self.session, &next_keepalive);
        if (progressed) continue;
        if (!self.waitForIo()) break;
    }
}

fn acceptLocal(self: *Self) Step {
    var step: Step = .idle;
    for (self.rules.items, 0..) |rule, index| {
        if (rule.kind != .local) continue;
        while (self.connections.items.len < max_connections) {
            const fd = c.accept(rule.listen_fd, null, null);
            if (fd < 0) break;
            setNonBlocking(fd);
            self.addConnection(.{ .fd = fd, .pending_rule = index }) catch {
                _ = c.close(fd);
                break;
            };
            step = .progressed;
        }
    }
    return step;
}

fn acceptRemote(self: *Self) Step {
    var step: Step = .idle;
    for (self.rules.items) |rule| {
        if (rule.kind != .remote) continue;
        while (self.connections.items.len < max_connections) {
            const channel = c.libssh2_channel_forward_accept(rule.listener.?) orelse break;
            const fd = connectTarget(rule.target_host, rule.target_port) orelse {
                closeChannel(channel);
                continue;
            };
            setNonBlocking(fd);
            self.addConnection(.{ .fd = fd, .channel = channel }) catch {
                closeChannel(channel);
                _ = c.close(fd);
                break;
            };
            step = .progressed;
        }
    }
    return step;
}

fn openPending(self: *Self) Step {
    for (self.connections.items) |conn| {
        const index = conn.pending_rule orelse continue;
        const rule = self.rules.items[index];
        const channel = c.libssh2_channel_direct_tcpip_ex(self.session, rule.target_host.ptr, rule.target_port, "127.0.0.1", rule.bind_port);
        if (channel) |opened| {
            conn.channel = opened;
            conn.pending_rule = null;
            return .progressed;
        }
        if (c.libssh2_session_last_errno(self.session) == c.LIBSSH2_ERROR_EAGAIN) return .idle;
        conn.pending_rule = null;
        conn.dead = true;
        return .progressed;
    }
    return .idle;
}

fn addConnection(self: *Self, conn: Connection) !void {
    const owned = try allocator.create(Connection);
    errdefer allocator.destroy(owned);
    owned.* = conn;
    try self.connections.append(allocator, owned);
}

fn pumpAll(self: *Self) Step {
    var step: Step = .idle;
    for (self.connections.items) |conn| {
        if (conn.dead or conn.channel == null) continue;
        if (pump(conn) == .progressed) step = .progressed;
    }
    return step;
}

fn pump(conn: *Connection) Step {
    var step: Step = .idle;
    const steps = [_]*const fn (*Connection) Step{ readChannel, writeLocal, readLocal, writeChannel, propagateEof };
    for (steps) |step_fn| {
        if (conn.dead) return .progressed;
        if (step_fn(conn) == .progressed) step = .progressed;
    }
    if (conn.remote_eof and conn.local_eof and conn.eof_sent and conn.to_local.isEmpty()) conn.dead = true;
    return step;
}

fn readChannel(conn: *Connection) Step {
    if (conn.remote_eof or !conn.to_local.isEmpty()) return .idle;
    const rc = c.libssh2_channel_read_ex(conn.channel.?, 0, &conn.to_local.bytes, conn.to_local.bytes.len);
    if (rc > 0) {
        conn.to_local.fill(@intCast(rc));
        return .progressed;
    }
    if (rc == c.LIBSSH2_ERROR_EAGAIN) return .idle;
    if (rc == 0 and c.libssh2_channel_eof(conn.channel.?) == 0) return .idle;
    if (rc < 0) conn.dead = true;
    conn.remote_eof = true;
    return .progressed;
}

fn writeLocal(conn: *Connection) Step {
    if (conn.to_local.isEmpty()) return .idle;
    const pending = conn.to_local.pending();
    const rc = c.write(conn.fd, pending.ptr, pending.len);
    if (rc > 0) {
        conn.to_local.consume(@intCast(rc));
        return .progressed;
    }
    if (rc < 0 and wouldBlock()) return .idle;
    conn.dead = true;
    return .progressed;
}

fn readLocal(conn: *Connection) Step {
    if (conn.local_eof or !conn.to_remote.isEmpty()) return .idle;
    const rc = c.read(conn.fd, &conn.to_remote.bytes, conn.to_remote.bytes.len);
    if (rc > 0) {
        conn.to_remote.fill(@intCast(rc));
        return .progressed;
    }
    if (rc < 0 and wouldBlock()) return .idle;
    if (rc < 0) conn.dead = true;
    conn.local_eof = true;
    return .progressed;
}

fn writeChannel(conn: *Connection) Step {
    if (conn.to_remote.isEmpty()) return .idle;
    const pending = conn.to_remote.pending();
    const rc = c.libssh2_channel_write_ex(conn.channel.?, 0, pending.ptr, pending.len);
    if (rc > 0) {
        conn.to_remote.consume(@intCast(rc));
        return .progressed;
    }
    if (rc == 0 or rc == c.LIBSSH2_ERROR_EAGAIN) return .idle;
    conn.dead = true;
    return .progressed;
}

fn propagateEof(conn: *Connection) Step {
    var step: Step = .idle;
    if (conn.local_eof and !conn.eof_sent and conn.to_remote.isEmpty()) {
        const rc = c.libssh2_channel_send_eof(conn.channel.?);
        if (rc != c.LIBSSH2_ERROR_EAGAIN) {
            conn.eof_sent = true;
            step = .progressed;
        }
    }
    if (conn.remote_eof and !conn.local_shut and conn.to_local.isEmpty()) {
        _ = c.shutdown(conn.fd, c.SHUT_WR);
        conn.local_shut = true;
        step = .progressed;
    }
    return step;
}

fn reapDead(self: *Self) void {
    var index: usize = 0;
    while (index < self.connections.items.len) {
        const conn = self.connections.items[index];
        if (conn.dead and conn.pending_rule == null) {
            destroyConnection(self.connections.swapRemove(index));
        } else {
            index += 1;
        }
    }
}

fn destroyConnection(conn: *Connection) void {
    if (conn.channel) |channel| closeChannel(channel);
    _ = c.close(conn.fd);
    allocator.destroy(conn);
}

fn closeChannel(channel: *c.LIBSSH2_CHANNEL) void {
    _ = c.libssh2_channel_close(channel);
    _ = c.libssh2_channel_free(channel);
}

fn waitForIo(self: *Self) bool {
    var poll_fds: [1 + max_rules + max_connections]c.struct_pollfd = undefined;
    var count: usize = 0;
    poll_fds[count] = .{ .fd = self.ssh_fd, .events = self.sessionEvents(), .revents = 0 };
    count += 1;
    for (self.rules.items) |rule| {
        if (rule.kind != .local) continue;
        poll_fds[count] = .{ .fd = rule.listen_fd, .events = c.POLLIN, .revents = 0 };
        count += 1;
    }
    for (self.connections.items) |conn| {
        if (conn.channel == null or conn.dead) continue;
        var events: c_short = 0;
        if (!conn.local_eof and conn.to_remote.isEmpty()) events |= c.POLLIN;
        if (!conn.to_local.isEmpty()) events |= c.POLLOUT;
        poll_fds[count] = .{ .fd = conn.fd, .events = events, .revents = 0 };
        count += 1;
    }
    const rc = c.poll(&poll_fds, @intCast(count), idle_poll_timeout_ms);
    if (rc < 0) return c.__errno().* == c.EINTR;
    const hangup = c.POLLERR | c.POLLHUP | c.POLLNVAL;
    return (poll_fds[0].revents & hangup) == 0;
}

fn connectTarget(host: [:0]const u8, port: u16) ?c_int {
    var hints: c.struct_addrinfo = std.mem.zeroes(c.struct_addrinfo);
    hints.ai_family = c.AF_UNSPEC;
    hints.ai_socktype = c.SOCK_STREAM;
    var service_buf: [8]u8 = undefined;
    const service = std.fmt.bufPrintSentinel(&service_buf, "{}", .{port}, 0) catch return null;
    var addr_list: ?*c.struct_addrinfo = null;
    if (c.getaddrinfo(host.ptr, service.ptr, &hints, &addr_list) != 0) return null;
    defer c.freeaddrinfo(addr_list);
    var cur = addr_list;
    while (cur) |info| : (cur = info.ai_next) {
        const fd = c.socket(info.ai_family, info.ai_socktype, info.ai_protocol);
        if (fd < 0) continue;
        if (c.connect(fd, info.ai_addr, info.ai_addrlen) == 0) return fd;
        _ = c.close(fd);
    }
    return null;
}

fn setNonBlocking(fd: c_int) void {
    const flags = c.fcntl(fd, c.F_GETFL, @as(c_int, 0));
    if (flags < 0) return;
    _ = c.fcntl(fd, c.F_SETFL, flags | c.O_NONBLOCK);
}

fn wouldBlock() bool {
    const err = c.__errno().*;
    return err == c.EAGAIN or err == c.EWOULDBLOCK or err == c.EINTR;
}
