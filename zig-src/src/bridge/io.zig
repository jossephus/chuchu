//! Process-wide `std.Io` for the JNI bridge.
//!
//! Zig 0.17 routes clocks and randomness through `std.Io`. JNI entry points
//! have no caller that could hand us an `Io`, so the bridge acts as the
//! application and owns one instance for the whole process.

const std = @import("std");

/// The global single-threaded instance is enough here: the bridge only uses
/// clocks and randomness, and `random` serializes seeding behind a mutex, so
/// it is safe to call from every JNI thread.
pub fn get() std.Io {
    return std.Io.Threaded.global_single_threaded.io();
}

/// Monotonic milliseconds for deadlines and idle timers. Unlike the wall
/// clock, this cannot jump when NTP or the user adjusts the system time.
/// `.boot` keeps counting while the device is suspended, so a timeout that
/// spans a phone sleep still expires on wake, as it did with the wall clock.
pub fn nowMs() i64 {
    return std.Io.Timestamp.now(get(), .boot).toMilliseconds();
}

/// Monotonic nanoseconds for performance measurements.
pub fn nowNs() i128 {
    return std.Io.Timestamp.now(get(), .awake).toNanoseconds();
}

/// Fills `buffer` from the CSPRNG; the replacement for `std.crypto.random`.
pub fn randomBytes(buffer: []u8) void {
    get().random(buffer);
}

/// Returns a uniformly random integer from the CSPRNG.
pub fn randomInt(comptime T: type) T {
    var bytes: [@sizeOf(T)]u8 = undefined;
    randomBytes(&bytes);
    return std.mem.readInt(T, &bytes, .little);
}
