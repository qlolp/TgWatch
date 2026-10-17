#!/usr/bin/env bash
set -euo pipefail
# A dedicated Wear OS3+ emulator/device; ANDROID_SERIAL selects the owned target.
report_dir=${WEAR_SMOKE_REPORT_DIR:-wear-smoke-reports}
mkdir -p "$report_dir"
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$api" =~ ^[0-9]+$ && "$api" -ge 30 ]] || { echo 'Wear smoke requires API30+' >&2; exit 1; }
adb shell pm list features > "$report_dir/features.txt"
rg -q 'feature:android.hardware.type.watch' "$report_dir/features.txt"
if [[ ${WEAR_SMOKE_SKIP_BUILD:-false} != true ]]; then
    bash ./gradlew :wear:assembleDebug :wear:assembleDebugAndroidTest --no-daemon
fi
adb install -r "${WEAR_SMOKE_APK:-wear/build/outputs/apk/debug/wear-debug.apk}"
adb install -r "${WEAR_SMOKE_TEST_APK:-wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk}"
# Wear OS3 userdebug defaults include a kernel buffer that the shell cannot clear.
adb logcat -b main -b system -b crash -c
adb shell input keyevent KEYCODE_WAKEUP
timeout 300 adb shell am instrument -w -r \
    -e class ru.tgwatch.wear.WatchManualTest,ru.tgwatch.wear.WatchSurfacesTest \
    ru.tgwatch.wear.test/androidx.test.runner.AndroidJUnitRunner > "$report_dir/instrumentation.txt" 2>&1
cat "$report_dir/instrumentation.txt"
if rg -q 'INSTRUMENTATION_STATUS_CODE: -|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$report_dir/instrumentation.txt"; then
    echo 'Wear tests failed or skipped' >&2; exit 1
fi
rg -q '^OK \([1-9][0-9]* tests?\)' "$report_dir/instrumentation.txt"
python3 scripts/wear_smoke_report.py "$report_dir" "$api"
adb shell am start -W -n ru.tgwatch.wear/.WatchActivity
adb exec-out screencap -p > "$report_dir/watch.png"
adb logcat -b main -b system -b crash -d -s AndroidRuntime:E > "$report_dir/crashes.txt"
if rg -q 'Process: ru.tgwatch.wear([,: ]|$)' "$report_dir/crashes.txt"; then
    echo 'Wear process crash detected' >&2; exit 1
fi
echo 'Standalone Wear manual/cache/surface smoke passed.'
