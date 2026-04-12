const std = @import("std");
const native_build = @import("src/build.zig");
const ndk = @import("src/ndk.zig");

const build_targets: []const std.Target.Query = &.{
    .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
    //.{ .cpu_arch = .arm, .os_tag = .linux, .abi = .androideabi, .android_api_level = 24 },
    //.{ .cpu_arch = .x86, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
    .{ .cpu_arch = .x86_64, .os_tag = .linux, .abi = .android, .android_api_level = 24 },
};

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const has_android_ndk = b.graph.env_map.get("ANDROID_NDK_HOME") != null or b.graph.env_map.get("ANDROID_NDK_ROOT") != null;
    const ghostty_dep = b.dependency("ghostty", .{
        .target = target,
        .optimize = optimize,
    });

    const ghostty_vt_doc = b.addModule("ghostty-vt", .{
        .root_source_file = b.path("src/ghostty_vt_doc.zig"),
        .target = target,
        .optimize = optimize,
    });
    ghostty_vt_doc.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));

    const ghostty_vt_doc_underscore = b.addModule("ghostty_vt", .{
        .root_source_file = b.path("src/ghostty_vt_doc.zig"),
        .target = target,
        .optimize = optimize,
    });
    ghostty_vt_doc_underscore.addImport("ghostty-vt", ghostty_dep.module("ghostty-vt"));

    const native_step = b.step("native", "Build native JNI library");
    const jni_step = b.step("jni", "Build native library and copy to jniLibs");
    if (has_android_ndk) {
        for (build_targets) |target_query| {
            const resolved_target = b.resolveTargetQuery(target_query);
            const native_lib = native_build.build(b, resolved_target, optimize);
            native_step.dependOn(&native_lib.step);

            const abi_name = ndk.getOutputDir(resolved_target.result) catch unreachable;
            const jni_lib_dir = b.fmt("app/src/main/jniLibs/{s}", .{abi_name});

            const mkdir_jni_libs = b.addSystemCommand(&.{ "mkdir", "-p", jni_lib_dir });
            mkdir_jni_libs.step.dependOn(&native_lib.step);

            const copy_to_jni_libs = b.addSystemCommand(&.{"cp"});
            copy_to_jni_libs.step.dependOn(&mkdir_jni_libs.step);
            copy_to_jni_libs.addFileArg(native_lib.getEmittedBin());
            _ = copy_to_jni_libs.addArg(b.fmt("{s}/libchuchu_jni.so", .{jni_lib_dir}));

            jni_step.dependOn(&copy_to_jni_libs.step);
        }
    }

    const test_step = b.step("test", "Run unit tests");
    const exe_tests = b.addTest(.{
        .root_module = b.createModule(.{
            .root_source_file = b.path("src/main.zig"),
            .target = target,
            .optimize = optimize,
        }),
    });
    test_step.dependOn(&b.addRunArtifact(exe_tests).step);

    const fmt_check = b.addFmt(.{ .paths = &.{ "src", "build.zig", "build.zig.zon" } });
    test_step.dependOn(&fmt_check.step);
}
