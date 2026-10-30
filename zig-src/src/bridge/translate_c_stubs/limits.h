/* Terminates bionic's `#include_next <limits.h>` during translate-c.
 *
 * Zig 0.17's translate-c treats --zig-integration runs as native, so on a
 * macOS host it appends the Xcode SDK include dirs after the NDK sysroot.
 * Without this stub, bionic's limits.h include_next's into the macOS
 * <limits.h>, which #errors on non-Apple targets. Aro's builtin limits.h
 * already supplies the compiler limits bionic was reaching for. */
#pragma once
