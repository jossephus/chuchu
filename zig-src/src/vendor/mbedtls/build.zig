const std = @import("std");

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

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const upstream = b.dependency("mbedtls_upstream", .{
        .target = target,
        .optimize = optimize,
    });

    const lib = b.addLibrary(.{
        .name = "mbedtls",
        .linkage = .static,
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });

    // libc_file can be passed as an option from parent build script
    if (b.option(std.Build.LazyPath, "libc_file", "Path to libc configuration file")) |libc_path| {
        lib.setLibCFile(libc_path);
    }

    lib.root_module.addIncludePath(upstream.path("include"));
    lib.addCSourceFiles(.{
        .root = upstream.path("library"),
        .files = mbedtls_src,
    });

    b.installArtifact(lib);

    const module = b.addModule("mbedtls", .{
        .root_source_file = null,
        .target = target,
        .optimize = optimize,
    });
    module.addIncludePath(upstream.path("include"));
}
