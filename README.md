<p align="center">
  <img src="./logo.svg" alt="Chuchu logo" width="180" />
</p>

# Chuchu

Chuchu is an Android SSH client with a Ghostty-powered terminal, a terminal-first Compose UI, and support for both standard SSH and Tailscale SSH workflows.

## Status

Chuchu is in active development. The current branch already includes a usable terminal path, saved host profiles, host key verification, and Android-native UI flows, but some roadmap items are still in progress.

## Current Highlights

- Ghostty-backed terminal rendering through a Zig/JNI bridge
- Native Android app built with Kotlin and Jetpack Compose
- Saved host profiles backed by Room
- Standard SSH and Tailscale SSH transport selection
- Host key verification prompt with local persistence
- IME input, hardware keyboard support, accessory modifiers, scrollback, and pinch-to-zoom terminal sizing
- `xterm-kitty` shell sessions with Kitty image support wired into terminal snapshots

## Stack

- Kotlin + Jetpack Compose for the Android app
- Zig for native build orchestration and JNI/native bridge code
- Ghostty VT for terminal emulation
- `libssh2` + `mbedtls` for the current native SSH path
- Room for local data storage

## Project Layout

```text
.
|- app/          Android application code, Compose UI, Room, ViewModels
|- native/       Zig native bridge and SSH/terminal integration
|- src/          Top-level Zig entrypoints and docs shims
|- .plans/       Project plan and progress tracking
```

## Running It

### Prerequisites

- Android SDK
- Android NDK
- JDK 17+
- Zig 0.15.2
- `adb` for installing/running on a device

If you use the included Nix shell, it sets up most of the Android and Zig tooling for you.

### Build The Native Library

Set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`, then build the JNI library for Android arm64:

```sh
zig build jni -Dtarget=aarch64-linux-android
```

That copies `libchuchu_jni.so` into `app/src/main/jniLibs/arm64-v8a/`.

### Build The App

```sh
./gradlew assembleDebug
```

### Install And Launch

```sh
./gradlew installDebug
adb shell am start -n com.example.chuchu/.MainActivity
```

## What Works Today

- Server list and add/edit server flows
- Password-based SSH sessions
- Tailscale SSH flow using server-side auth policy (`none` auth)
- Host key verification and changed-key warnings
- Terminal resize, scrollback, focus, modifier keys, and paste
- Native terminal color/title/bell updates

## Known Gaps

- Primary target is currently Android arm64
- Native SSH currently supports password auth and Tailscale-style `none` auth; key import/storage is still being built out
- The project is still moving quickly, so the roadmap in `.plans/PLAN.md` remains the best source for upcoming work

## Development Notes

- `build.zig` drives the native build and JNI copy step
- `app/` owns the Android UI, persistence, and app lifecycle
- `.plans/progress.txt` tracks feature-level progress over time

## Inspiration

The project direction is explicitly inspired by VVTerm on iOS, but implemented as a native Android client with a Ghostty-based terminal core.
