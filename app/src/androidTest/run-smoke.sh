#!/usr/bin/env bash
set -euo pipefail

# Compilation can outlast the emulator's default screen timeout. Keep it awake and
# dismiss the default emulator keyguard before any ActivityScenario is launched.
adb wait-for-device
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

test_status=0
gradle --no-daemon :app:connectedDebugAndroidTest || test_status=$?

# These shell-owned screenshots survive Gradle uninstalling the app after the tests.
# Pull every expected preview even when an assertion fails, and fail CI if any is missing.
mkdir -p build/ui-previews
for preview in 01-login-start 02-login-code 03-library 04-library-selected 05-library-downloaded 06-library-search; do
    adb pull "/sdcard/Download/LikeFolder-ui-previews/$preview.png" "build/ui-previews/$preview.png" || test_status=1
done

if [[ "$test_status" -ne 0 ]]; then
    # Include precondition-failure screenshots, not only the six expected previews.
    adb pull /sdcard/Download/LikeFolder-ui-previews build/ui-previews/diagnostics || true
    adb shell dumpsys power > build/ui-previews/device-power.txt || true
    adb shell dumpsys window > build/ui-previews/device-windows.txt || true
fi

exit "$test_status"
