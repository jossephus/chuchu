//! Zig wrapper around zigimg PNG decoding.
const std = @import("std");
const zigimg = @import("zigimg");
const c = @cImport({
    @cInclude("android/log.h");
});

comptime {
    _ = @import("chuchu_snapshot.zig");
    _ = @import("chuchu_ssh.zig");
}

const c_allocator = std.heap.c_allocator;
const LOG_TAG = "ChuKittyNative";

fn logLine(prio: c_int, message: []const u8) void {
    _ = c.__android_log_print(prio, LOG_TAG, "%.*s", @as(c_int, @intCast(message.len)), message.ptr);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    logLine(c.ANDROID_LOG_INFO, line);
}

fn logWarn(comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    logLine(c.ANDROID_LOG_WARN, line);
}

/// Decode a PNG buffer into RGBA pixels.
/// Returns null on failure. Caller must free with `freePixels`.
pub fn decodePng(
    data: [*]const u8,
    len: usize,
    out_w: *u32,
    out_h: *u32,
) ?[*]u8 {
    var img = zigimg.Image.fromMemory(c_allocator, data[0..len]) catch {
        logWarn("zigimg decode failed len={}", .{len});
        return null;
    };
    defer img.deinit(c_allocator);

    img.convert(c_allocator, .rgba32) catch {
        logWarn("zigimg rgba32 convert failed len={}", .{len});
        return null;
    };

    if (img.width > std.math.maxInt(u32) or img.height > std.math.maxInt(u32)) {
        logWarn("zigimg image too large width={} height={}", .{ img.width, img.height });
        return null;
    }
    logInfo("zigimg decode ok cols={} rows={}", .{ img.width, img.height });
    out_w.* = @intCast(img.width);
    out_h.* = @intCast(img.height);

    const pixels = c_allocator.alloc(u8, img.rawBytes().len) catch {
        logWarn("zigimg alloc failed bytes={}", .{img.rawBytes().len});
        return null;
    };
    @memcpy(pixels, img.rawBytes());
    return pixels.ptr;
}

/// Free pixel data previously returned by `decodePng`.
pub fn freePixels(ptr: ?[*]u8, w: u32, h: u32) void {
    if (ptr) |p| {
        const total_bytes = @as(usize, w) * @as(usize, h) * 4;
        c_allocator.free(p[0..total_bytes]);
    }
}
