#!/usr/bin/env bash
set -uo pipefail

test_status=0
gradle --no-daemon :app:connectedDebugAndroidTest || test_status=$?

# Preserve screenshots even if a later assertion fails.
mkdir -p build/ui-previews
adb pull /sdcard/Android/data/com.likefolder.app/files/ui-previews build/ui-previews || true

exit "$test_status"
