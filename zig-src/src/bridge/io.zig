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

pub fn nowMs() i64 {
    return std.Io.Timestamp.now(get(), .boot).toMilliseconds();
}

pub fn nowNs() i128 {
    return std.Io.Timestamp.now(get(), .awake).toNanoseconds();
}

pub fn randomBytes(buffer: []u8) void {
    get().random(buffer);
}

pub fn randomInt(comptime T: type) T {
    var bytes: [@sizeOf(T)]u8 = undefined;
    randomBytes(&bytes);
    return std.mem.readInt(T, &bytes, .little);
}
