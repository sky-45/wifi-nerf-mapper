#!/bin/bash
# ==============================================================================
# Setup & Maintenance script for Wi-Fi Indoor Mapper on Android
# ==============================================================================
set -e

PACKAGE="com.antigravity.wifimapper"

case "$1" in
  setup)
    echo "==> Configuring Android Device via ADB..."
    # 1. Disable Wi-Fi Scan Throttling (Enables continuous ~1 Hz scanning)
    adb shell settings put global wifi_scan_throttle_enabled 0
    echo "✓ Wi-Fi Scan Throttling disabled (wifi_scan_throttle_enabled = 0)"

    # 2. Enable Wi-Fi Verbose Logging
    adb shell cmd wifi set-verbose-logging enabled
    echo "✓ Wi-Fi Verbose Logging enabled"

    # 3. Grant runtime permissions automatically
    echo "==> Granting Runtime Permissions..."
    adb shell pm grant $PACKAGE android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    adb shell pm grant $PACKAGE android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
    adb shell pm grant $PACKAGE android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
    adb shell pm grant $PACKAGE android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true
    adb shell pm grant $PACKAGE android.permission.ACTIVITY_RECOGNITION 2>/dev/null || true
    adb shell pm grant $PACKAGE android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    adb shell pm grant $PACKAGE android.permission.RECORD_AUDIO 2>/dev/null || true
    echo "✓ Permissions granted to $PACKAGE"

    # 4. Check Wi-Fi RTT Hardware Capability
    echo "==> Checking Wi-Fi RTT (802.11mc) support..."
    HAS_RTT=$(adb shell pm list features | grep "android.hardware.wifi.rtt" || true)
    if [ -n "$HAS_RTT" ]; then
      echo "✓ Hardware supports Wi-Fi RTT (802.11mc/az)!"
    else
      echo "! Note: Device does not have dedicated 802.11mc RTT feature. App will use Multi-Band RSSI & One-Sided RTT fallback."
    fi
    ;;

  pull)
    OUT_DIR="./sessions"
    mkdir -p "$OUT_DIR"
    echo "==> Pulling telemetry spool / sessions to $OUT_DIR..."
    adb pull "/sdcard/Android/data/$PACKAGE/files/telemetry_spool" "$OUT_DIR/" 2>/dev/null || \
    adb exec-out run-as $PACKAGE tar -czf - files/telemetry_spool | tar -xzf - -C "$OUT_DIR" 2>/dev/null || \
    echo "No files found or app not run yet."
    echo "✓ Spool sync finished."
    ;;

  build)
    echo "==> Building Debug APK with Gradle..."
    cd "$(dirname "$0")/.."
    ./gradlew assembleDebug
    echo "✓ Built: app/build/outputs/apk/debug/app-debug.apk"
    ;;

  install)
    echo "==> Installing APK to connected device..."
    cd "$(dirname "$0")/.."
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    echo "✓ App installed successfully."
    ;;

  *)
    echo "Usage: $0 {setup|build|install|pull}"
    exit 1
    ;;
esac
