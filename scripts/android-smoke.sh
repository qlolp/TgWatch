#!/usr/bin/env bash
# Dedicated disposable emulator only: changes PIN/network and reboots.
set -Eeuo pipefail
smoke_failure() {
  local status=$1 line=$2
  # Numeric location/status only: BASH_COMMAND can contain credentials or tokens.
  printf '::error file=scripts/android-smoke.sh,line=%s,title=Android smoke command failed::Smoke failed at line %s with exit status %s\n' "$line" "$line" "$status" >&2
  exit "$status"
}
trap 'smoke_failure "$?" "$LINENO"' ERR
trap 'adb logcat -d >> smoke-logcat.txt 2>/dev/null || true' EXIT
: > smoke-logcat.txt
check_crashes() {
  adb logcat -d >> smoke-logcat.txt
  if grep -A5 'FATAL EXCEPTION' smoke-logcat.txt | grep -q 'ru.tgwatch'; then
    echo 'TG Watch crashed during smoke test' >&2; exit 1
  fi
  # Retain logs: some emulator builds cannot clear them reliably.
}
last_check() {
  adb shell cat /data/user_de/0/ru.tgwatch/shared_prefs/tgwatch.xml 2>/dev/null |
    sed -n 's/.*name="last_checked" value="\([0-9]*\)".*/\1/p' | tr -d '\r'
}
await_observation() {
  local after=$1
  shift
  python3 scripts/target-snapshot.py await --after "$after" --foreground \
    --timeout "${TGWATCH_OBSERVATION_TIMEOUT:-90}" "$@"
}
assert_legacy_observation() {
  python3 scripts/target-snapshot.py capture --out app/build/reports/androidTests/target-snapshot.json
  python3 scripts/target-snapshot.py contains app/build/reports/androidTests/target-snapshot.json --at "$((minute * 60000))"
}
# The always-run reporter must never mistake a previous run for fresh results.
rm -rf -- app/build/outputs/androidTest-results/connected
bash ./gradlew :app:connectedDebugAndroidTest :app:assembleDebug --no-daemon --stacktrace
python3 scripts/adb-readiness.py
mkdir -p app/build/reports/androidTests/ui-screenshots
adb pull /data/local/tmp/tgwatch-settings.png app/build/reports/androidTests/ui-screenshots/settings.png
# Runtime permission revocation kills the app; exercise denial in a fresh instrumentation.
api_level=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$api_level" -ge 33 ]]; then
  permission_log=app/build/reports/androidTests/notification-permission-cycle.txt
  adb shell am force-stop ru.tgwatch
  adb shell pm revoke ru.tgwatch android.permission.POST_NOTIFICATIONS
  adb shell pm set-permission-flags ru.tgwatch android.permission.POST_NOTIFICATIONS user-set user-fixed
  permission_status=0
  adb shell am instrument -w -r -e class \
    'ru.tgwatch.EventNotificationTest#disabledAppNotificationsReportNoSummaryDelivery,ru.tgwatch.OnboardingFlowTest#permanentlyDeniedNotificationActionReturnsFromSettings' \
    ru.tgwatch.test/androidx.test.runner.AndroidJUnitRunner > "$permission_log" || permission_status=$?
  adb shell pm clear-permission-flags ru.tgwatch android.permission.POST_NOTIFICATIONS user-set user-fixed
  adb shell pm grant ru.tgwatch android.permission.POST_NOTIFICATIONS
  python3 - "$permission_log" "$permission_status" <<'PY_PERMISSION'
import pathlib, sys
lines = pathlib.Path(sys.argv[1]).read_text().splitlines()
codes = [int(line.split(":", 1)[1]) for line in lines if line.startswith("INSTRUMENTATION_STATUS_CODE:")]
if sys.argv[2] != "0" or "OK (2 tests)" not in [line.strip() for line in lines]:
    raise SystemExit("Permission cycle did not finish")
if codes.count(0) != 2 or any(code not in (0, 1) for code in codes):
    raise SystemExit("Permission tests failed or were skipped")
print("Notification permission cycle: 2 passed, 0 failed/skipped")
PY_PERMISSION
fi
# A genuine 1.6 -> 1.7 data-format upgrade using one test signing identity.
# This does not claim compatibility with an unrecoverable old CI debug key.
legacy_dir=$(mktemp -d)
git archive fef8aef77da5a8b5d2363ef2167deab3a3d4d4d2 | tar -x -C "$legacy_dir"
# Verify the immutable legacy Gradle distribution before it executes.
# This URL uses the Gradle 8.10.2 checksum reviewed when it was the project wrapper (up to 1.17.1).
python3 - "$legacy_dir/gradle/wrapper/gradle-wrapper.properties" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
content = path.read_text()
entries = [line.strip().split('=', 1) for line in content.splitlines()
           if line.strip() and not line.lstrip().startswith('#') and '=' in line]
urls = [value for key, value in entries if key == 'distributionUrl']
expected_url = r'https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip'
expected_hash = '31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26'
hashes = [value for key, value in entries if key == 'distributionSha256Sum']
if urls != [expected_url] or (hashes and hashes != [expected_hash]):
    raise ValueError('Immutable legacy Gradle distribution does not match its trusted pin')
if not hashes:
    path.write_text(content + '\ndistributionSha256Sum=' + expected_hash + '\n')
PY
(cd "$legacy_dir" && bash ./gradlew assembleDebug --no-daemon)
if adb shell pm path ru.tgwatch | grep -q '^package:'; then adb uninstall ru.tgwatch; fi
adb install -g "$legacy_dir/app/build/outputs/apk/debug/app-debug.apk"
python3 scripts/adb-readiness.py
adb shell am start -W -n ru.tgwatch/.MainActivity
adb shell am force-stop ru.tgwatch
minute=$(( $(date +%s) / 60 - 3 ))
printf '%s,3,0,0,150\n' "$minute" > legacy-history.csv
adb push legacy-history.csv /data/local/tmp/tgwatch-history.csv
adb shell run-as ru.tgwatch cp /data/local/tmp/tgwatch-history.csv files/history.csv
adb shell run-as ru.tgwatch sh -c "'echo preserved > files/upgrade-sentinel'"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n ru.tgwatch/.MainActivity
await_observation 0
adb shell run-as ru.tgwatch cat files/upgrade-sentinel | tr -d '\r' | grep -qx preserved
assert_legacy_observation
# Connectivity loss and recovery must produce new persisted observations.
before=$(last_check)
adb shell svc wifi disable
adb shell svc data disable
sleep 6
await_observation "$before" --status NO_NETWORK
before=$(last_check)
adb shell svc wifi enable
adb shell svc data enable
# A completed, measured UNKNOWN is valid when this network blocks all probes.
# The reader still requires a fresh, positive-width observation of this goal.
await_observation "$before" --exclude-status NO_NETWORK
before=$(last_check)
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys deviceidle enable
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle get deep | tr -d '\r' | grep -qx IDLE
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -W -n ru.tgwatch/.MainActivity
await_observation "$before"
# Collect pre-reboot evidence: reboot clears the Android log buffers.
check_crashes
before=$(last_check)
adb shell locksettings set-disabled false
adb shell locksettings set-pin 1234
adb reboot
python3 scripts/adb-readiness.py --timeout 180
adb shell dumpsys user | grep -q RUNNING_LOCKED
await_observation "$before"
python3 scripts/adb-unlock-smoke.py
# Never clear the credential to make a failed PIN attempt look like an unlock.
adb shell dumpsys user | grep -q RUNNING_UNLOCKED
adb shell locksettings clear --old 1234
adb shell wm dismiss-keyguard
for attempt in $(seq 1 10); do
  if adb shell dumpsys user | grep -q RUNNING_UNLOCKED; then break; fi
  sleep 1
done
adb shell dumpsys user | grep -q RUNNING_UNLOCKED
adb shell am start -W -n ru.tgwatch/.MainActivity
adb shell run-as ru.tgwatch cat files/upgrade-sentinel | tr -d '\r' | grep -qx preserved
assert_legacy_observation
check_crashes
# After all assertions, capture the real main screen for documentation.
adb shell dumpsys deviceidle whitelist +ru.tgwatch
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell input keyevent 82
adb shell am start -W -n ru.tgwatch/.MainActivity
for attempt in $(seq 1 10); do
  if adb shell dumpsys window | grep -F mCurrentFocus | grep -Fq ru.tgwatch; then break; fi
  sleep 1
done
adb exec-out screencap -p > app/build/reports/androidTests/ui-screenshots/main.png
adb shell dumpsys window | grep -F mCurrentFocus
adb shell dumpsys window | grep -F mCurrentFocus | grep -Fq ru.tgwatch
rm -rf "$legacy_dir"
