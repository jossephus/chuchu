#!/bin/bash
#
# Chuchu SSH App Demo Script
#
# Assumes:
#   - The Chuchu app is already open on the device, showing the server list
#     with (at least) the demo host visible.
#   - You have already started scrcpy (with recording) in another terminal,
#     e.g.:
#       scrcpy --always-on-top --render-driver=opengl --record chuchu_demo.mp4
#     This script does NOT start or stop scrcpy.
#   - `adb` is installed and on PATH.
#
# Usage:
#   ./demo_chuchu.sh [device_id]
#
set -e

# Configuration
DEVICE_ID=""
ADB="adb"

# ----------------------------------------------------------------------------
# Device helpers
# ----------------------------------------------------------------------------
check_device() {
    local device_count
    device_count=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l | tr -d ' ')

    if [ "$device_count" -eq 0 ]; then
        echo "Error: No device connected. Please connect an Android device."
        exit 1
    fi

    if [ -n "$DEVICE_ID" ] && ! adb devices | grep -q "$DEVICE_ID"; then
        echo "Error: Device $DEVICE_ID not found."
        adb devices
        exit 1
    fi
}

# ----------------------------------------------------------------------------
# UI Automator helpers
#
# We rely on a stable contentDescription on the Connect button
# (see ServerListScreen.kt -> "chuchu_connect_button"). This removes the need
# to compute screen coordinates for the Connect tap.
# ----------------------------------------------------------------------------
tap_by_content_desc() {
    local desc=$1
    local attempts=${2:-10}

    local i
    for ((i = 0; i < attempts; i++)); do
        $ADB shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
        local xml
        xml=$($ADB shell cat /sdcard/window_dump.xml 2>/dev/null)

        local node
        node=$(echo "$xml" | grep -oE "[^<]*content-desc=\"$desc\"[^>]*" | head -1)
        if [ -n "$node" ]; then
            local bounds
            bounds=$(echo "$node" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
            if [ -n "$bounds" ]; then
                local x1 y1 x2 y2
                x1=$(echo "$bounds" | cut -d'[' -f2 | cut -d',' -f1)
                y1=$(echo "$bounds" | cut -d',' -f2 | cut -d']' -f1)
                x2=$(echo "$bounds" | cut -d'[' -f3 | cut -d',' -f1)
                y2=$(echo "$bounds" | cut -d',' -f3 | cut -d']' -f1)
                local cx=$(((x1 + x2) / 2))
                local cy=$(((y1 + y2) / 2))
                echo "  tap '$desc' at ($cx, $cy)"
                $ADB shell input tap "$cx" "$cy"
                return 0
            fi
        fi
        sleep 0.5
    done

    echo "Error: could not find element with content-desc='$desc'."
    echo "Make sure the app is rebuilt with the latest ServerListScreen.kt."
    return 1
}

# ----------------------------------------------------------------------------
# Terminal input
#
# We MUST route text through InputConnection.commitText() on the focused
# TerminalInputView (an EditText). If we send letters via `adb shell input
# keyevent KEYCODE_C` they get delivered as raw key events to the window and
# Android's focus search jumps focus to the next focusable view whose label
# starts with that letter — e.g. 'c' lands on the "Cmd" button in the
# KeyboardAccessoryBar, which steals/transforms our text.
#
# `adb shell input text "..."` reaches InputConnection.commitText only if:
#   - the TerminalInputView still has focus (don't dismiss the IME), and
#   - the string is properly quoted so the device-side shell passes it as a
#     single argument to `input` (otherwise `input` falls back to per-keycode
#     dispatch for unquoted/space-split tokens).
#
# We wrap the whole `input text 'foo bar'` invocation in double quotes for
# `adb shell` and use single quotes around the payload for the device shell.
# ----------------------------------------------------------------------------
send_terminal_command() {
    local command_text=$1
    # Single-quote-escape any single quotes in the payload.
    local escaped=${command_text//\'/\'\\\'\'}
    $ADB shell "input text '$escaped'"
    sleep 0.2
    $ADB shell input keyevent KEYCODE_ENTER
}

# ----------------------------------------------------------------------------
# Main
#
# NOTE: scrcpy is started/stopped by the user in a separate terminal. This
# script only drives the UI via adb.
# ----------------------------------------------------------------------------
main() {
    echo "=== Chuchu SSH Demo Script ==="
    echo "(Make sure scrcpy is already running in another terminal.)"

    check_device

    # 1. Tap Connect on the (only) host card via stable contentDescription.
    echo "[1/10] Tapping Connect button..."
    tap_by_content_desc "chuchu_connect_button"

    # 2. Wait for the terminal screen to appear.
    echo "[2/10] Waiting for terminal connection..."
    sleep 4

    # 3. Focus the terminal.
    #
    # NOTE: TerminalCanvas focus is handled by a Compose pointerInput, which is
    # NOT exposed to UI Automator, so this is the one place we still need a
    # coordinate-based tap. We tap the screen center, which is always inside the
    # terminal canvas.
    echo "[3/10] Focusing terminal (center-screen tap)..."
    SCREEN_SIZE=$($ADB shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1)
    WIDTH=$(echo "$SCREEN_SIZE" | cut -d'x' -f1)
    HEIGHT=$(echo "$SCREEN_SIZE" | cut -d'x' -f2)
    $ADB shell input tap $((WIDTH / 2)) $((HEIGHT / 2))
    sleep 1

    # NOTE: We intentionally DO NOT dismiss the IME here. Doing so removes
    # focus from TerminalInputView, which breaks the `input text` ->
    # InputConnection.commitText routing — `input text` then degrades to
    # per-keycode key events, and 'c' starts shifting focus to the "Cmd"
    # button on the accessory bar (Android focus-search by first letter).

    # 5. cd chuchu
    echo "[4/10] cd chuchu..."
    send_terminal_command "cd chuchu"
    sleep 1

    # 6. ls
    echo "[5/10] ls..."
    send_terminal_command "ls"
    sleep 1

    # 7. fastfetch
    echo "[6/10] fastfetch..."
    send_terminal_command "fastfetch"
    sleep 2

    # 8. clear
    echo "[7/10] clear..."
    send_terminal_command "clear"
    sleep 1

    # 9. htop, exit with q. The IME/accessory bar was already dismissed
    # after the focus tap, so KEYCODE_Q goes straight to htop.
    echo "[8/10] htop..."
    send_terminal_command "htop"
    sleep 2
    echo "      quitting htop with 'q'..."
    $ADB shell input keyevent KEYCODE_Q
    sleep 1

    # 10. kitty icat gene.jpg
    echo "[9/10] kitty icat gene.jpg..."
    send_terminal_command "kitty icat gene.jpg"
    sleep 2

    # 11. opencode (final command — script ends here, recording continues
    # until the user manually stops scrcpy).
    echo "[10/10] opencode..."
    send_terminal_command "opencode"

    echo ""
    echo "=== Demo commands sent. Stop scrcpy in the other terminal when you're done. ==="
}

show_help() {
    cat << 'EOF'
Chuchu SSH Demo Script

Prereqs:
  - Chuchu app is already OPEN on the device, on the server list screen,
    with the demo host present.
  - scrcpy is already running in a SEPARATE terminal (this script does not
    start or stop it), e.g.:
      scrcpy --always-on-top --render-driver=opengl --record demo.mp4
  - `adb` installed and on PATH.

Usage:
  ./demo_chuchu.sh [DEVICE_ID]

DEVICE_ID:
  Optional. Specify the device serial when multiple devices are connected.
EOF
}

# Parse args
while [ $# -gt 0 ]; do
    case $1 in
        -h|--help) show_help; exit 0 ;;
        -*)        echo "Unknown option: $1"; show_help; exit 1 ;;
        *)         DEVICE_ID="$1"; shift ;;
    esac
done

if [ -n "$DEVICE_ID" ]; then
    ADB="adb -s $DEVICE_ID"
fi

main
