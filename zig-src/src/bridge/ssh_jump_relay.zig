const Self = @This();

const std = @import("std");

const c = @import("c");

const allocator = std.heap.c_allocator;
const idle_poll_timeout_ms = 250;
const buffer_size = 32 * 1024;

const Buffer = struct {
    bytes: [buffer_size]u8 = undefined,
    start: usize = 0,
    end: usize = 0,

    fn isEmpty(self: *const Buffer) bool {
        return self.start == self.end;
    }

    fn pending(self: *Buffer) []u8 {
        return self.bytes[self.start..self.end];
    }

    fn consume(self: *Buffer, len: usize) void {
        std.debug.assert(len <= self.end - self.start);
        self.start += len;
        if (self.start == self.end) {
            self.start = 0;
            self.end = 0;
        }
    }

    fn fill(self: *Buffer, len: usize) void {
        std.debug.assert(self.isEmpty());
        std.debug.assert(len <= self.bytes.len);
        self.start = 0;
        self.end = len;
    }
};

const Step = enum { idle, progressed, closed };

jump_session: *c.LIBSSH2_SESSION,
jump_fd: c_int,
channel: *c.LIBSSH2_CHANNEL,
relay_fd: c_int,
stop: std.atomic.Value(bool) = .init(false),
thread: ?std.Thread = null,
to_local: Buffer = .{},
to_remote: Buffer = .{},

pub fn start(
    jump_session: *c.LIBSSH2_SESSION,
    jump_fd: c_int,
    channel: *c.LIBSSH2_CHANNEL,
    relay_fd: c_int,
) !*Self {
    std.debug.assert(jump_fd >= 0);
    std.debug.assert(relay_fd >= 0);
    const relay = try allocator.create(Self);
    errdefer allocator.destroy(relay);
    relay.* = .{
        .jump_session = jump_session,
        .jump_fd = jump_fd,
        .channel = channel,
        .relay_fd = relay_fd,
    };
    relay.thread = try std.Thread.spawn(.{ .stack_size = 256 * 1024 }, run, .{relay});
    return relay;
}

pub fn deinit(self: *Self) void {
    self.stop.store(true, .release);
    if (self.thread) |thread| thread.join();
    _ = c.libssh2_channel_close(self.channel);
    _ = c.libssh2_channel_free(self.channel);
    _ = c.close(self.relay_fd);
    allocator.destroy(self);
}

fn run(self: *Self) void {
    while (!self.stop.load(.acquire)) {
        const step = self.pumpOnce();
        switch (step) {
            .closed => break,
            .progressed => continue,
            .idle => if (!self.waitForIo()) break,
        }
    }
    _ = c.shutdown(self.relay_fd, c.SHUT_RDWR);
}

fn pumpOnce(self: *Self) Step {
    var progressed = false;
    const steps = [_]*const fn (*Self) Step{ readChannel, writeLocal, readLocal, writeChannel };
    for (steps) |step_fn| {
        switch (step_fn(self)) {
            .closed => return .closed,
            .progressed => progressed = true,
            .idle => {},
        }
    }
    return if (progressed) .progressed else .idle;
}

fn readChannel(self: *Self) Step {
    if (!self.to_local.isEmpty()) return .idle;
    const rc = c.libssh2_channel_read_ex(self.channel, 0, &self.to_local.bytes, self.to_local.bytes.len);
    if (rc > 0) {
        self.to_local.fill(@intCast(rc));
        return .progressed;
    }
    if (rc == c.LIBSSH2_ERROR_EAGAIN) return .idle;
    if (rc == 0 and c.libssh2_channel_eof(self.channel) == 0) return .idle;
    return .closed;
}

fn writeLocal(self: *Self) Step {
    if (self.to_local.isEmpty()) return .idle;
    const pending = self.to_local.pending();
    const rc = c.write(self.relay_fd, pending.ptr, pending.len);
    if (rc > 0) {
        self.to_local.consume(@intCast(rc));
        return .progressed;
    }
    return if (rc < 0 and wouldBlock()) .idle else .closed;
}

fn readLocal(self: *Self) Step {
    if (!self.to_remote.isEmpty()) return .idle;
    const rc = c.read(self.relay_fd, &self.to_remote.bytes, self.to_remote.bytes.len);
    if (rc > 0) {
        self.to_remote.fill(@intCast(rc));
        return .progressed;
    }
    return if (rc < 0 and wouldBlock()) .idle else .closed;
}

fn writeChannel(self: *Self) Step {
    if (self.to_remote.isEmpty()) return .idle;
    const pending = self.to_remote.pending();
    const rc = c.libssh2_channel_write_ex(self.channel, 0, pending.ptr, pending.len);
    if (rc > 0) {
        self.to_remote.consume(@intCast(rc));
        return .progressed;
    }
    if (rc == 0 or rc == c.LIBSSH2_ERROR_EAGAIN) return .idle;
    return .closed;
}

fn waitForIo(self: *Self) bool {
    var poll_fds: [2]c.struct_pollfd = .{
        .{ .fd = self.jump_fd, .events = self.jumpEvents(), .revents = 0 },
        .{ .fd = self.relay_fd, .events = self.localEvents(), .revents = 0 },
    };
    const rc = c.poll(&poll_fds, poll_fds.len, idle_poll_timeout_ms);
    if (rc < 0) return c.__errno().* == c.EINTR;
    const hangup = c.POLLERR | c.POLLNVAL;
    return (poll_fds[0].revents & hangup) == 0 and (poll_fds[1].revents & hangup) == 0;
}

fn jumpEvents(self: *Self) c_short {
    const directions = c.libssh2_session_block_directions(self.jump_session);
    var events: c_short = 0;
    if (self.to_local.isEmpty() or (directions & c.LIBSSH2_SESSION_BLOCK_INBOUND) != 0) {
        events |= c.POLLIN;
    }
    if ((directions & c.LIBSSH2_SESSION_BLOCK_OUTBOUND) != 0) events |= c.POLLOUT;
    return events;
}

fn localEvents(self: *Self) c_short {
    var events: c_short = 0;
    if (self.to_remote.isEmpty()) events |= c.POLLIN;
    if (!self.to_local.isEmpty()) events |= c.POLLOUT;
    return events;
}

fn wouldBlock() bool {
    const err = c.__errno().*;
    return err == c.EAGAIN or err == c.EWOULDBLOCK or err == c.EINTR;
}
