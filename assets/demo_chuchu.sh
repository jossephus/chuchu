#!/bin/bash
#
# Chuchu SSH App Demo Script
# This script automates opening Chuchu, connecting to the first server,
# running fastfetch, and recording the entire process.
#
# Usage: ./demo_chuchu.sh [device_id]
#   device_id: Optional. If multiple devices are connected, specify which one to use.
#

set -e

# Configuration
PACKAGE_NAME="com.jossephus.chuchu"
# Use relative activity name (with leading dot) for am start
MAIN_ACTIVITY=".MainActivity"
OUTPUT_VIDEO="chuchu_demo_$(date +%Y%m%d_%H%M%S).mp4"
DEVICE_ID=""
ADB="adb"

# Function to check if a device is connected
check_device() {
    local device_count
    device_count=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l | tr -d ' ')
    
    if [ "$device_count" -eq 0 ]; then
        echo "Error: No device connected. Please connect an Android device."
        exit 1
    fi
    
    if [ -n "$DEVICE_ID" ]; then
        if ! adb devices | grep -q "$DEVICE_ID"; then
            echo "Error: Device $DEVICE_ID not found."
            echo "Available devices:"
            adb devices | grep -v "List of devices"
            exit 1
        fi
    fi
}

# Function to get screen resolution
get_screen_size() {
    local size
    size=$($ADB shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1)
    echo "$size"
}

# Calculate tap coordinates based on screen size
# Based on ServerListScreen.kt layout analysis
# For 1080x2340 screen, Connect button is around (900, 600-700)
calculate_coordinates() {
    local screen_size=$1
    local width height
    
    width=$(echo "$screen_size" | cut -d'x' -f1)
    height=$(echo "$screen_size" | cut -d'x' -f2)
    
    # Coordinates for "Connect" button on first server card
    # Button is at bottom-right of first HostCard
    # The card is in a LazyColumn after: Title + Search + Active Connections
    # For 1080 width: button is around x=900 (right side of card)
    # For 2340 height: button is around y=600-700 (first card area)
    CONNECT_X=$((width * 83 / 100))  # 83% from left (right side of card)
    CONNECT_Y=$((height * 30 / 100)) # 30% from top (first card area)
    
    echo "${CONNECT_X},${CONNECT_Y}"
}

# Take a screenshot for debugging
take_screenshot() {
    local filename=$1
    $ADB exec-out screencap -p > "$filename"
    echo "Screenshot saved: $filename"
}

# Find Connect button using UI Automator (more reliable)
# This looks for elements with testTag or contentDescription containing "host_connect"
find_connect_button() {
    $ADB shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || return 1

    local xml
    xml=$($ADB shell cat /sdcard/window_dump.xml 2>/dev/null)

    # Find the first element whose content-desc starts with "Connect to"
    local node
    node=$(echo "$xml" | grep -oE '[^<]*content-desc="Connect to [^"]*"[^>]*' | head -1)

    if [ -z "$node" ]; then
        return 1
    fi

    # Extract bounds e.g. [279,978][511,1113]
    local bounds
    bounds=$(echo "$node" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')

    if [ -z "$bounds" ]; then
        return 1
    fi

    local x1 y1 x2 y2
    x1=$(echo "$bounds" | cut -d'[' -f2 | cut -d',' -f1)
    y1=$(echo "$bounds" | cut -d',' -f2 | cut -d']' -f1)
    x2=$(echo "$bounds" | cut -d'[' -f3 | cut -d',' -f1)
    y2=$(echo "$bounds" | cut -d',' -f3 | cut -d']' -f1)

    if [ -z "$x1" ] || [ -z "$y1" ] || [ -z "$x2" ] || [ -z "$y2" ]; then
        return 1
    fi

    local cx=$(((x1 + x2) / 2))
    local cy=$(((y1 + y2) / 2))

    if [ "$cx" -gt 0 ] && [ "$cy" -gt 0 ]; then
        echo "${cx},${cy}"
        return 0
    fi
    return 1
}

# Send a command to the terminal using hardware keyevents.
# TerminalInputView.onKeyDown() intercepts these before EditText processes them,
# converting each key into terminal text via onTerminalText.
send_terminal_command() {
    local command_text=$1
    local ch upper keycode i

    for ((i = 0; i < ${#command_text}; i++)); do
        ch=${command_text:$i:1}
        case "$ch" in
            [a-z])
                upper=$(printf '%s' "$ch" | tr '[:lower:]' '[:upper:]')
                keycode="KEYCODE_$upper"
                ;;
            [A-Z]) keycode="KEYCODE_$ch" ;;
            [0-9]) keycode="KEYCODE_$ch" ;;
            " ")   keycode="KEYCODE_SPACE" ;;
            "-")   keycode="KEYCODE_MINUS" ;;
            "_")   keycode="KEYCODE_MINUS" ;;
            ".")   keycode="KEYCODE_PERIOD" ;;
            "/")   keycode="KEYCODE_SLASH" ;;
            "?")   keycode="KEYCODE_SLASH" ;;
            *)     keycode="" ;;
        esac

        if [ -n "$keycode" ]; then
            if [ "$ch" = "?" ]; then
                $ADB shell input text '?'
            else
                $ADB shell input keyevent "$keycode"
            fi
            sleep 0.01
        fi
    done

    sleep 0.3
    $ADB shell input keyevent KEYCODE_ENTER
}

# Main execution
main() {
    echo "=== Chuchu SSH Demo Script ==="
    echo ""
    
    # Check for connected device
    check_device
    
    # Get screen info
    SCREEN_SIZE=$(get_screen_size)
    echo "Screen size: $SCREEN_SIZE"
    
    COORDS=$(calculate_coordinates "$SCREEN_SIZE")
    CONNECT_X=$(echo "$COORDS" | cut -d',' -f1)
    CONNECT_Y=$(echo "$COORDS" | cut -d',' -f2)
    
    echo "Using Connect button coordinates: ($CONNECT_X, $CONNECT_Y)"
    echo "Note: If the tap doesn't hit the button, adjust CONNECT_X and CONNECT_Y in calculate_coordinates()"
    echo ""
    
    # Stop any existing app instance
    echo "[1/7] Stopping existing app instance..."
    $ADB shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
    sleep 1
    
    # Start screen recording to device first (more reliable than exec-out)
    echo "[2/7] Starting screen recording..."
    $ADB shell screenrecord --time-limit=120 --bit-rate=4M /sdcard/demo_recording.mp4 &
    RECORD_PID=$!

    # Give screenrecord a moment to start
    sleep 2

    # Launch the app
    echo "[3/7] Launching Chuchu app..."
    $ADB shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY"

    # Wait for app to load and show server list
    echo "[4/7] Waiting for server list to load..."
    sleep 1

    # Try to find Connect button using UI Automator, fallback to calculated coordinates
    echo "[5/7] Finding Connect button via UI Automator..."
    COORDS_UIA=$(find_connect_button 2>/dev/null)
    if [ -n "$COORDS_UIA" ]; then
        echo "✓ Found Connect button via UI Automator at: $COORDS_UIA"
        CONNECT_X=$(echo "$COORDS_UIA" | cut -d',' -f1)
        CONNECT_Y=$(echo "$COORDS_UIA" | cut -d',' -f2)
    else
        echo "✗ UI Automator couldn't find the button (test tags not found)"
        echo "  → This means the app hasn't been rebuilt with the new test tags yet"
        echo "  → Falling back to calculated coordinates: ($CONNECT_X, $CONNECT_Y)"
        echo ""
        echo "  To use automatic detection, rebuild the app with:"
        echo "    ./gradlew :app:installDebug"
    fi

    # In debug mode, take a screenshot and pause
    if [ "$DEBUG_MODE" = true ]; then
        take_screenshot "screenshot_before_tap.png"
        echo "Screenshot saved as screenshot_before_tap.png"
        echo "Check the screenshot and press Enter to continue with the tap..."
        read
    fi

    # Tap on "Connect" button of the first server
    echo "Tapping at coordinates: ($CONNECT_X, $CONNECT_Y)"
    $ADB shell input tap "$CONNECT_X" "$CONNECT_Y"

    # Wait for terminal connection to establish
    echo "[6/7] Waiting for terminal connection..."

    # Focus terminal input by tapping the terminal canvas area.
    # TerminalCanvas onTap calls requestInputFocus() which focuses the hidden
    # TerminalInputView and opens the keyboard — no second tap needed.
    echo "Focusing terminal..."
    SCREEN_SIZE=$(get_screen_size)
    WIDTH=$(echo "$SCREEN_SIZE" | cut -d'x' -f1)
    HEIGHT=$(echo "$SCREEN_SIZE" | cut -d'x' -f2)
    TERM_X=$((WIDTH / 2))
    TERM_Y=$((HEIGHT / 2))
    $ADB shell input tap "$TERM_X" "$TERM_Y"
    sleep 2

    # Type and run command with fallback for terminals that ignore `input text`
    echo "[7/7] Typing 'fastfetch' command..."
    send_terminal_command "fastfetch"

    # Wait for fastfetch output to display
    echo "Waiting for fastfetch to complete..."
    sleep 1

    # Run htop
    echo "[8/10] Running htop..."
    send_terminal_command "htop"
    sleep 1

    # Dismiss keyboard with back button before interacting with htop
    $ADB shell input keyevent KEYCODE_BACK
    sleep 2

    # Exit htop
    echo "[9/10] Exiting htop..."
    $ADB shell input keyevent KEYCODE_Q
    sleep 1

    # Display image with kitty icat
    echo "[10/10] Displaying image with kitty icat..."
    send_terminal_command "kitty icat gene.jpg"
    sleep 2

    # Navigate to chuchu directory
    echo "[11/13] Changing to chuchu directory..."
    send_terminal_command "cd chuchu"
    sleep 1

    # Launch opencode
    echo "[12/13] Launching opencode..."
    send_terminal_command "opencode"
    sleep 4

    # Ask opencode about the codebase
    echo "[13/13] Typing prompt in opencode..."
    send_terminal_command "can you tell me what this codebase is"
    sleep 45

    # Stop screen recording
    echo "Stopping screen recording..."
    kill $RECORD_PID 2>/dev/null || true
    sleep 2

    # Pull the video file
    echo "Pulling video from device..."
    $ADB pull /sdcard/demo_recording.mp4 "$OUTPUT_VIDEO"

    # Clean up device
    $ADB shell rm /sdcard/demo_recording.mp4

    echo ""
    echo "=== Demo Complete! ==="
    echo "Video saved as: $OUTPUT_VIDEO"
    echo ""
    echo "Note: Video is saved on your laptop"
}

# Calibration helper - helps find correct coordinates
calibrate() {
    echo "=== Coordinate Calibration Mode ==="
    echo ""
    echo "This will help you find the correct tap coordinates for the Connect button."
    echo ""
    
    check_device

    # Launch the app
    echo "Launching Chuchu app..."
    $ADB shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY"
    sleep 3
    
    # Take initial screenshot
    take_screenshot "calibration_start.png"
    
    echo ""
    echo "A screenshot has been saved as 'calibration_start.png'"
    echo "Open it to see the current screen."
    echo ""
    
    SCREEN_SIZE=$(get_screen_size)
    echo "Screen size: $SCREEN_SIZE"
    
    # Calculate initial guess
    COORDS=$(calculate_coordinates "$SCREEN_SIZE")
    GUESS_X=$(echo "$COORDS" | cut -d',' -f1)
    GUESS_Y=$(echo "$COORDS" | cut -d',' -f2)
    
    echo ""
    echo "Initial guess: ($GUESS_X, $GUESS_Y)"
    echo ""
    echo "Let's test some coordinates. The script will tap and you check if it hits the button."
    echo ""
    
    while true; do
        echo -n "Enter X coordinate (or 'q' to quit): "
        read x
        if [ "$x" = "q" ]; then
            break
        fi
        
        echo -n "Enter Y coordinate: "
        read y
        
        echo "Tapping at ($x, $y)..."
        $ADB shell input tap "$x" "$y"
        
        sleep 1
        take_screenshot "calibration_tap_${x}_${y}.png"
        echo "Screenshot saved: calibration_tap_${x}_${y}.png"
        echo ""
    done
    
    echo ""
    echo "Calibration complete!"
    echo "Update the calculate_coordinates() function in this script with your working coordinates."
}

# Dump UI hierarchy for debugging
dump_ui() {
    echo "Dumping UI hierarchy..."
    $ADB shell uiautomator dump /sdcard/window_dump.xml
    $ADB pull /sdcard/window_dump.xml ./ui_dump.xml
    echo "UI hierarchy saved to: ./ui_dump.xml"
    echo ""
    echo "Searching for 'Connect' elements:"
    grep -i "connect" ./ui_dump.xml | head -5 || echo "No 'connect' found in dump"
    echo ""
    echo "Searching for 'host_connect':"
    grep -i "host_connect" ./ui_dump.xml | head -5 || echo "No 'host_connect' found in dump"
}

# Show help
show_help() {
    cat << 'EOF'
Chuchu SSH Demo Script

This script automates:
1. Opening the Chuchu SSH app
2. Connecting to the first server in the list
3. Running 'fastfetch' command
4. Recording the entire process

Usage:
  ./demo_chuchu.sh [OPTIONS] [DEVICE_ID]

Options:
  -h, --help      Show this help message
  --interactive   Interactive mode - prompts for coordinates
  --debug         Debug mode - takes screenshot before tap and pauses
  --calibrate     Calibration mode - find correct coordinates interactively
  --dump-ui       Dump UI hierarchy for debugging

DEVICE_ID:
  Optional. Specify the device serial when multiple devices are connected.

Requirements:
  - Android device with USB debugging enabled
  - adb (Android Debug Bridge) installed and in PATH
  - Chuchu app installed on the device
  - At least one server configured in Chuchu

Coordinate Calibration:
  The script uses UI Automator to find the Connect button automatically.
  If that fails, it falls back to calculated coordinates based on screen size.
  For a 1080x2340 screen, the Connect button is typically around (900, 700).

  Use --debug to take a screenshot before tapping to verify coordinates.
  Use --calibrate to interactively find the right coordinates.
  Use --dump-ui to see what UI Automator can see.

Examples:
  ./demo_chuchu.sh                    # Use default device
  ./demo_chuchu.sh emulator-5554      # Use specific emulator
  ./demo_chuchu.sh 192.168.1.5:5555   # Use device over network
EOF
}

# Parse command line arguments
INTERACTIVE_MODE=false
DEBUG_MODE=false

while [ $# -gt 0 ]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        --interactive)
            INTERACTIVE_MODE=true
            shift
            ;;
        --debug)
            DEBUG_MODE=true
            shift
            ;;
        --calibrate)
            calibrate
            exit 0
            ;;
        --dump-ui)
            check_device
            $ADB shell am start -n "$PACKAGE_NAME/$MAIN_ACTIVITY"
            sleep 3
            dump_ui
            exit 0
            ;;
        -*)
            echo "Unknown option: $1"
            show_help
            exit 1
            ;;
        *)
            DEVICE_ID="$1"
            shift
            ;;
    esac
done

# Rebuild ADB command after parsing
if [ -n "$DEVICE_ID" ]; then
    ADB="adb -s $DEVICE_ID"
fi

# Run main function
main
