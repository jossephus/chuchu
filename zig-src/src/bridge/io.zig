const std = @import("std");

pub fn get() std.Io {
    return std.Io.Threaded.global_single_threaded.io();
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
