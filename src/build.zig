const std = @import("std");
const builtin = @import("builtin");
const ndk = @import("ndk.zig");

fn ndkPrebuiltTag() []const u8 {
    const os_part = switch (builtin.os.tag) {
        .macos => "darwin",
        .linux => "linux",
        .windows => "windows",
        else => @panic("Unsupported host OS for Android NDK prebuilt toolchain"),
    };

    const arch_part = switch (builtin.cpu.arch) {
        .x86_64 => "x86_64",
        .aarch64 => if (builtin.os.tag == .macos) "arm64" else "aarch64",
        else => @panic("Unsupported host architecture for Android NDK prebuilt toolchain"),
    };

    return std.fmt.comptimePrint("{s}-{s}", .{ os_part, arch_part });
}

const mbedtls_src: []const []const u8 = &.{
    "x509_create.c",
    "x509_crt.c",
    "psa_crypto_client.c",
    "aes.c",
    "psa_crypto_slot_management.c",
    "bignum_mod_raw.c",
    "psa_crypto_driver_wrappers_no_static.c",
    "camellia.c",
    "constant_time.c",
    "pk_wrap.c",
    "pk.c",
    "pkcs7.c",
    "aesce.c",
    "ssl_tls13_client.c",
    "ssl_tls12_client.c",
    "psa_util.c",
    "ecdh.c",
    "ssl_tls.c",
    "x509_crl.c",
    "cipher_wrap.c",
    "chacha20.c",
    "psa_crypto_rsa.c",
    "des.c",
    "ssl_cookie.c",
    "ctr_drbg.c",
    "psa_crypto_mac.c",
    "aesni.c",
    "dhm.c",
    "ssl_cache.c",
    "ssl_ciphersuites.c",
    "ecp_curves_new.c",
    "hmac_drbg.c",
    "rsa.c",
    "ssl_ticket.c",
    "asn1parse.c",
    "mps_trace.c",
    "pkwrite.c",
    "gcm.c",
    "sha1.c",
    "ssl_client.c",
    "asn1write.c",
    "ccm.c",
    "version_features.c",
    "aria.c",
    "lms.c",
    "psa_crypto_cipher.c",
    "entropy_poll.c",
    "x509write_csr.c",
    "platform.c",
    "cmac.c",
    "bignum.c",
    "pkparse.c",
    "psa_crypto_ffdh.c",
    "ssl_msg.c",
    "debug.c",
    "ripemd160.c",
    "pkcs5.c",
    "ssl_tls13_generic.c",
    "x509write.c",
    "bignum_mod.c",
    "pem.c",
    "oid.c",
    "error.c",
    "psa_crypto_pake.c",
    "x509_csr.c",
    "psa_its_file.c",
    "psa_crypto.c",
    "rsa_alt_helpers.c",
    "ssl_debug_helpers_generated.c",
    "platform_util.c",
    "psa_crypto_se.c",
    "base64.c",
    "memory_buffer_alloc.c",
    "mps_reader.c",
    "psa_crypto_aead.c",
    "ecp.c",
    "lmots.c",
    "version.c",
    "x509.c",
    "bignum_core.c",
    "chachapoly.c",
    "ssl_tls13_keys.c",
    "sha256.c",
    "ecp_curves.c",
    "md5.c",
    "timing.c",
    "psa_crypto_ecp.c",
    "psa_crypto_storage.c",
    "poly1305.c",
    "x509write_crt.c",
    "hkdf.c",
    "sha3.c",
    "threading.c",
    "padlock.c",
    "psa_crypto_hash.c",
    "pkcs12.c",
    "entropy.c",
    "ssl_tls13_server.c",
    "ssl_tls12_server.c",
    "net_sockets.c",
    "sha512.c",
    "md.c",
    "ecjpake.c",
    "cipher.c",
    "ecdsa.c",
    "nist_kw.c",
    "pk_ecc.c",
};

const libssh2_src: []const []const u8 = &.{
    "src/agent.c",
    "src/bcrypt_pbkdf.c",
    "src/blowfish.c",
    "src/chacha.c",
    "src/channel.c",
    "src/cipher-chachapoly.c",
    "src/comp.c",
    "src/crypto.c",
    "src/crypt.c",
    "src/global.c",
    "src/hostkey.c",
    "src/keepalive.c",
    "src/kex.c",
    "src/knownhost.c",
    "src/mac.c",
    "src/misc.c",
    "src/packet.c",
    "src/pem.c",
    "src/poly1305.c",
    "src/publickey.c",
    "src/scp.c",
    "src/session.c",
    "src/sftp.c",
    "src/transport.c",
    "src/userauth.c",
    "src/userauth_kbd_packet.c",
    "src/version.c",
};

fn resolveNdkHome(b: *std.Build, ndk_root: []const u8) []const u8 {
    if (ndk_root.len == 0) return ndk_root;

    const toolchains_path = b.pathJoin(&.{ ndk_root, "toolchains", "llvm" });
    std.fs.cwd().access(toolchains_path, .{}) catch {
        var dir = std.fs.cwd().openDir(ndk_root, .{ .iterate = true }) catch return ndk_root;
        defer dir.close();

        var iter = dir.iterate();
        while (iter.next() catch null) |entry| {
            if (entry.kind != .directory) continue;
            return b.pathJoin(&.{ ndk_root, entry.name });
        }
        return ndk_root;
    };

    return ndk_root;
}

fn buildLibssh2(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    libc_config: std.Build.LazyPath,
    ndk_include_dir: []const u8,
    ndk_target_include_dir: []const u8,
) *std.Build.Step.Compile {
    const upstream = b.dependency("libssh2_upstream", .{});
    const mbedtls_lib = buildMbedtls(b, target, optimize, libc_config, ndk_include_dir, ndk_target_include_dir);

    const config_header = b.addConfigHeader(.{
        .style = .{ .cmake = upstream.path("src/libssh2_config_cmake.h.in") },
        .include_path = "libssh2_config.h",
    }, .{
        .LIBSSH2_API = "",
        .LIBSSH2_HAVE_ZLIB = false,
        .HAVE_SYS_UIO_H = true,
        .HAVE_WRITEV = true,
        .HAVE_SYS_SOCKET_H = true,
        .HAVE_NETINET_IN_H = true,
        .HAVE_ARPA_INET_H = true,
        .HAVE_SYS_TYPES_H = true,
        .HAVE_INTTYPES_H = true,
        .HAVE_STDINT_H = true,
    });

    const lib = b.addLibrary(.{
        .name = "ssh2_local",
        .linkage = .static,
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });

    lib.root_module.addConfigHeader(config_header);
    lib.root_module.addIncludePath(.{ .cwd_relative = ndk_include_dir });
    lib.root_module.addIncludePath(.{ .cwd_relative = ndk_target_include_dir });
    lib.root_module.addIncludePath(upstream.path("include"));
    lib.root_module.addIncludePath(b.dependency("mbedtls", .{}).path("include"));
    lib.root_module.addCMacro("HAVE_CONFIG_H", "1");
    lib.root_module.addCMacro("LIBSSH2_MBEDTLS", "1");
    lib.root_module.addCSourceFiles(.{
        .files = libssh2_src,
        .root = upstream.path(""),
    });
    lib.root_module.linkLibrary(mbedtls_lib);

    return lib;
}

fn buildMbedtls(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    libc_config: std.Build.LazyPath,
    ndk_include_dir: []const u8,
    ndk_target_include_dir: []const u8,
) *std.Build.Step.Compile {
    const upstream = b.dependency("mbedtls", .{});

    const lib = b.addLibrary(.{
        .name = "mbedtls_local",
        .linkage = .static,
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });

    lib.setLibCFile(libc_config);
    lib.root_module.addIncludePath(.{ .cwd_relative = ndk_include_dir });
    lib.root_module.addIncludePath(.{ .cwd_relative = ndk_target_include_dir });
    lib.root_module.addIncludePath(upstream.path("include"));
    lib.root_module.addCSourceFiles(.{
        .root = upstream.path("library"),
        .files = mbedtls_src,
    });

    return lib;
}

pub fn build(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) *std.Build.Step.Compile {
    const ghostty_dep = b.dependency("ghostty", .{
        .target = target,
        .optimize = optimize,
    });
    const zignal_dep = b.dependency("zignal", .{
        .target = target,
        .optimize = optimize,
    });
    const root_module = b.createModule(.{
        .root_source_file = b.path("src/bridge/zignal_png.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    root_module.addIncludePath(b.path("src/bridge"));
    root_module.addIncludePath(b.dependency("libssh2_upstream", .{}).path("include"));
    root_module.addIncludePath(b.dependency("mbedtls", .{}).path("include"));
    root_module.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));
    root_module.addImport("zignal", zignal_dep.module("zignal"));

    const lib = b.addLibrary(.{
        .linkage = .dynamic,
        .name = "chuchu_jni",
        .root_module = root_module,
    });

    const ndk_root = b.graph.env_map.get("ANDROID_NDK_HOME") orelse b.graph.env_map.get("ANDROID_NDK_ROOT") orelse "";
    const ndk_home = resolveNdkHome(b, ndk_root);
    if (ndk_home.len == 0) {
        std.debug.panic("ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) must be set", .{});
    }

    const android_target = ndk.getAndroidTriple(target.result) catch {
        std.debug.panic("target must be Android", .{});
    };
    std.debug.assert(target.result.os.tag == .linux);
    const android_api_version: u32 = target.result.os.version_range.linux.android;

    const ndk_sysroot = b.pathJoin(&.{
        ndk_home,
        "toolchains",
        "llvm",
        "prebuilt",
        ndkPrebuiltTag(),
        "sysroot",
    });

    const libc_config = ndk.createLibC(
        b,
        android_target,
        android_api_version,
        ndk_sysroot,
    );

    const include_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "include" });
    const target_include_dir = b.pathJoin(&.{ include_dir, android_target });
    const libssh2 = buildLibssh2(b, target, optimize, libc_config, include_dir, target_include_dir);
    lib.addIncludePath(.{ .cwd_relative = include_dir });
    lib.addIncludePath(.{ .cwd_relative = target_include_dir });
    lib.addIncludePath(ghostty_dep.path("include"));

    const api_dir = b.fmt("{d}", .{android_api_version});
    const lib_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "lib", android_target, api_dir });
    lib.addLibraryPath(.{ .cwd_relative = lib_dir });

    lib.setLibCFile(libc_config);
    lib.linkLibrary(libssh2);
    lib.linkSystemLibrary("log");
    lib.linkLibC();

    b.installArtifact(lib);
    return lib;
}
