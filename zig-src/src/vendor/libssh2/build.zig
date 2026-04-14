const std = @import("std");

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

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const upstream = b.dependency("libssh2_upstream", .{
        .target = target,
        .optimize = optimize,
    });

    // Get optional libc file path from parent (as LazyPath)
    const libc_file = b.option(std.Build.LazyPath, "libc_file", "Path to libc configuration file");

    // Build mbedtls with libc_file option (parent should pass this)
    const mbedtls_dep = b.dependency("mbedtls", .{
        .target = target,
        .optimize = optimize,
        .libc_file = libc_file,
    });

    // Get the upstream mbedtls dependency to access include paths
    const mbedtls_upstream = mbedtls_dep.builder.dependency("mbedtls_upstream", .{
        .target = target,
        .optimize = optimize,
    });

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
        .name = "ssh2",
        .linkage = .static,
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });

    // Set libc file on libssh2 if provided
    if (libc_file) |path| {
        lib.setLibCFile(path);
    }

    lib.root_module.addConfigHeader(config_header);
    lib.root_module.addIncludePath(upstream.path("include"));
    lib.root_module.addIncludePath(mbedtls_upstream.path("include"));
    lib.addIncludePath(mbedtls_upstream.path("include"));
    lib.root_module.addCMacro("HAVE_CONFIG_H", "1");
    lib.root_module.addCMacro("LIBSSH2_MBEDTLS", "1");
    lib.addCSourceFiles(.{
        .files = libssh2_src,
        .root = upstream.path(""),
    });
    lib.linkLibrary(mbedtls_dep.artifact("mbedtls"));

    // Ensure mbedtls is built before libssh2
    lib.step.dependOn(&mbedtls_dep.artifact("mbedtls").step);

    b.installArtifact(lib);

    const module = b.addModule("libssh2", .{
        .root_source_file = null,
        .target = target,
        .optimize = optimize,
    });
    module.addIncludePath(upstream.path("include"));
    module.addIncludePath(mbedtls_upstream.path("include"));
}
