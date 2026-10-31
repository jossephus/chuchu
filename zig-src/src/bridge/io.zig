const std = @import("std");
const c = @import("c");

var threaded: std.Io.Threaded = undefined;

export fn JNI_OnLoad(vm: ?*c.JavaVM, reserved: ?*anyopaque) callconv(.c) c.jint {
    _ = vm;
    _ = reserved;
    threaded = .init(std.heap.c_allocator, .{});
    return c.JNI_VERSION_1_6;
}

pub fn get() std.Io {
    return threaded.io();
}

pub fn nowMs(io: std.Io) i64 {
    return std.Io.Timestamp.now(io, .boot).toMilliseconds();
}

pub fn nowNs(io: std.Io) i128 {
    return std.Io.Timestamp.now(io, .awake).toNanoseconds();
}

pub fn randomInt(io: std.Io, comptime T: type) T {
    var bytes: [@sizeOf(T)]u8 = undefined;
    io.random(&bytes);
    return std.mem.readInt(T, &bytes, .little);
}
