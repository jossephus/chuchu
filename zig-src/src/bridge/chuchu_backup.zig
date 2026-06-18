//! JNI bridge for encrypted backup container crypto.

const std = @import("std");

const c = @cImport({
    @cInclude("jni.h");
});

const allocator = std.heap.c_allocator;

const FORMAT_VERSION: i32 = 1;
const KDF_ID_PBKDF2_HMAC_SHA1: i32 = 1;
const CIPHER_ID_AES_256_GCM: i32 = 1;
const KDF_ITERATIONS: i32 = 210_000;
const SALT_SIZE_BYTES: usize = 16;
const IV_SIZE_BYTES: usize = 12;
const MAX_BACKUP_SIZE_BYTES: usize = 32 * 1024 * 1024;
const CONTAINER_MAGIC: i32 = 0x4348424b; // CHBK

const STATUS_OK: u8 = 0;
const STATUS_FORMAT_ERROR: u8 = 1;
const STATUS_INVALID_PASSPHRASE: u8 = 2;
const STATUS_INTERNAL_ERROR: u8 = 3;

const Aes256Gcm = std.crypto.aead.aes_gcm.Aes256Gcm;
const HmacSha1 = std.crypto.auth.hmac.HmacSha1;

const NativeError = error{
    InvalidInput,
    InvalidFormat,
    BackupTooLarge,
    InvalidPassphrase,
    CryptoFailure,
};

export fn Java_com_jossephus_chuchu_service_backup_NativeBackupBridge_nativeEncrypt(
    env: *c.JNIEnv,
    thiz: c.jobject,
    plaintext: c.jbyteArray,
    passphrase: c.jcharArray,
) callconv(.c) c.jbyteArray {
    _ = thiz;

    const plaintext_bytes = readJByteArray(env, plaintext) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid plaintext");
    };
    defer allocator.free(plaintext_bytes);

    const passphrase_bytes = readJCharArrayAsBigEndianBytes(env, passphrase) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid passphrase");
    };
    defer {
        std.crypto.secureZero(u8, passphrase_bytes);
        allocator.free(passphrase_bytes);
    }

    const encrypted = encryptPayload(plaintext_bytes, passphrase_bytes) catch |err| {
        return statusFromError(env, err);
    };
    defer {
        std.crypto.secureZero(u8, encrypted);
        allocator.free(encrypted);
    }

    return statusResultBytes(env, STATUS_OK, encrypted);
}

export fn Java_com_jossephus_chuchu_service_backup_NativeBackupBridge_nativeDecrypt(
    env: *c.JNIEnv,
    thiz: c.jobject,
    ciphertext: c.jbyteArray,
    passphrase: c.jcharArray,
) callconv(.c) c.jbyteArray {
    _ = thiz;

    const ciphertext_bytes = readJByteArray(env, ciphertext) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid ciphertext");
    };
    defer allocator.free(ciphertext_bytes);

    const passphrase_utf16be = readJCharArrayAsBigEndianBytes(env, passphrase) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid passphrase");
    };
    defer {
        std.crypto.secureZero(u8, passphrase_utf16be);
        allocator.free(passphrase_utf16be);
    }

    const passphrase_utf8 = readJCharArrayAsUtf8(env, passphrase) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid passphrase");
    };
    defer {
        std.crypto.secureZero(u8, passphrase_utf8);
        allocator.free(passphrase_utf8);
    }

    const plaintext = decryptPayloadWithFallback(ciphertext_bytes, passphrase_utf16be, passphrase_utf8) catch |err| {
        return statusFromError(env, err);
    };
    defer {
        std.crypto.secureZero(u8, plaintext);
        allocator.free(plaintext);
    }

    return statusResultBytes(env, STATUS_OK, plaintext);
}

fn encryptPayload(plaintext: []const u8, passphrase: []const u8) NativeError![]u8 {
    if (plaintext.len > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;
    if (passphrase.len == 0) return error.InvalidInput;

    var salt: [SALT_SIZE_BYTES]u8 = undefined;
    var iv: [IV_SIZE_BYTES]u8 = undefined;
    std.crypto.random.bytes(&salt);
    std.crypto.random.bytes(&iv);

    const metadata = buildMetadata(salt, iv);
    var key: [32]u8 = undefined;
    deriveKey(passphrase, &salt, &key) catch return error.CryptoFailure;
    defer std.crypto.secureZero(u8, &key);

    const tag_len = Aes256Gcm.tag_length;
    const total_cipher_size = plaintext.len + tag_len;
    if (total_cipher_size > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;

    const ciphertext = allocator.alloc(u8, plaintext.len) catch return error.CryptoFailure;
    defer allocator.free(ciphertext);
    var tag: [tag_len]u8 = undefined;

    Aes256Gcm.encrypt(
        ciphertext,
        &tag,
        plaintext,
        &metadata,
        iv,
        key,
    );

    const total_size = metadata.len + 4 + total_cipher_size;
    if (total_size > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;

    const out = allocator.alloc(u8, total_size) catch return error.CryptoFailure;
    var offset: usize = 0;
    @memcpy(out[offset .. offset + metadata.len], metadata[0..]);
    offset += metadata.len;
    writeIntBe(out[offset .. offset + 4], @intCast(total_cipher_size));
    offset += 4;
    @memcpy(out[offset .. offset + ciphertext.len], ciphertext);
    offset += ciphertext.len;
    @memcpy(out[offset .. offset + tag.len], &tag);

    return out;
}

fn decryptPayload(ciphertext_container: []const u8, passphrase: []const u8) NativeError![]u8 {
    if (ciphertext_container.len > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;
    if (passphrase.len == 0) return error.InvalidInput;

    var reader = Reader{ .bytes = ciphertext_container, .offset = 0 };

    const metadata = readMetadata(&reader) catch return error.InvalidFormat;

    const ciphertext_size_i32 = reader.readInt() catch return error.InvalidFormat;
    if (ciphertext_size_i32 < 0) return error.InvalidFormat;
    const ciphertext_size: usize = @intCast(ciphertext_size_i32);
    if (ciphertext_size > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;

    const ciphertext_and_tag = reader.readSlice(ciphertext_size) catch return error.InvalidFormat;
    if (!reader.isEof()) return error.InvalidFormat;

    if (ciphertext_and_tag.len < Aes256Gcm.tag_length) return error.InvalidFormat;

    const cipher_only_len = ciphertext_and_tag.len - Aes256Gcm.tag_length;
    const cipher_only = ciphertext_and_tag[0..cipher_only_len];
    const tag_slice = ciphertext_and_tag[cipher_only_len..];
    var tag: [Aes256Gcm.tag_length]u8 = undefined;
    @memcpy(&tag, tag_slice);

    var key: [32]u8 = undefined;
    deriveKey(passphrase, &metadata.salt, &key) catch return error.CryptoFailure;
    defer std.crypto.secureZero(u8, &key);

    const plaintext = allocator.alloc(u8, cipher_only.len) catch return error.CryptoFailure;
    errdefer allocator.free(plaintext);

    Aes256Gcm.decrypt(
        plaintext,
        cipher_only,
        tag,
        &metadata.encoded,
        metadata.iv,
        key,
    ) catch return error.InvalidPassphrase;

    return plaintext;
}

fn decryptPayloadWithFallback(ciphertext_container: []const u8, passphrase_primary: []const u8, passphrase_legacy: []const u8) NativeError![]u8 {
    return decryptPayload(ciphertext_container, passphrase_primary) catch |err| switch (err) {
        error.InvalidPassphrase => decryptPayload(ciphertext_container, passphrase_legacy),
        else => err,
    };
}

fn deriveKey(passphrase: []const u8, salt: *const [SALT_SIZE_BYTES]u8, out_key: *[32]u8) !void {
    try std.crypto.pwhash.pbkdf2(out_key, passphrase, salt, @intCast(KDF_ITERATIONS), HmacSha1);
}

fn buildMetadata(salt: [SALT_SIZE_BYTES]u8, iv: [IV_SIZE_BYTES]u8) [5 * 4 + 4 + SALT_SIZE_BYTES + 4 + IV_SIZE_BYTES]u8 {
    var metadata: [5 * 4 + 4 + SALT_SIZE_BYTES + 4 + IV_SIZE_BYTES]u8 = undefined;
    var offset: usize = 0;

    writeIntBe(metadata[offset .. offset + 4], CONTAINER_MAGIC);
    offset += 4;
    writeIntBe(metadata[offset .. offset + 4], FORMAT_VERSION);
    offset += 4;
    writeIntBe(metadata[offset .. offset + 4], KDF_ID_PBKDF2_HMAC_SHA1);
    offset += 4;
    writeIntBe(metadata[offset .. offset + 4], KDF_ITERATIONS);
    offset += 4;
    writeIntBe(metadata[offset .. offset + 4], CIPHER_ID_AES_256_GCM);
    offset += 4;

    writeIntBe(metadata[offset .. offset + 4], SALT_SIZE_BYTES);
    offset += 4;
    @memcpy(metadata[offset .. offset + SALT_SIZE_BYTES], &salt);
    offset += SALT_SIZE_BYTES;

    writeIntBe(metadata[offset .. offset + 4], IV_SIZE_BYTES);
    offset += 4;
    @memcpy(metadata[offset .. offset + IV_SIZE_BYTES], &iv);

    return metadata;
}

const Metadata = struct {
    salt: [SALT_SIZE_BYTES]u8,
    iv: [IV_SIZE_BYTES]u8,
    encoded: [5 * 4 + 4 + SALT_SIZE_BYTES + 4 + IV_SIZE_BYTES]u8,
};

fn readMetadata(reader: *Reader) !Metadata {
    var encoded: [5 * 4 + 4 + SALT_SIZE_BYTES + 4 + IV_SIZE_BYTES]u8 = undefined;
    var encoded_offset: usize = 0;

    const magic = reader.readInt() catch return error.InvalidFormat;
    writeIntBe(encoded[encoded_offset .. encoded_offset + 4], magic);
    encoded_offset += 4;
    if (magic != CONTAINER_MAGIC) return error.InvalidFormat;

    const version = reader.readInt() catch return error.InvalidFormat;
    writeIntBe(encoded[encoded_offset .. encoded_offset + 4], version);
    encoded_offset += 4;
    if (version != FORMAT_VERSION) return error.InvalidFormat;

    const kdf_id = reader.readInt() catch return error.InvalidFormat;
    writeIntBe(encoded[encoded_offset .. encoded_offset + 4], kdf_id);
    encoded_offset += 4;
    if (kdf_id != KDF_ID_PBKDF2_HMAC_SHA1) return error.InvalidFormat;

    const iterations = reader.readInt() catch return error.InvalidFormat;
    writeIntBe(encoded[encoded_offset .. encoded_offset + 4], iterations);
    encoded_offset += 4;
    if (iterations != KDF_ITERATIONS) return error.InvalidFormat;

    const cipher_id = reader.readInt() catch return error.InvalidFormat;
    writeIntBe(encoded[encoded_offset .. encoded_offset + 4], cipher_id);
    encoded_offset += 4;
    if (cipher_id != CIPHER_ID_AES_256_GCM) return error.InvalidFormat;

    const salt_len = reader.readInt() catch return error.InvalidFormat;
    writeIntBe(encoded[encoded_offset .. encoded_offset + 4], salt_len);
    encoded_offset += 4;
    if (salt_len != SALT_SIZE_BYTES) return error.InvalidFormat;
    const salt_bytes = reader.readSlice(SALT_SIZE_BYTES) catch return error.InvalidFormat;
    var salt: [SALT_SIZE_BYTES]u8 = undefined;
    @memcpy(&salt, salt_bytes);
    @memcpy(encoded[encoded_offset .. encoded_offset + SALT_SIZE_BYTES], salt_bytes);
    encoded_offset += SALT_SIZE_BYTES;

    const iv_len = reader.readInt() catch return error.InvalidFormat;
    writeIntBe(encoded[encoded_offset .. encoded_offset + 4], iv_len);
    encoded_offset += 4;
    if (iv_len != IV_SIZE_BYTES) return error.InvalidFormat;
    const iv_bytes = reader.readSlice(IV_SIZE_BYTES) catch return error.InvalidFormat;
    var iv: [IV_SIZE_BYTES]u8 = undefined;
    @memcpy(&iv, iv_bytes);
    @memcpy(encoded[encoded_offset .. encoded_offset + IV_SIZE_BYTES], iv_bytes);

    return .{
        .salt = salt,
        .iv = iv,
        .encoded = encoded,
    };
}

const Reader = struct {
    bytes: []const u8,
    offset: usize,

    fn readInt(self: *Reader) !i32 {
        const slice = try self.readSlice(4);
        return readIntBe(slice);
    }

    fn readSlice(self: *Reader, size: usize) ![]const u8 {
        if (size > self.bytes.len - self.offset) return error.InvalidFormat;
        const out = self.bytes[self.offset .. self.offset + size];
        self.offset += size;
        return out;
    }

    fn isEof(self: *const Reader) bool {
        return self.offset == self.bytes.len;
    }
};

fn writeIntBe(dst: []u8, value: i32) void {
    const bits: u32 = @bitCast(value);
    dst[0] = @intCast((bits >> 24) & 0xff);
    dst[1] = @intCast((bits >> 16) & 0xff);
    dst[2] = @intCast((bits >> 8) & 0xff);
    dst[3] = @intCast(bits & 0xff);
}

fn readIntBe(src: []const u8) i32 {
    const value: u32 =
        (@as(u32, src[0]) << 24) |
        (@as(u32, src[1]) << 16) |
        (@as(u32, src[2]) << 8) |
        @as(u32, src[3]);
    return @bitCast(value);
}

fn readJByteArray(env: *c.JNIEnv, array: c.jbyteArray) ![]u8 {
    if (array == null) return error.InvalidInput;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len < 0) return error.InvalidInput;
    const size: usize = @intCast(len);
    const out = try allocator.alloc(u8, size);
    if (size == 0) return out;

    var is_copy: c.jboolean = c.JNI_FALSE;
    const ptr = env.*.*.GetByteArrayElements.?(env, array, &is_copy) orelse {
        allocator.free(out);
        return error.InvalidInput;
    };
    defer env.*.*.ReleaseByteArrayElements.?(env, array, ptr, c.JNI_ABORT);

    const src: [*]u8 = @ptrCast(ptr);
    @memcpy(out, src[0..size]);
    return out;
}

fn readJCharArrayAsBigEndianBytes(env: *c.JNIEnv, array: c.jcharArray) ![]u8 {
    if (array == null) return error.InvalidInput;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len < 0) return error.InvalidInput;
    const char_count: usize = @intCast(len);

    var is_copy: c.jboolean = c.JNI_FALSE;
    const ptr = env.*.*.GetCharArrayElements.?(env, array, &is_copy) orelse return error.InvalidInput;
    defer env.*.*.ReleaseCharArrayElements.?(env, array, ptr, c.JNI_ABORT);

    const utf16: [*]u16 = @ptrCast(ptr);
    const out = try allocator.alloc(u8, char_count * 2);
    var i: usize = 0;
    while (i < char_count) : (i += 1) {
        const unit = utf16[i];
        out[(i * 2)] = @intCast((unit >> 8) & 0xff);
        out[(i * 2) + 1] = @intCast(unit & 0xff);
    }
    return out;
}

fn readJCharArrayAsUtf8(env: *c.JNIEnv, array: c.jcharArray) ![]u8 {
    if (array == null) return error.InvalidInput;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len < 0) return error.InvalidInput;
    const char_count: usize = @intCast(len);

    var is_copy: c.jboolean = c.JNI_FALSE;
    const ptr = env.*.*.GetCharArrayElements.?(env, array, &is_copy) orelse return error.InvalidInput;
    defer env.*.*.ReleaseCharArrayElements.?(env, array, ptr, c.JNI_ABORT);

    const utf16: [*]u16 = @ptrCast(ptr);
    return std.unicode.utf16LeToUtf8Alloc(allocator, utf16[0..char_count]) catch error.InvalidInput;
}

fn statusFromError(env: *c.JNIEnv, err: anyerror) c.jbyteArray {
    return switch (err) {
        error.InvalidPassphrase => statusResult(env, STATUS_INVALID_PASSPHRASE, "Invalid backup passphrase"),
        error.InvalidFormat => statusResult(env, STATUS_FORMAT_ERROR, "Invalid backup file"),
        error.BackupTooLarge => statusResult(env, STATUS_FORMAT_ERROR, "Backup file is too large"),
        else => statusResult(env, STATUS_INTERNAL_ERROR, "Native backup failure"),
    };
}

fn statusResult(env: *c.JNIEnv, status: u8, message: []const u8) c.jbyteArray {
    return statusResultBytes(env, status, message);
}

fn statusResultBytes(env: *c.JNIEnv, status: u8, payload: []const u8) c.jbyteArray {
    const total = 1 + payload.len;
    const out = env.*.*.NewByteArray.?(env, @intCast(total)) orelse return null;
    var temp = allocator.alloc(u8, total) catch return null;
    defer allocator.free(temp);

    temp[0] = status;
    if (payload.len > 0) {
        @memcpy(temp[1..], payload);
    }

    env.*.*.SetByteArrayRegion.?(env, out, 0, @intCast(total), @ptrCast(temp.ptr));
    return out;
}

fn encryptPayloadForTest(plaintext: []const u8, passphrase: []const u8, salt: [SALT_SIZE_BYTES]u8, iv: [IV_SIZE_BYTES]u8) ![]u8 {
    const metadata = buildMetadata(salt, iv);
    var key: [32]u8 = undefined;
    defer std.crypto.secureZero(u8, &key);
    try deriveKey(passphrase, &salt, &key);

    const tag_len = Aes256Gcm.tag_length;
    const ciphertext = try allocator.alloc(u8, plaintext.len);
    defer allocator.free(ciphertext);
    var tag: [tag_len]u8 = undefined;
    Aes256Gcm.encrypt(ciphertext, &tag, plaintext, &metadata, iv, key);

    const total_cipher_size = plaintext.len + tag_len;
    const total_size = metadata.len + 4 + total_cipher_size;
    const out = try allocator.alloc(u8, total_size);
    var offset: usize = 0;
    @memcpy(out[offset .. offset + metadata.len], metadata[0..]);
    offset += metadata.len;
    writeIntBe(out[offset .. offset + 4], @intCast(total_cipher_size));
    offset += 4;
    @memcpy(out[offset .. offset + ciphertext.len], ciphertext);
    offset += ciphertext.len;
    @memcpy(out[offset .. offset + tag.len], &tag);
    return out;
}

test "deriveKey matches JVM-compatible UTF-16BE PBKDF2 vector ascii" {
    const passphrase = "\x00p\x00a\x00s\x00s\x00w\x00o\x00r\x00d";
    const salt = [SALT_SIZE_BYTES]u8{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
    var key: [32]u8 = undefined;
    defer std.crypto.secureZero(u8, &key);

    try deriveKey(passphrase, &salt, &key);
    const expected = [_]u8{ 0xde, 0x7c, 0x12, 0x36, 0x4e, 0xea, 0xd1, 0x9a, 0xc7, 0x06, 0xb0, 0xa4, 0xb9, 0x88, 0xf0, 0x60, 0x11, 0xa4, 0x82, 0x7d, 0x23, 0x66, 0x98, 0xfc, 0xd6, 0x5e, 0x57, 0x89, 0xdb, 0x5a, 0x74, 0xc7 };
    try std.testing.expectEqualSlices(u8, &expected, &key);
}

test "deriveKey matches JVM-compatible UTF-16BE PBKDF2 vector unicode" {
    const passphrase = "\x00p\x00\xe1\x00s\x00s\x00w\xd8=\xdd\x10\x00r\x00d";
    const salt = [SALT_SIZE_BYTES]u8{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
    var key: [32]u8 = undefined;
    defer std.crypto.secureZero(u8, &key);

    try deriveKey(passphrase, &salt, &key);
    const expected = [_]u8{ 0x7e, 0x67, 0xf2, 0xf5, 0x11, 0x6a, 0xba, 0x42, 0xe3, 0x47, 0xcf, 0x7e, 0x08, 0x95, 0x21, 0x46, 0x86, 0xb4, 0xe2, 0x12, 0xc0, 0xe8, 0xf1, 0x02, 0x16, 0xc1, 0x5c, 0x81, 0xcf, 0x97, 0x32, 0x22 };
    try std.testing.expectEqualSlices(u8, &expected, &key);
}

test "decrypt fallback handles legacy UTF-8 and new UTF-16BE passphrase bytes" {
    const plaintext = "compatibility-check";
    const salt = [SALT_SIZE_BYTES]u8{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
    const iv = [IV_SIZE_BYTES]u8{ 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27 };

    const passphrase_utf8 = "awdsefrr";
    const passphrase_utf16be = "\x00a\x00w\x00d\x00s\x00e\x00f\x00r\x00r";

    const legacy_container = try encryptPayloadForTest(plaintext, passphrase_utf8, salt, iv);
    defer allocator.free(legacy_container);

    const new_container = try encryptPayloadForTest(plaintext, passphrase_utf16be, salt, iv);
    defer allocator.free(new_container);

    const legacy_dec = try decryptPayloadWithFallback(legacy_container, passphrase_utf16be, passphrase_utf8);
    defer allocator.free(legacy_dec);
    try std.testing.expectEqualStrings(plaintext, legacy_dec);

    const new_dec = try decryptPayloadWithFallback(new_container, passphrase_utf16be, passphrase_utf8);
    defer allocator.free(new_dec);
    try std.testing.expectEqualStrings(plaintext, new_dec);
}
