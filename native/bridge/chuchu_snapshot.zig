const std = @import("std");
const ghostty = @import("ghostty-vt");
const zignal_png = @import("zignal_png.zig");

const c = @cImport({
    @cInclude("jni.h");
    @cInclude("android/log.h");
    @cInclude("chuchu_jni_internal.h");
});

const allocator = std.heap.c_allocator;
const LOG_TAG = "ChuKittyNative";
const verbose_kitty_logs = false;
const SNAPSHOT_HEADER_I32_COUNT = 11;
const SNAPSHOT_CELL_SIZE_BYTES = 11;
const IMAGE_HEADER_BYTES = 44;
const MAX_KITTY_IMAGES = 64;
const DeviceAttributes = blk: {
    const device_attributes_opt = @FieldType(ghostty.TerminalStream.Handler.Effects, "device_attributes");
    const device_attributes_fn = @typeInfo(device_attributes_opt).optional.child;
    break :blk @typeInfo(@typeInfo(device_attributes_fn).pointer.child).@"fn".return_type.?;
};

const ImageFreeMode = enum(c_int) {
    none = 0,
    malloc_buf = 1,
    zignal = 2,
};

const PlacementInfo = struct {
    dest_x: i32,
    dest_y: i32,
    dest_w: u32,
    dest_h: u32,
    src_x: u32,
    src_y: u32,
    src_w: u32,
    src_h: u32,
    img_w: u32,
    img_h: u32,
    data_ptr: [*c]const u8,
    data_len: usize,
    free_mode: ImageFreeMode,
};

const PreparedImageData = struct {
    data_ptr: [*c]const u8,
    data_len: usize,
    img_w: u32,
    img_h: u32,
    free_mode: ImageFreeMode,
};

const MouseEncodingSize = struct {
    screen_width: u32 = 1,
    screen_height: u32 = 1,
    cell_width: u32 = 1,
    cell_height: u32 = 1,
    padding_top: u32 = 0,
    padding_bottom: u32 = 0,
    padding_left: u32 = 0,
    padding_right: u32 = 0,
};

const ChuchuTerminal = struct {
    terminal: ghostty.Terminal,
    render_state: ghostty.RenderState = .empty,
    stream: ghostty.TerminalStream,
    cols: u16,
    rows: u16,
    cell_width: u32,
    cell_height: u32,
    mouse_size: MouseEncodingSize = .{},
    mouse_size_set: bool = false,
    last_mouse_cell: ?ghostty.point.Coordinate = null,
    color_scheme: ghostty.device_status.ColorScheme = .dark,
    color_scheme_set: bool = false,
    title: ?[]u8 = null,
    title_len: usize = 0,
    title_dirty: bool = false,
    pwd: ?[]u8 = null,
    pwd_len: usize = 0,
    pwd_dirty: bool = false,
    bell_count: u32 = 0,
    snapshot_buffer: std.ArrayListUnmanaged(u8) = .empty,
    image_snapshot_buffer: std.ArrayListUnmanaged(u8) = .empty,
    pty_write_buffer: std.ArrayListUnmanaged(u8) = .empty,
    pty_write_len: usize = 0,
};

fn chuchuFromHandle(handle: c.jlong) ?*ChuchuTerminal {
    if (handle == 0) return null;
    return @ptrFromInt(@as(usize, @bitCast(handle)));
}

fn clampI32(value: c.jint, minimum: i32, fallback: i32) i32 {
    if (value < minimum) return fallback;
    return @intCast(value);
}

fn rgbFromBytes(bytes: [*c]const u8) ghostty.color.RGB {
    return .{ .r = bytes[0], .g = bytes[1], .b = bytes[2] };
}

fn terminalFromHandler(handler: *ghostty.TerminalStream.Handler) *ChuchuTerminal {
    return @fieldParentPtr("terminal", handler.terminal);
}

fn logLine(prio: c_int, message: []const u8) void {
    _ = c.__android_log_print(prio, LOG_TAG, "%.*s", @as(c_int, @intCast(message.len)), message.ptr);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    if (!verbose_kitty_logs) return;
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    logLine(c.ANDROID_LOG_INFO, line);
}

fn logWarn(comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    logLine(c.ANDROID_LOG_WARN, line);
}

fn logKittyState(stage: []const u8, terminal: *ChuchuTerminal) void {
    const storage = &terminal.terminal.screens.active.kitty_images;
    const viewport_top = terminal.terminal.screens.active.pages.getTopLeft(.viewport);
    logInfo(
        "kitty_state stage={s} images={} placements={} loading={} total_bytes={} viewport=({}, {}) term={}x{} cell={}x{}",
        .{
            stage,
            storage.images.count(),
            storage.placements.count(),
            storage.loading != null,
            storage.total_bytes,
            viewport_top.x,
            viewport_top.y,
            terminal.cols,
            terminal.rows,
            terminal.cell_width,
            terminal.cell_height,
        },
    );
}

fn ensureListSize(list: *std.ArrayListUnmanaged(u8), size_needed: usize) ?[*]u8 {
    list.ensureTotalCapacityPrecise(allocator, size_needed) catch return null;
    list.items.len = size_needed;
    return list.items.ptr;
}

fn updateString(storage: *?[]u8, storage_len: *usize, dirty: *bool, value: ?[:0]const u8) bool {
    const slice: []const u8 = if (value) |v| v else "";

    if (storage.* != null and storage_len.* == slice.len) {
        if (slice.len == 0) return false;
        if (std.mem.eql(u8, storage.*.?[0..slice.len], slice)) return false;
    }

    const new_buf = allocator.alloc(u8, slice.len + 1) catch return false;
    if (slice.len > 0) @memcpy(new_buf[0..slice.len], slice);
    new_buf[slice.len] = 0;

    if (storage.*) |old| allocator.free(old);
    storage.* = new_buf;
    storage_len.* = slice.len;
    dirty.* = true;
    return true;
}

fn updateMetadata(terminal: *ChuchuTerminal) void {
    _ = updateString(&terminal.title, &terminal.title_len, &terminal.title_dirty, terminal.terminal.getTitle());
    _ = updateString(&terminal.pwd, &terminal.pwd_len, &terminal.pwd_dirty, terminal.terminal.getPwd());
}

fn chuchuWritePty(handler: *ghostty.TerminalStream.Handler, data: [:0]const u8) void {
    const terminal = terminalFromHandler(handler);
    appendPtyWrite(terminal, data);
}

fn chuchuBell(handler: *ghostty.TerminalStream.Handler) void {
    const terminal = terminalFromHandler(handler);
    if (terminal.bell_count < std.math.maxInt(u32)) terminal.bell_count += 1;
}

fn chuchuTitleChanged(handler: *ghostty.TerminalStream.Handler) void {
    updateMetadata(terminalFromHandler(handler));
}

fn chuchuColorScheme(handler: *ghostty.TerminalStream.Handler) ?ghostty.device_status.ColorScheme {
    const terminal = terminalFromHandler(handler);
    if (!terminal.color_scheme_set) return null;
    return terminal.color_scheme;
}

fn chuchuDeviceAttributes(_: *ghostty.TerminalStream.Handler) DeviceAttributes {
    // Use Ghostty's parsed DA handling so replies still work when escape
    // sequences are split across SSH read chunks.
    return .{};
}

fn chuchuSize(handler: *ghostty.TerminalStream.Handler) ?ghostty.size_report.Size {
    const terminal = terminalFromHandler(handler);
    return .{
        .rows = terminal.rows,
        .columns = terminal.cols,
        .cell_width = if (terminal.cell_width > 0) terminal.cell_width else 1,
        .cell_height = if (terminal.cell_height > 0) terminal.cell_height else 1,
    };
}

fn chuchuXtversion(_: *ghostty.TerminalStream.Handler) []const u8 {
    return "ghostty 1";
}

fn appendPtyWrite(terminal: *ChuchuTerminal, bytes: []const u8) void {
    if (bytes.len == 0) return;
    if (std.math.maxInt(usize) - terminal.pty_write_len < bytes.len) return;
    const size_needed = terminal.pty_write_len + bytes.len;
    _ = ensureListSize(&terminal.pty_write_buffer, size_needed) orelse return;
    @memcpy(terminal.pty_write_buffer.items[terminal.pty_write_len .. terminal.pty_write_len + bytes.len], bytes);
    terminal.pty_write_len += bytes.len;
}

fn chuchuDecodePng(alloc: std.mem.Allocator, data: []const u8) ghostty.sys.DecodeError!ghostty.sys.Image {
    var w: u32 = 0;
    var h: u32 = 0;
    const pixels = zignal_png.decodePng(data.ptr, data.len, &w, &h) orelse return error.InvalidData;
    defer zignal_png.freePixels(pixels, w, h);
    logInfo("decode_png ok bytes={} width={} height={}", .{ data.len, w, h });

    const pixel_len = @as(usize, w) * @as(usize, h) * 4;
    const out = try alloc.alloc(u8, pixel_len);
    @memcpy(out, pixels[0..pixel_len]);
    return .{ .width = w, .height = h, .data = out };
}

export fn update_render_state(terminal: ?*ChuchuTerminal) callconv(.c) void {
    if (terminal == null) return;
    terminal.?.render_state.update(allocator, &terminal.?.terminal) catch return;
    updateMetadata(terminal.?);
}

export fn chuchu_create_terminal(cols: c.jint, rows: c.jint, max_scrollback: c.jint) callconv(.c) c.jlong {
    ghostty.sys.decode_png = chuchuDecodePng;

    const terminal = allocator.create(ChuchuTerminal) catch return 0;
    errdefer allocator.destroy(terminal);

    const init_cols: u16 = @intCast(clampI32(cols, 1, 80));
    const init_rows: u16 = @intCast(clampI32(rows, 1, 24));
    var inner = ghostty.Terminal.init(allocator, .{
        .cols = init_cols,
        .rows = init_rows,
        .max_scrollback = @intCast(clampI32(max_scrollback, 0, 10000)),
        .kitty_image_storage_limit = 64 * 1024 * 1024,
        .kitty_image_loading_limits = .{
            .file = false,
            .temporary_file = true,
            .shared_memory = true,
        },
    }) catch return 0;
    errdefer inner.deinit(allocator);

    terminal.* = .{
        .terminal = inner,
        .stream = undefined,
        .cols = init_cols,
        .rows = init_rows,
        .cell_width = 1,
        .cell_height = 1,
    };

    var handler = terminal.terminal.vtHandler();
    handler.effects = .{
        .write_pty = chuchuWritePty,
        .bell = chuchuBell,
        .color_scheme = chuchuColorScheme,
        .device_attributes = chuchuDeviceAttributes,
        .enquiry = null,
        .size = chuchuSize,
        .title_changed = chuchuTitleChanged,
        .xtversion = chuchuXtversion,
    };
    terminal.stream = ghostty.TerminalStream.initAlloc(allocator, handler);

    update_render_state(terminal);
    return @bitCast(@intFromPtr(terminal));
}

export fn chuchu_destroy_terminal(handle: c.jlong) callconv(.c) void {
    const terminal = chuchuFromHandle(handle) orelse return;
    terminal.snapshot_buffer.deinit(allocator);
    terminal.image_snapshot_buffer.deinit(allocator);
    terminal.pty_write_buffer.deinit(allocator);
    if (terminal.title) |buf| allocator.free(buf);
    if (terminal.pwd) |buf| allocator.free(buf);
    terminal.render_state.deinit(allocator);
    terminal.stream.deinit();
    terminal.terminal.deinit(allocator);
    allocator.destroy(terminal);
}

fn jniNewByteArray(env: *c.JNIEnv, len: c.jsize) c.jbyteArray {
    return env.*.*.NewByteArray.?(env, len);
}

fn jniEmptyByteArray(env: *c.JNIEnv) c.jbyteArray {
    return jniNewByteArray(env, 0);
}

fn jniNewDirectByteBuffer(env: *c.JNIEnv, ptr: ?*anyopaque, len: c.jlong) c.jobject {
    return env.*.*.NewDirectByteBuffer.?(env, ptr, len);
}

fn jniNewStringUTF(env: *c.JNIEnv, s: [*:0]const u8) c.jstring {
    return env.*.*.NewStringUTF.?(env, s);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeVersion(env: *c.JNIEnv, clazz: c.jclass) callconv(.c) c.jstring {
    _ = clazz;
    return jniNewStringUTF(env, "1");
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeCreate(env: *c.JNIEnv, thiz: c.jobject, cols: c.jint, rows: c.jint, max_scrollback: c.jint) callconv(.c) c.jlong {
    _ = env;
    _ = thiz;
    return chuchu_create_terminal(cols, rows, max_scrollback);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeDestroy(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) void {
    _ = env;
    _ = thiz;
    chuchu_destroy_terminal(handle);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeWriteRemote(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, data: c.jbyteArray) callconv(.c) void {
    _ = thiz;
    if (data == null) return;
    const len = env.*.*.GetArrayLength.?(env, data);
    if (len <= 0) return;
    var is_copy: c.jboolean = c.JNI_FALSE;
    const bytes = env.*.*.GetByteArrayElements.?(env, data, &is_copy);
    if (bytes == null) return;
    chuchu_write_remote(handle, @ptrCast(bytes), @intCast(len));
    env.*.*.ReleaseByteArrayElements.?(env, data, bytes, c.JNI_ABORT);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeResize(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, cols: c.jint, rows: c.jint, cell_width: c.jint, cell_height: c.jint) callconv(.c) void {
    _ = env;
    _ = thiz;
    chuchu_resize(handle, cols, rows, cell_width, cell_height);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeScroll(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, delta: c.jint) callconv(.c) void {
    _ = env;
    _ = thiz;
    chuchu_scroll(handle, delta);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeSnapshot(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jobject {
    _ = thiz;
    var size: usize = 0;
    const buffer = chuchu_build_text_snapshot(handle, &size);
    if (buffer == null or size == 0) return jniNewDirectByteBuffer(env, null, 0);
    return jniNewDirectByteBuffer(env, @ptrCast(buffer), @intCast(size));
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeSnapshotImages(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jobject {
    _ = thiz;
    var size: usize = 0;
    const buffer = chuchu_build_image_snapshot(handle, &size);
    if (buffer == null or size == 0) return jniNewDirectByteBuffer(env, null, 0);
    return jniNewDirectByteBuffer(env, @ptrCast(buffer), @intCast(size));
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeIsImageLoading(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    _ = env;
    _ = thiz;
    const terminal = chuchuFromHandle(handle) orelse return c.JNI_FALSE;
    return if (terminal.terminal.screens.active.kitty_images.loading != null) c.JNI_TRUE else c.JNI_FALSE;
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativePollTitle(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jstring {
    _ = thiz;
    const value = chuchu_poll_title_ptr(handle) orelse return null;
    return jniNewStringUTF(env, value);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativePollPwd(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jstring {
    _ = thiz;
    const value = chuchu_poll_pwd_ptr(handle) orelse return null;
    return jniNewStringUTF(env, value);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeDrainBellCount(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    return chuchu_drain_bell_count(handle);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeSetColorScheme(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, scheme: c.jint) callconv(.c) void {
    _ = env;
    _ = thiz;
    chuchu_set_color_scheme(handle, scheme);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeSetMouseEncodingSize(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, screen_width: c.jint, screen_height: c.jint, cell_width: c.jint, cell_height: c.jint, padding_top: c.jint, padding_bottom: c.jint, padding_left: c.jint, padding_right: c.jint) callconv(.c) void {
    _ = env;
    _ = thiz;
    chuchu_set_mouse_encoding_size(handle, screen_width, screen_height, cell_width, cell_height, padding_top, padding_bottom, padding_left, padding_right);
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeEncodeKey(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, key: c.jint, codepoint: c.jint, mods: c.jint, action: c.jint) callconv(.c) c.jbyteArray {
    _ = thiz;
    const terminal = chuchuFromHandle(handle) orelse return jniEmptyByteArray(env);
    var buf: [128]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&buf);
    const event: ghostty.input.KeyEvent = .{
        .action = @enumFromInt(action),
        .key = @enumFromInt(key),
        .mods = @bitCast(@as(ghostty.input.KeyMods.Backing, @intCast(mods))),
        .unshifted_codepoint = if (codepoint > 0) @intCast(codepoint) else 0,
        .utf8 = "",
    };
    ghostty.input.encodeKey(&writer, event, ghostty.input.KeyEncodeOptions.fromTerminal(&terminal.terminal)) catch return jniEmptyByteArray(env);
    return jniByteArrayFromBytes(env, writer.buffered());
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeEncodeMouse(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, action: c.jint, button: c.jint, mods: c.jint, x: c.jfloat, y: c.jfloat, any_button_pressed: c.jboolean, track_last_cell: c.jboolean) callconv(.c) c.jbyteArray {
    _ = thiz;
    const terminal = chuchuFromHandle(handle) orelse return jniEmptyByteArray(env);
    if (!terminal.mouse_size_set) return jniEmptyByteArray(env);
    var buf: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&buf);
    var last_cell = terminal.last_mouse_cell;
    const event: ghostty.input.MouseEncodeEvent = .{
        .action = @enumFromInt(action),
        .button = if (button <= 0) null else @enumFromInt(button),
        .mods = @bitCast(@as(ghostty.input.KeyMods.Backing, @intCast(mods))),
        .pos = .{ .x = x, .y = y },
    };
    const opts = ghosttyMouseEncodeOptions(terminal, any_button_pressed == c.JNI_TRUE, track_last_cell == c.JNI_TRUE, &last_cell);
    ghostty.input.encodeMouse(&writer, event, opts) catch return jniEmptyByteArray(env);
    if (track_last_cell == c.JNI_TRUE) terminal.last_mouse_cell = last_cell;
    return jniByteArrayFromBytes(env, writer.buffered());
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeEncodeFocus(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, focused: c.jboolean) callconv(.c) c.jbyteArray {
    _ = thiz;
    const terminal = chuchuFromHandle(handle) orelse return jniEmptyByteArray(env);
    if (!terminal.terminal.modes.get(.focus_event)) return jniEmptyByteArray(env);
    var buf: [ghostty.input.max_focus_encode_size]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&buf);
    ghostty.input.encodeFocus(&writer, if (focused == c.JNI_TRUE) .gained else .lost) catch return jniEmptyByteArray(env);
    return jniByteArrayFromBytes(env, writer.buffered());
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeDrainPtyWrites(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong) callconv(.c) c.jbyteArray {
    _ = thiz;
    const terminal = chuchuFromHandle(handle) orelse return jniEmptyByteArray(env);
    if (terminal.pty_write_len == 0) return jniEmptyByteArray(env);
    const out = jniByteArrayFromBytes(env, terminal.pty_write_buffer.items[0..terminal.pty_write_len]);
    terminal.pty_write_len = 0;
    return out;
}

export fn Java_com_example_chuchu_service_terminal_GhosttyBridge_nativeSetDefaultColors(env: *c.JNIEnv, thiz: c.jobject, handle: c.jlong, fg_rgb: c.jintArray, bg_rgb: c.jintArray, cursor_rgb: c.jintArray, palette_rgb: c.jbyteArray) callconv(.c) void {
    _ = thiz;
    var fg_vals: [3]u8 = .{ 0, 0, 0 };
    var bg_vals: [3]u8 = .{ 0, 0, 0 };
    var cursor_vals: [3]u8 = .{ 0, 0, 0 };
    var palette_vals: [256 * 3]u8 = [_]u8{0} ** (256 * 3);
    var has_fg = false;
    var has_bg = false;
    var has_cursor = false;
    var has_palette = false;

    if (fg_rgb != null and env.*.*.GetArrayLength.?(env, fg_rgb) >= 3) {
        var is_copy: c.jboolean = c.JNI_FALSE;
        const values = env.*.*.GetIntArrayElements.?(env, fg_rgb, &is_copy);
        if (values != null) {
            fg_vals = .{ @intCast(@max(0, @min(255, values[0]))), @intCast(@max(0, @min(255, values[1]))), @intCast(@max(0, @min(255, values[2]))) };
            has_fg = true;
            env.*.*.ReleaseIntArrayElements.?(env, fg_rgb, values, c.JNI_ABORT);
        }
    }
    if (bg_rgb != null and env.*.*.GetArrayLength.?(env, bg_rgb) >= 3) {
        var is_copy: c.jboolean = c.JNI_FALSE;
        const values = env.*.*.GetIntArrayElements.?(env, bg_rgb, &is_copy);
        if (values != null) {
            bg_vals = .{ @intCast(@max(0, @min(255, values[0]))), @intCast(@max(0, @min(255, values[1]))), @intCast(@max(0, @min(255, values[2]))) };
            has_bg = true;
            env.*.*.ReleaseIntArrayElements.?(env, bg_rgb, values, c.JNI_ABORT);
        }
    }
    if (cursor_rgb != null and env.*.*.GetArrayLength.?(env, cursor_rgb) >= 3) {
        var is_copy: c.jboolean = c.JNI_FALSE;
        const values = env.*.*.GetIntArrayElements.?(env, cursor_rgb, &is_copy);
        if (values != null) {
            cursor_vals = .{ @intCast(@max(0, @min(255, values[0]))), @intCast(@max(0, @min(255, values[1]))), @intCast(@max(0, @min(255, values[2]))) };
            has_cursor = true;
            env.*.*.ReleaseIntArrayElements.?(env, cursor_rgb, values, c.JNI_ABORT);
        }
    }
    if (palette_rgb != null and env.*.*.GetArrayLength.?(env, palette_rgb) >= 256 * 3) {
        var is_copy: c.jboolean = c.JNI_FALSE;
        const bytes = env.*.*.GetByteArrayElements.?(env, palette_rgb, &is_copy);
        if (bytes != null) {
            @memcpy(palette_vals[0 .. 256 * 3], @as([*]u8, @ptrCast(bytes))[0 .. 256 * 3]);
            has_palette = true;
            env.*.*.ReleaseByteArrayElements.?(env, palette_rgb, bytes, c.JNI_ABORT);
        }
    }

    chuchu_apply_default_colors(handle, has_fg, &fg_vals[0], has_bg, &bg_vals[0], has_cursor, &cursor_vals[0], has_palette, &palette_vals[0]);
}

fn writeIntLe(comptime T: type, buffer: []u8, offset: usize, value: T) void {
    const le_value = std.mem.nativeToLittle(T, value);
    @memcpy(buffer[offset .. offset + @sizeOf(T)], std.mem.asBytes(&le_value));
}

fn jniByteArrayFromBytes(env: *c.JNIEnv, bytes: []const u8) c.jbyteArray {
    const out = jniNewByteArray(env, @intCast(bytes.len));
    if (out != null and bytes.len > 0) {
        env.*.*.SetByteArrayRegion.?(env, out, 0, @intCast(bytes.len), @ptrCast(bytes.ptr));
    }
    return out;
}

export fn shm_open(name: [*:0]const u8, oflag: c_int, mode: c_uint) callconv(.c) c_int {
    _ = name;
    _ = oflag;
    _ = mode;
    return -1;
}

export fn shm_unlink(name: [*:0]const u8) callconv(.c) c_int {
    _ = name;
    return -1;
}

fn resolvedStyle(render_cell: ghostty.RenderState.Cell) ghostty.Style {
    return if (render_cell.raw.style_id != 0) render_cell.style else .{};
}

fn resolvedCodepoint(render_cell: ghostty.RenderState.Cell) u32 {
    if (render_cell.raw.wide == .spacer_tail or render_cell.raw.wide == .spacer_head) return 32;
    if (render_cell.raw.content_tag == .codepoint_grapheme and render_cell.grapheme.len > 0) return render_cell.grapheme[0];
    const cp = render_cell.raw.codepoint();
    return if (cp == 0) 32 else cp;
}

export fn chuchu_build_text_snapshot(handle: c.jlong, out_size: [*c]usize) callconv(.c) ?[*]u8 {
    const terminal = chuchuFromHandle(handle) orelse return null;
    if (out_size == null) return null;

    update_render_state(terminal);

    const cols: usize = terminal.render_state.cols;
    const rows: usize = terminal.render_state.rows;
    const header_size = SNAPSHOT_HEADER_I32_COUNT * @sizeOf(i32);
    const total_size = header_size + (cols * rows * SNAPSHOT_CELL_SIZE_BYTES);
    const buffer = ensureListSize(&terminal.snapshot_buffer, total_size) orelse return null;

    writeIntLe(i32, buffer[0..total_size], 0, @intCast(cols));
    writeIntLe(i32, buffer[0..total_size], 4, @intCast(rows));
    writeIntLe(i32, buffer[0..total_size], 8, if (terminal.render_state.cursor.viewport) |cursor| cursor.x else -1);
    writeIntLe(i32, buffer[0..total_size], 12, if (terminal.render_state.cursor.viewport) |cursor| cursor.y else -1);
    writeIntLe(i32, buffer[0..total_size], 16, if (terminal.render_state.cursor.visible and terminal.render_state.cursor.viewport != null) 1 else 0);
    writeIntLe(i32, buffer[0..total_size], 20, terminal.render_state.colors.background.r);
    writeIntLe(i32, buffer[0..total_size], 24, terminal.render_state.colors.background.g);
    writeIntLe(i32, buffer[0..total_size], 28, terminal.render_state.colors.background.b);
    writeIntLe(i32, buffer[0..total_size], 32, terminal.render_state.colors.foreground.r);
    writeIntLe(i32, buffer[0..total_size], 36, terminal.render_state.colors.foreground.g);
    writeIntLe(i32, buffer[0..total_size], 40, terminal.render_state.colors.foreground.b);

    const row_slice = terminal.render_state.row_data.slice();
    const row_cells = row_slice.items(.cells);
    var cell_index: usize = 0;
    for (row_cells[0..rows]) |cells| {
        const cell_slice = cells.slice();
        const render_cells = cell_slice.items(.raw);
        const graphemes = cell_slice.items(.grapheme);
        const styles = cell_slice.items(.style);
        var x: usize = 0;
        while (x < cols) : (x += 1) {
            const base = header_size + cell_index * SNAPSHOT_CELL_SIZE_BYTES;
            cell_index += 1;

            const render_cell: ghostty.RenderState.Cell = .{
                .raw = render_cells[x],
                .grapheme = graphemes[x],
                .style = styles[x],
            };
            const style = resolvedStyle(render_cell);
            const fg = style.fg(.{
                .default = terminal.render_state.colors.foreground,
                .palette = &terminal.render_state.colors.palette,
            });
            const bg = style.bg(&render_cell.raw, &terminal.render_state.colors.palette) orelse terminal.render_state.colors.background;

            var flags: u8 = 0;
            if (style.flags.bold) flags |= 1 << 0;
            if (style.flags.italic) flags |= 1 << 1;
            if (style.flags.underline != .none) flags |= 1 << 2;
            if (style.flags.inverse) flags |= 1 << 3;
            if (style.flags.blink) flags |= 1 << 4;
            if (style.flags.faint) flags |= 1 << 5;

            writeIntLe(i32, buffer[0..total_size], base, @intCast(resolvedCodepoint(render_cell)));
            buffer[base + 4] = fg.r;
            buffer[base + 5] = fg.g;
            buffer[base + 6] = fg.b;
            buffer[base + 7] = bg.r;
            buffer[base + 8] = bg.g;
            buffer[base + 9] = bg.b;
            buffer[base + 10] = flags;
        }
    }

    out_size.* = total_size;
    return buffer;
}

fn placementViewportPos(
    terminal: *ChuchuTerminal,
    placement: ghostty.kitty.graphics.ImageStorage.Placement,
    image: ghostty.kitty.graphics.Image,
) ?struct { col: i32, row: i32 } {
    const pin = switch (placement.location) {
        .pin => |p| p,
        .virtual => return null,
    };

    const pages = &terminal.terminal.screens.active.pages;
    const pin_screen = pages.pointFromPin(.screen, pin.*) orelse return null;
    const viewport_top = pages.pointFromPin(.screen, pages.getTopLeft(.viewport)) orelse return null;
    const vp_row: i32 = @as(i32, @intCast(pin_screen.screen.y)) - @as(i32, @intCast(viewport_top.screen.y));
    const vp_col: i32 = @as(i32, @intCast(pin_screen.screen.x)) - @as(i32, @intCast(viewport_top.screen.x));
    const grid_size = placement.gridSize(image, &terminal.terminal);
    if (vp_row + @as(i32, @intCast(grid_size.rows)) <= 0 or vp_row >= @as(i32, @intCast(terminal.terminal.rows))) {
        return null;
    }
    return .{ .col = vp_col, .row = vp_row };
}

fn placementSourceRect(
    placement: ghostty.kitty.graphics.ImageStorage.Placement,
    image_w: u32,
    image_h: u32,
) struct { x: u32, y: u32, width: u32, height: u32 } {
    const x = @min(placement.source_x, image_w);
    const y = @min(placement.source_y, image_h);
    const w = @min(if (placement.source_width > 0) placement.source_width else image_w, image_w - x);
    const h = @min(if (placement.source_height > 0) placement.source_height else image_h, image_h - y);
    return .{ .x = x, .y = y, .width = w, .height = h };
}

fn allocRgba(pixel_count: usize) ?[]u8 {
    return allocator.alloc(u8, pixel_count * 4) catch null;
}

fn prepareImageData(image_id: u32, image: ghostty.kitty.graphics.Image) ?PreparedImageData {
    var data_ptr: [*c]const u8 = if (image.data.len == 0) null else image.data.ptr;
    var data_len: usize = image.data.len;
    var img_w = image.width;
    var img_h = image.height;
    var free_mode: ImageFreeMode = .none;
    var expected_len: usize = @as(usize, img_w) * @as(usize, img_h) * 4;
    const pixel_count = @as(usize, img_w) * @as(usize, img_h);

    switch (image.format) {
        .rgb => {
            const rgb_len = @as(usize, img_w) * @as(usize, img_h) * 3;
            if (data_ptr == null or data_len < rgb_len) {
                logWarn("kitty placement skipped image_id={} reason=short_rgb data_len={} need={}", .{ image_id, data_len, rgb_len });
                return null;
            }
            const rgba = allocRgba(pixel_count) orelse return null;
            var i: usize = 0;
            var j: usize = 0;
            while (i < rgb_len) : ({
                i += 3;
                j += 4;
            }) {
                rgba[j + 0] = data_ptr[i + 0];
                rgba[j + 1] = data_ptr[i + 1];
                rgba[j + 2] = data_ptr[i + 2];
                rgba[j + 3] = 0xff;
            }
            data_ptr = rgba.ptr;
            data_len = expected_len;
            free_mode = .malloc_buf;
        },
        .gray => {
            const gray_len = @as(usize, img_w) * @as(usize, img_h);
            if (data_ptr == null or data_len < gray_len) {
                logWarn("kitty placement skipped image_id={} reason=short_gray data_len={} need={}", .{ image_id, data_len, gray_len });
                return null;
            }
            const rgba = allocRgba(pixel_count) orelse return null;
            var i: usize = 0;
            var j: usize = 0;
            while (i < gray_len) : ({
                i += 1;
                j += 4;
            }) {
                const g = data_ptr[i];
                rgba[j + 0] = g;
                rgba[j + 1] = g;
                rgba[j + 2] = g;
                rgba[j + 3] = 0xff;
            }
            data_ptr = rgba.ptr;
            data_len = expected_len;
            free_mode = .malloc_buf;
        },
        .gray_alpha => {
            const ga_len = @as(usize, img_w) * @as(usize, img_h) * 2;
            if (data_ptr == null or data_len < ga_len) {
                logWarn("kitty placement skipped image_id={} reason=short_gray_alpha data_len={} need={}", .{ image_id, data_len, ga_len });
                return null;
            }
            const rgba = allocRgba(pixel_count) orelse return null;
            var i: usize = 0;
            var j: usize = 0;
            while (i < ga_len) : ({
                i += 2;
                j += 4;
            }) {
                const g = data_ptr[i];
                rgba[j + 0] = g;
                rgba[j + 1] = g;
                rgba[j + 2] = g;
                rgba[j + 3] = data_ptr[i + 1];
            }
            data_ptr = rgba.ptr;
            data_len = expected_len;
            free_mode = .malloc_buf;
        },
        .png => {
            if (data_ptr == null or data_len == 0) {
                logWarn("kitty placement skipped image_id={} reason=empty_png", .{image_id});
                return null;
            }
            var png_w: u32 = 0;
            var png_h: u32 = 0;
            const rgba = zignal_png.decodePng(data_ptr, data_len, &png_w, &png_h) orelse return null;
            if (png_w == 0 or png_h == 0) {
                zignal_png.freePixels(rgba, png_w, png_h);
                logWarn("kitty placement skipped image_id={} reason=png_decode_zero", .{image_id});
                return null;
            }
            img_w = png_w;
            img_h = png_h;
            expected_len = @as(usize, img_w) * @as(usize, img_h) * 4;
            data_ptr = rgba;
            data_len = expected_len;
            free_mode = .zignal;
        },
        .rgba => {
            if (data_ptr == null or data_len < expected_len) {
                logWarn("kitty placement skipped image_id={} reason=short_rgba data_len={} need={}", .{ image_id, data_len, expected_len });
                return null;
            }
        },
    }

    if (data_ptr == null or data_len < expected_len) {
        logWarn("kitty placement skipped image_id={} reason=short_final data_len={} need={}", .{ image_id, data_len, expected_len });
        return null;
    }

    return .{
        .data_ptr = data_ptr,
        .data_len = expected_len,
        .img_w = img_w,
        .img_h = img_h,
        .free_mode = free_mode,
    };
}

export fn chuchu_build_image_snapshot(handle: c.jlong, out_size: [*c]usize) callconv(.c) ?[*]u8 {
    const terminal = chuchuFromHandle(handle) orelse return null;
    if (out_size == null) return null;
    logKittyState("image_snapshot_begin", terminal);

    var placements: [MAX_KITTY_IMAGES]PlacementInfo = undefined;
    var count: usize = 0;
    var has_virtual = false;
    var it = terminal.terminal.screens.active.kitty_images.placements.iterator();
    while (it.next()) |entry| {
        if (count >= MAX_KITTY_IMAGES) break;

        const placement = entry.value_ptr.*;
        if (placement.location == .virtual) {
            has_virtual = true;
            continue;
        }

        const image = terminal.terminal.screens.active.kitty_images.imageById(entry.key_ptr.image_id) orelse continue;
        const viewport = placementViewportPos(terminal, placement, image) orelse {
            logWarn(
                "kitty placement skipped image_id={} reason=no_viewport location_virtual={}",
                .{ entry.key_ptr.image_id, placement.location == .virtual },
            );
            continue;
        };
        const prepared = prepareImageData(entry.key_ptr.image_id, image) orelse continue;
        const rect = placementSourceRect(placement, prepared.img_w, prepared.img_h);
        const orig_grid_size = placement.gridSize(image, &terminal.terminal);
        var grid_size = orig_grid_size;
        if (orig_grid_size.cols == 0 or orig_grid_size.rows == 0) {
            const cell_w: u32 = @max(terminal.cell_width, 1);
            const cell_h: u32 = @max(terminal.cell_height, 1);
            const width_with_offset: u32 = rect.width + placement.x_offset;
            const height_with_offset: u32 = rect.height + placement.y_offset;
            grid_size = .{
                .cols = @max(@as(u32, 1), (width_with_offset + cell_w - 1) / cell_w),
                .rows = @max(@as(u32, 1), (height_with_offset + cell_h - 1) / cell_h),
            };
        }
        placements[count] = .{
            .dest_x = viewport.col * @as(i32, @intCast(terminal.cell_width)) + @as(i32, @intCast(placement.x_offset)),
            .dest_y = viewport.row * @as(i32, @intCast(terminal.cell_height)) + @as(i32, @intCast(placement.y_offset)),
            .dest_w = grid_size.cols * terminal.cell_width,
            .dest_h = grid_size.rows * terminal.cell_height,
            .src_x = rect.x,
            .src_y = rect.y,
            .src_w = rect.width,
            .src_h = rect.height,
            .img_w = prepared.img_w,
            .img_h = prepared.img_h,
            .data_ptr = prepared.data_ptr,
            .data_len = prepared.data_len,
            .free_mode = prepared.free_mode,
        };
        count += 1;
    }

    if (has_virtual and count < MAX_KITTY_IMAGES) {
        const pages = &terminal.terminal.screens.active.pages;
        const top = pages.getTopLeft(.viewport);
        const bot = pages.getBottomRight(.viewport) orelse top;
        var virtual_it = ghostty.kitty.graphics.unicode.placementIterator(top, bot);
        while (count < MAX_KITTY_IMAGES) {
            const virtual_placement = virtual_it.next() orelse break;
            const image = terminal.terminal.screens.active.kitty_images.imageById(virtual_placement.image_id) orelse continue;
            const rendered = virtual_placement.renderPlacement(
                &terminal.terminal.screens.active.kitty_images,
                &image,
                terminal.cell_width,
                terminal.cell_height,
            ) catch |err| {
                logWarn("kitty virtual placement skipped image_id={} reason=render_failed err={}", .{ virtual_placement.image_id, err });
                continue;
            };
            if (rendered.dest_width == 0 or rendered.dest_height == 0) continue;

            const viewport = pages.pointFromPin(.viewport, rendered.top_left) orelse {
                logWarn("kitty virtual placement skipped image_id={} reason=no_viewport", .{virtual_placement.image_id});
                continue;
            };

            const prepared = prepareImageData(virtual_placement.image_id, image) orelse continue;
            placements[count] = .{
                .dest_x = @as(i32, @intCast(viewport.viewport.x)) * @as(i32, @intCast(terminal.cell_width)) + @as(i32, @intCast(rendered.offset_x)),
                .dest_y = @as(i32, @intCast(viewport.viewport.y)) * @as(i32, @intCast(terminal.cell_height)) + @as(i32, @intCast(rendered.offset_y)),
                .dest_w = rendered.dest_width,
                .dest_h = rendered.dest_height,
                .src_x = rendered.source_x,
                .src_y = rendered.source_y,
                .src_w = rendered.source_width,
                .src_h = rendered.source_height,
                .img_w = prepared.img_w,
                .img_h = prepared.img_h,
                .data_ptr = prepared.data_ptr,
                .data_len = prepared.data_len,
                .free_mode = prepared.free_mode,
            };
            count += 1;
        }
    }

    if (count == 0) {
        out_size.* = 0;
        return null;
    }

    var total: usize = 4;
    for (placements[0..count]) |p| total += IMAGE_HEADER_BYTES + p.data_len;
    const buf = ensureListSize(&terminal.image_snapshot_buffer, total) orelse {
        for (placements[0..count]) |p| switch (p.free_mode) {
            .malloc_buf => allocator.free(@constCast(p.data_ptr[0..p.data_len])),
            .zignal => zignal_png.freePixels(@ptrCast(@constCast(p.data_ptr)), p.img_w, p.img_h),
            .none => {},
        };
        return null;
    };

    writeIntLe(i32, buf[0..total], 0, @intCast(count));
    var offset: usize = 4;
    for (placements[0..count]) |p| {
        writeIntLe(i32, buf[0..total], offset + 0, p.dest_x);
        writeIntLe(i32, buf[0..total], offset + 4, p.dest_y);
        writeIntLe(u32, buf[0..total], offset + 8, p.dest_w);
        writeIntLe(u32, buf[0..total], offset + 12, p.dest_h);
        writeIntLe(u32, buf[0..total], offset + 16, p.src_x);
        writeIntLe(u32, buf[0..total], offset + 20, p.src_y);
        writeIntLe(u32, buf[0..total], offset + 24, p.src_w);
        writeIntLe(u32, buf[0..total], offset + 28, p.src_h);
        writeIntLe(u32, buf[0..total], offset + 32, p.img_w);
        writeIntLe(u32, buf[0..total], offset + 36, p.img_h);
        writeIntLe(u32, buf[0..total], offset + 40, @intCast(p.data_len));
        @memcpy(buf[offset + IMAGE_HEADER_BYTES .. offset + IMAGE_HEADER_BYTES + p.data_len], p.data_ptr[0..p.data_len]);
        offset += IMAGE_HEADER_BYTES + p.data_len;
        switch (p.free_mode) {
            .malloc_buf => allocator.free(@constCast(p.data_ptr[0..p.data_len])),
            .zignal => zignal_png.freePixels(@ptrCast(@constCast(p.data_ptr)), p.img_w, p.img_h),
            .none => {},
        }
    }

    out_size.* = total;
    return buf;
}

fn ghosttyMouseEncodeOptions(terminal: *ChuchuTerminal, any_button_pressed: bool, track_last_cell: bool, last_cell: *?ghostty.point.Coordinate) ghostty.input.MouseEncodeOptions {
    var opts = ghostty.input.MouseEncodeOptions{
        .event = terminal.terminal.flags.mouse_event,
        .format = terminal.terminal.flags.mouse_format,
        .size = .{
            .screen = .{
                .width = terminal.mouse_size.screen_width,
                .height = terminal.mouse_size.screen_height,
            },
            .cell = .{
                .width = @max(terminal.mouse_size.cell_width, 1),
                .height = @max(terminal.mouse_size.cell_height, 1),
            },
            .padding = .{
                .top = terminal.mouse_size.padding_top,
                .bottom = terminal.mouse_size.padding_bottom,
                .left = terminal.mouse_size.padding_left,
                .right = terminal.mouse_size.padding_right,
            },
        },
        .any_button_pressed = any_button_pressed,
    };
    if (track_last_cell) opts.last_cell = last_cell;
    return opts;
}

export fn chuchu_poll_title_ptr(handle: c.jlong) callconv(.c) ?[*:0]const u8 {
    const terminal = chuchuFromHandle(handle) orelse return null;
    if (!terminal.title_dirty) return null;
    terminal.title_dirty = false;
    return if (terminal.title) |title| @ptrCast(title) else "";
}

export fn chuchu_poll_pwd_ptr(handle: c.jlong) callconv(.c) ?[*:0]const u8 {
    const terminal = chuchuFromHandle(handle) orelse return null;
    if (!terminal.pwd_dirty) return null;
    terminal.pwd_dirty = false;
    return if (terminal.pwd) |pwd| @ptrCast(pwd) else "";
}

export fn chuchu_drain_bell_count(handle: c.jlong) callconv(.c) c.jint {
    const terminal = chuchuFromHandle(handle) orelse return 0;
    const count = terminal.bell_count;
    terminal.bell_count = 0;
    return @intCast(count);
}

export fn chuchu_apply_default_colors(handle: c.jlong, has_fg: bool, fg: [*c]const u8, has_bg: bool, bg: [*c]const u8, has_cursor: bool, cursor: [*c]const u8, has_palette: bool, palette: [*c]const u8) callconv(.c) void {
    const terminal = chuchuFromHandle(handle) orelse return;

    terminal.terminal.colors.foreground = if (has_fg) ghostty.color.DynamicRGB.init(rgbFromBytes(fg)) else .unset;
    terminal.terminal.colors.background = if (has_bg) ghostty.color.DynamicRGB.init(rgbFromBytes(bg)) else .unset;
    terminal.terminal.colors.cursor = if (has_cursor) ghostty.color.DynamicRGB.init(rgbFromBytes(cursor)) else .unset;
    if (has_palette) {
        var colors: ghostty.color.Palette = undefined;
        for (0..256) |i| {
            const off = i * 3;
            colors[i] = .{ .r = palette[off], .g = palette[off + 1], .b = palette[off + 2] };
        }
        terminal.terminal.colors.palette = .init(colors);
    } else {
        terminal.terminal.colors.palette = .default;
    }

    update_render_state(terminal);
}

export fn chuchu_set_color_scheme(handle: c.jlong, scheme: c.jint) callconv(.c) void {
    const terminal = chuchuFromHandle(handle) orelse return;
    terminal.color_scheme = if (scheme == 0) .light else .dark;
    terminal.color_scheme_set = true;
}

export fn chuchu_set_mouse_encoding_size(handle: c.jlong, screen_width: c.jint, screen_height: c.jint, cell_width: c.jint, cell_height: c.jint, padding_top: c.jint, padding_bottom: c.jint, padding_left: c.jint, padding_right: c.jint) callconv(.c) void {
    const terminal = chuchuFromHandle(handle) orelse return;
    terminal.mouse_size = .{
        .screen_width = @intCast(clampI32(screen_width, 1, 1)),
        .screen_height = @intCast(clampI32(screen_height, 1, 1)),
        .cell_width = @intCast(clampI32(cell_width, 1, 1)),
        .cell_height = @intCast(clampI32(cell_height, 1, 1)),
        .padding_top = @intCast(clampI32(padding_top, 0, 0)),
        .padding_bottom = @intCast(clampI32(padding_bottom, 0, 0)),
        .padding_left = @intCast(clampI32(padding_left, 0, 0)),
        .padding_right = @intCast(clampI32(padding_right, 0, 0)),
    };
    terminal.mouse_size_set = true;
}

export fn chuchu_write_remote(handle: c.jlong, data_ptr: [*c]const u8, data_len: usize) callconv(.c) void {
    const terminal = chuchuFromHandle(handle) orelse return;
    if (data_ptr == null or data_len == 0) return;
    const data = data_ptr[0..data_len];
    terminal.stream.nextSlice(data);
    update_render_state(terminal);
}

export fn chuchu_resize(handle: c.jlong, cols: c.jint, rows: c.jint, cell_width: c.jint, cell_height: c.jint) callconv(.c) void {
    const terminal = chuchuFromHandle(handle) orelse return;
    const new_cols: u16 = @intCast(clampI32(cols, 1, terminal.cols));
    const new_rows: u16 = @intCast(clampI32(rows, 1, terminal.rows));
    terminal.terminal.resize(allocator, new_cols, new_rows) catch return;
    terminal.cols = new_cols;
    terminal.rows = new_rows;
    terminal.cell_width = @intCast(clampI32(cell_width, 1, 1));
    terminal.cell_height = @intCast(clampI32(cell_height, 1, 1));
    terminal.terminal.width_px = @as(u32, new_cols) * terminal.cell_width;
    terminal.terminal.height_px = @as(u32, new_rows) * terminal.cell_height;
    update_render_state(terminal);
}

export fn chuchu_scroll(handle: c.jlong, delta: c.jint) callconv(.c) void {
    const terminal = chuchuFromHandle(handle) orelse return;
    terminal.terminal.scrollViewport(.{ .delta = @intCast(delta) });
    update_render_state(terminal);
}
