#!/usr/bin/env bash
set -uo pipefail

test_status=0
gradle --no-daemon :app:connectedDebugAndroidTest || test_status=$?

# These shell-owned screenshots survive Gradle uninstalling the app after the tests.
# Pull every expected preview even when an assertion fails, and fail CI if any is missing.
mkdir -p build/ui-previews
for preview in 01-login-start 02-login-code 03-library 04-library-selected 05-library-downloaded 06-library-search; do
    adb pull "/sdcard/Download/LikeFolder-ui-previews/$preview.png" "build/ui-previews/$preview.png" || test_status=1
done

exit "$test_status"
