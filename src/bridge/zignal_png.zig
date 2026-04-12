//! Zig wrapper around zignal PNG decoding.
const std = @import("std");
const zignal = @import("zignal");
const c = @cImport({
    @cInclude("android/log.h");
});

comptime {
    _ = @import("chuchu_snapshot.zig");
    _ = @import("chuchu_ssh.zig");
}

const c_allocator = std.heap.c_allocator;
const Rgba = zignal.Rgba;
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
    const ImageRgba = zignal.Image(Rgba);
    var name_buf: [64]u8 = undefined;
    const suffix = std.crypto.random.int(u64);
    const temp_name = std.fmt.bufPrint(&name_buf, "chuchu_zignal_{x}.png", .{suffix}) catch return null;

    const cwd = std.fs.cwd();
    const file = cwd.createFile(temp_name, .{}) catch {
        logWarn("zignal create temp failed len={}", .{len});
        return null;
    };
    defer file.close();
    defer cwd.deleteFile(temp_name) catch {};
    file.writeAll(data[0..len]) catch {
        logWarn("zignal temp write failed len={}", .{len});
        return null;
    };

    const img = ImageRgba.load(c_allocator, temp_name) catch {
        logWarn("zignal load failed path={s} len={}", .{ temp_name, len });
        return null;
    };

    if (img.cols > std.math.maxInt(u32) or img.rows > std.math.maxInt(u32)) {
        logWarn("zignal image too large cols={} rows={}", .{ img.cols, img.rows });
        return null;
    }
    logInfo("zignal load ok path={s} cols={} rows={}", .{ temp_name, img.cols, img.rows });
    out_w.* = @intCast(img.cols);
    out_h.* = @intCast(img.rows);

    const bytes = std.mem.sliceAsBytes(img.data);
    return bytes.ptr;
}

/// Free pixel data previously returned by `decodePng`.
pub fn freePixels(ptr: ?[*]u8, w: u32, h: u32) void {
    if (ptr) |p| {
        const total = @as(usize, w) * @as(usize, h);
        const typed: [*]Rgba = @ptrCast(@alignCast(p));
        c_allocator.free(typed[0..total]);
    }
}
