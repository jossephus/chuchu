const std = @import("std");
const builtin = @import("builtin");
const ndk = @import("src/ndk.zig");
const Translator = @import("translate_c").Translator;

const default_build_targets: []const std.Target.Query = &.{
    .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
    .{ .cpu_arch = .arm, .os_tag = .linux, .abi = .androideabi, .android_api_level = 24 },
    .{ .cpu_arch = .x86, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
    .{ .cpu_arch = .x86_64, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
};

fn resolveBuildTargets(b: *std.Build) []const std.Target.Query {
    const maybe_target = b.option(
        []const u8,
        "target",
        "Android target triple (default: all supported ABIs)",
    ) orelse return default_build_targets;

    var query = std.Target.Query.parse(.{ .arch_os_abi = maybe_target }) catch |err| {
        std.debug.panic("invalid -Dtarget '{s}': {s}", .{ maybe_target, @errorName(err) });
    };
    if (query.android_api_level == null) query.android_api_level = 24;

    const targets = b.allocator.alloc(std.Target.Query, 1) catch @panic("OOM");
    targets[0] = query;
    return targets;
}

fn ndkPrebuiltTag() []const u8 {
    const os_part = switch (builtin.os.tag) {
        .macos => "darwin",
        .linux => "linux",
        .windows => "windows",
        else => @panic("Unsupported host OS for Android NDK prebuilt toolchain"),
    };

    // NDK only provides x86_64 prebuilt tools for macOS (works on Apple Silicon via Rosetta 2)
    const arch_part = switch (builtin.cpu.arch) {
        .x86_64 => "x86_64",
        .aarch64 => if (builtin.os.tag == .macos) "x86_64" else "aarch64",
        else => @panic("Unsupported host architecture for Android NDK prebuilt toolchain"),
    };

    return std.fmt.comptimePrint("{s}-{s}", .{ os_part, arch_part });
}

fn resolveNdkHome(b: *std.Build, ndk_root: []const u8) []const u8 {
    if (ndk_root.len == 0) return ndk_root;

    b.graph.poisonCache();
    const io = b.graph.io;
    const cwd = std.Io.Dir.cwd();

    const toolchains_path = b.pathJoin(&.{ ndk_root, "toolchains", "llvm" });
    cwd.access(io, toolchains_path, .{}) catch {
        var dir = cwd.openDir(io, ndk_root, .{ .iterate = true }) catch return ndk_root;
        defer dir.close(io);

        var iter = dir.iterate();
        while (iter.next(io) catch null) |entry| {
            if (entry.kind != .directory) continue;
            return b.pathJoin(&.{ ndk_root, entry.name });
        }
        return ndk_root;
    };

    return ndk_root;
}

fn translateBridgeHeaders(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
    libc_file: std.Build.LazyPath,
    libssh2_include: std.Build.LazyPath,
) *std.Build.Module {
    const translator: Translator = .init(b.dependency("translate_c", .{}), .{
        .c_source_file = b.path("src/bridge/c.h"),
        .target = target,
        .optimize = optimize,
        .libc_file = libc_file,
    });
    translator.addIncludePath(b.path("src/bridge"));
    translator.addIncludePath(libssh2_include);
    translator.defineCMacro("BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD", null);
    return translator.mod;
}

fn buildNativeLibrary(
    b: *std.Build,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) *std.Build.Step.Compile {
    const ghostty_dep = b.dependency("ghostty", .{
        .target = target,
        .optimize = optimize,
        .simd = false,
    });
    const zigimg_dep = b.dependency("zigimg", .{
        .target = target,
        .optimize = optimize,
    });
    const mosh_dep = b.dependency("mosh", .{
        .target = target,
        .optimize = optimize,
    });

    const ndk_root = b.graph.environ_map.get("ANDROID_NDK_HOME") orelse b.graph.environ_map.get("ANDROID_NDK_ROOT").?;
    const ndk_home = resolveNdkHome(b, ndk_root);

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

    // Build libssh2 (configured to use openssl-zig)
    const libssh2_dep = b.dependency("libssh2", .{
        .target = target,
        .optimize = optimize,
        .libc_file = libc_config,
    });

    const libssh2 = libssh2_dep.artifact("ssh2");

    // Get upstream libssh2 for include paths
    const libssh2_upstream = libssh2_dep.builder.dependency("libssh2_upstream", .{
        .target = target,
        .optimize = optimize,
    });

    const root_module = b.createModule(.{
        .root_source_file = b.path("src/bridge/zignal_png.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = true,
        .unwind_tables = .none,
        .omit_frame_pointer = true,
    });
    root_module.addIncludePath(b.path("src/bridge"));
    root_module.addIncludePath(libssh2_upstream.path("include"));
    root_module.addImport("c", translateBridgeHeaders(b, target, optimize, libc_config, libssh2_upstream.path("include")));
    root_module.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));
    root_module.addImport("zigimg", zigimg_dep.module("zigimg"));

    root_module.addImport("mosh", mosh_dep.module("mosh"));

    const lib = b.addLibrary(.{
        .linkage = .dynamic,
        .name = "chuchu_jni",
        .root_module = root_module,
    });
    lib.link_function_sections = true;
    lib.link_data_sections = true;
    lib.link_gc_sections = true;
    lib.link_eh_frame_hdr = false;
    lib.lto = .thin;
    lib.use_llvm = true;
    root_module.strip = true;
    root_module.unwind_tables = .none;
    root_module.omit_frame_pointer = true;

    root_module.addIncludePath(.{ .cwd_relative = include_dir });
    root_module.addIncludePath(.{ .cwd_relative = target_include_dir });

    const api_dir = b.fmt("{d}", .{android_api_version});
    const lib_dir = b.pathJoin(&.{ ndk_sysroot, "usr", "lib", android_target, api_dir });
    root_module.addLibraryPath(.{ .cwd_relative = lib_dir });

    lib.setLibCFile(libc_config);
    root_module.linkLibrary(libssh2);
    root_module.linkSystemLibrary("log", .{});
    root_module.linkSystemLibrary("z", .{});
    lib.version_script = b.path("src/bridge/version-script.map");

    b.installArtifact(lib);
    return lib;
}

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{});
    const build_targets = resolveBuildTargets(b);
    const has_android_ndk = b.graph.environ_map.get("ANDROID_NDK_HOME") != null or b.graph.environ_map.get("ANDROID_NDK_ROOT") != null;

    const native_step = b.step("native", "Build native JNI library");
    const jni_step = b.step("jni", "Build native library and copy to jniLibs");

    if (has_android_ndk) {
        for (build_targets) |target_query| {
            const resolved_target = b.resolveTargetQuery(target_query);
            const native_lib = buildNativeLibrary(b, resolved_target, optimize);
            native_step.dependOn(&native_lib.step);

            const abi_name = ndk.getOutputDir(resolved_target.result) catch unreachable;
            const jni_lib_dir = b.fmt("../android/app/src/main/jniLibs/{s}", .{abi_name});

            const mkdir_jni_libs = b.addSystemCommand(&.{ "mkdir", "-p", jni_lib_dir });
            mkdir_jni_libs.step.dependOn(&native_lib.step);

            const copy_to_jni_libs = b.addSystemCommand(&.{"cp"});
            copy_to_jni_libs.step.dependOn(&mkdir_jni_libs.step);
            copy_to_jni_libs.addFileArg(native_lib.getEmittedBin());
            _ = copy_to_jni_libs.addArg(b.fmt("{s}/libchuchu_jni.so", .{jni_lib_dir}));

            jni_step.dependOn(&copy_to_jni_libs.step);
        }
    }

    // Test: try building openssl for aarch64 android
    const openssl_test_step = b.step("test-openssl", "Test building OpenSSL for Android aarch64");
    if (has_android_ndk) {
        const test_target = b.resolveTargetQuery(.{
            .cpu_arch = .aarch64,
            .os_tag = .linux,
            .abi = .android,
            .android_api_level = 24,
        });
        const openssl_dep = b.dependency("openssl", .{
            .target = test_target,
            .optimize = .ReleaseSmall,
        });
        const crypto = openssl_dep.artifact("crypto");
        const ssl = openssl_dep.artifact("ssl");
        openssl_test_step.dependOn(&crypto.step);
        openssl_test_step.dependOn(&ssl.step);
    }

    const fmt_check = b.addFmt(.{ .paths = &.{ b.path("src"), b.path("build.zig"), b.path("build.zig.zon") } });
    const fmt_step = b.step("fmt", "Format Zig files");
    fmt_step.dependOn(&fmt_check.step);
}
