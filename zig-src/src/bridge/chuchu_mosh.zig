//! JNI bridge for the mosh Zig core. Wraps the C ABI exported by `mosh/ffi.zig`.

const std = @import("std");
const mosh = @import("mosh");

const c = @cImport({
    @cInclude("android/log.h");
    @cInclude("jni.h");
});

const allocator = std.heap.c_allocator;
const LOG_TAG = "ChuMoshJNI";

fn androidLog(prio: c_int, comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    _ = c.__android_log_print(prio, LOG_TAG, "%.*s", @as(c_int, @intCast(line.len)), line.ptr);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    androidLog(c.ANDROID_LOG_INFO, fmt, args);
}

fn logError(comptime fmt: []const u8, args: anytype) void {
    androidLog(c.ANDROID_LOG_ERROR, fmt, args);
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

fn jniDupString(env: *c.JNIEnv, s: c.jstring) ?[]u8 {
    if (s == null) return null;
    const chars = env.*.*.GetStringUTFChars.?(env, s, null);
    if (chars == null) return null;
    defer env.*.*.ReleaseStringUTFChars.?(env, s, chars);
    return allocator.dupe(u8, std.mem.span(chars)) catch null;
}

fn clientFromHandle(handle: c.jlong) *anyopaque {
    const raw_handle: u64 = @bitCast(handle);
    return @ptrFromInt(@as(usize, @truncate(raw_handle)));
}

fn handleFromClient(client: *anyopaque) c.jlong {
    const raw_ptr: u64 = @intCast(@intFromPtr(client));
    return @bitCast(raw_ptr);
}

// ---------------------------------------------------------------------------
// NativeMoshBridge JNI exports
// ---------------------------------------------------------------------------

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativeCreate(
    env: *c.JNIEnv,
    thiz: c.jobject,
    j_config_json: c.jstring,
) callconv(.c) c.jlong {
    _ = thiz;

    const config_owned = jniDupString(env, j_config_json);
    defer if (config_owned) |s| allocator.free(s);
    const config_slice: []const u8 = config_owned orelse return 0;
    const config_nt = allocator.allocSentinel(u8, config_slice.len, 0) catch return 0;
    defer allocator.free(config_nt);
    @memcpy(config_nt[0..config_slice.len], config_slice);

    var handle: ?*anyopaque = null;
    const rc = mosh.ffi.mosh_client_create(config_nt.ptr, &handle);
    if (rc != 0 or handle == null) {
        logError("mosh_client_create failed rc={d}", .{rc});
        return 0;
    }
    return handleFromClient(handle.?);
}

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativeStart(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    const raw = clientFromHandle(handle);
    return mosh.ffi.mosh_client_start(raw);
}

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativeSendInput(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
    j_data: c.jbyteArray,
) callconv(.c) c.jint {
    _ = thiz;
    const raw = clientFromHandle(handle);

    if (j_data == null) return mosh.ffi.mosh_client_send_input(raw, null, 0);

    const len = env.*.*.GetArrayLength.?(env, j_data);
    if (len <= 0) return mosh.ffi.mosh_client_send_input(raw, null, 0);

    const elems = env.*.*.GetByteArrayElements.?(env, j_data, null);
    if (elems == null) return 1; // error
    defer env.*.*.ReleaseByteArrayElements.?(env, j_data, elems, c.JNI_ABORT);

    const ptr: [*]const u8 = @ptrCast(elems);
    return mosh.ffi.mosh_client_send_input(raw, ptr, @intCast(len));
}

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativeResize(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
    cols: c.jint,
    rows: c.jint,
) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    const raw = clientFromHandle(handle);
    return mosh.ffi.mosh_client_resize(raw, cols, rows);
}

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativeMaintenanceTick(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    const raw = clientFromHandle(handle);
    return mosh.ffi.mosh_client_maintenance_tick(raw);
}

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativePumpNetwork(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    const raw = clientFromHandle(handle);
    return mosh.ffi.mosh_client_pump_network(raw);
}

/// outMeta must be a long[5] array. On success it is filled with:
/// [0] = event_type, [1] = written, [2] = cols, [3] = rows, [4] = echo_ack
/// Returns the C error code (0 = ok).
export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativePollOutput(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
    j_out_buf: c.jbyteArray,
    j_out_meta: c.jlongArray,
) callconv(.c) c.jint {
    _ = thiz;
    const raw = clientFromHandle(handle);

    var out_buf: [*c]u8 = null;
    var cap: usize = 0;
    if (j_out_buf != null) {
        cap = @intCast(env.*.*.GetArrayLength.?(env, j_out_buf));
        if (cap > 0) {
            out_buf = @ptrCast(env.*.*.GetByteArrayElements.?(env, j_out_buf, null));
        }
    }
    defer if (out_buf != null and j_out_buf != null) {
        env.*.*.ReleaseByteArrayElements.?(env, j_out_buf, @ptrCast(out_buf), 0);
    };

    var written: usize = 0;
    var event_type: c_int = 0;
    var out_cols: c_int = 0;
    var out_rows: c_int = 0;
    var echo_ack: u64 = 0;

    const rc = mosh.ffi.mosh_client_poll_output(
        raw,
        out_buf,
        cap,
        &written,
        &event_type,
        &out_cols,
        &out_rows,
        &echo_ack,
    );

    if (j_out_meta != null and env.*.*.GetArrayLength.?(env, j_out_meta) >= 5) {
        const meta = env.*.*.GetLongArrayElements.?(env, j_out_meta, null);
        if (meta != null) {
            meta[0] = event_type;
            meta[1] = @intCast(written);
            meta[2] = out_cols;
            meta[3] = out_rows;
            meta[4] = @intCast(echo_ack);
            env.*.*.ReleaseLongArrayElements.?(env, j_out_meta, meta, 0);
        }
    }

    return rc;
}

/// outState must be a long[7] array. On success it is filled with:
/// [0] = state, [1] = last_failure_code, [2] = last_state_num_sent,
/// [3] = last_state_num_received, [4] = pending_outbound,
/// [5] = pending_host_ops, [6] = current_rto_ms
export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativePollState(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
    j_out_state: c.jlongArray,
) callconv(.c) c.jint {
    _ = thiz;
    const raw = clientFromHandle(handle);

    var runtime_state: mosh.types.RuntimeState = undefined;
    const rc = mosh.ffi.mosh_client_poll_state(raw, &runtime_state);

    if (j_out_state != null and env.*.*.GetArrayLength.?(env, j_out_state) >= 7) {
        const state = env.*.*.GetLongArrayElements.?(env, j_out_state, null);
        if (state != null) {
            state[0] = runtime_state.state;
            state[1] = runtime_state.last_failure_code;
            state[2] = @intCast(runtime_state.last_state_num_sent);
            state[3] = @intCast(runtime_state.last_state_num_received);
            state[4] = runtime_state.pending_outbound;
            state[5] = runtime_state.pending_host_ops;
            state[6] = runtime_state.current_rto_ms;
            env.*.*.ReleaseLongArrayElements.?(env, j_out_state, state, 0);
        }
    }

    return rc;
}

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativeStop(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    const raw = clientFromHandle(handle);
    return mosh.ffi.mosh_client_stop(raw);
}

export fn Java_com_jossephus_chuchu_service_mosh_NativeMoshBridge_nativeDestroy(
    env: *c.JNIEnv,
    thiz: c.jobject,
    handle: c.jlong,
) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    if (handle == 0) return 0;
    const raw = clientFromHandle(handle);
    return mosh.ffi.mosh_client_destroy(raw);
}
