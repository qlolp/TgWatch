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
report_storage_mode() {
  local stage=$1 storage
  if [[ "$(adb shell getprop ro.crypto.type | tr -d '\r')" == file ]]; then
    storage=native
  else
    storage=$(adb shell sm get-fbe-mode | tr -d '\r')
  fi
  case "$storage" in native|emulated|none) ;; *) storage=unknown ;; esac
  printf '::notice title=Direct Boot storage stage::%s: FBE=%s\n' "$stage" "$storage"
}
# The always-run reporter must never mistake a previous run for fresh results.
rm -rf -- app/build/outputs/androidTest-results/connected
bash ./gradlew :app:connectedDebugAndroidTest :app:assembleDebug --no-daemon --stacktrace
python3 scripts/adb-readiness.py
report_storage_mode after-instrumentation
mkdir -p app/build/reports/androidTests/ui-screenshots
adb pull /data/local/tmp/tgwatch-settings.png app/build/reports/androidTests/ui-screenshots/settings.png
# Runtime permission revocation kills the app; exercise denial in a fresh instrumentation.
api_level=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$api_level" -ge 33 ]]; then
  # UTP may uninstall both packages after connected tests. The separate denial
  # instrumentation needs the exact current APKs, while -r preserves app data.
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
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
  bash scripts/regional-watch-permission-smoke.sh
fi
report_storage_mode before-legacy-upgrade
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
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell input keyevent 82
adb shell am start -W -n ru.tgwatch/.MainActivity
python3 scripts/target-snapshot.py legacy-ready
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
report_storage_mode after-legacy-upgrade
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
# Previous PIN tests can leave swipe-only keyguard enabled on reused emulators.
# This stage has no credential yet; the later locked-boot assertions keep the PIN.
adb shell wm dismiss-keyguard
adb shell input keyevent 82
adb shell am start -W -n ru.tgwatch/.MainActivity
await_observation "$before"
report_storage_mode after-doze
# Collect pre-reboot evidence: reboot clears the Android log buffers.
check_crashes
before=$(last_check)
tgwatch_boot_before_reboot=$(adb shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
test -n "$tgwatch_boot_before_reboot"
adb shell locksettings set-disabled false
adb shell locksettings set-pin 1234
report_storage_mode after-setting-pin
# adb reboot bypasses Android's normal shutdown sequence. Flush the disposable
# emulator's newly written platform PIN/FBE state before this abrupt reboot.
adb shell sync
adb reboot
python3 scripts/adb-readiness.py --timeout 180 --after-boot-id "$tgwatch_boot_before_reboot"
python3 - <<'PY_BOOT_STATE'
import re, subprocess
def value(arguments):
    try:
        result = subprocess.run(['adb', 'shell'] + arguments, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        return ''
    return result.stdout.strip() if result.returncode == 0 else ''
storage = 'native' if value(['getprop', 'ro.crypto.type']) == 'file' else value(['sm', 'get-fbe-mode'])
storage = storage if storage in ('native', 'emulated', 'none') else 'unknown'
states = sorted(set(re.findall(r'\bRUNNING_(?:LOCKED|UNLOCKED|UNLOCKING)\b', value(['dumpsys', 'user']))))
disabled = value(['locksettings', 'get-disabled', '--user', '0'])
disabled = disabled if disabled in ('true', 'false') else 'unknown'
def boolean(arguments):
    result = value(arguments)
    return result if result in ('true', 'false') else 'unknown'
emulation = boolean(['getprop', 'persist.sys.emulate_fbe'])
on_disk = boolean(['cat', '/data/property/persist.sys.emulate_fbe']) if storage == 'emulated' else 'unknown'
installed = value(['pm', 'path', 'ru.tgwatch']).startswith('package:')
print(f'::notice title=PIN reboot state::FBE={storage}, states={",".join(states) or "unknown"}, '
      f'lockscreen_disabled={disabled}, FBE_property={emulation}, FBE_disk={on_disk}, app_installed={installed}')
PY_BOOT_STATE
adb shell dumpsys user | grep -q RUNNING_LOCKED
await_observation "$before" --since-boot
# The observation must actually complete before PIN unlock, not after automatic user unlock.
adb shell dumpsys user | grep -q RUNNING_LOCKED
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
# Exercise the same real onboarding/start UI used by the signed predecessor gate.
# All data-preservation assertions above have finished on this disposable emulator.
if [[ "$api_level" -eq 35 ]]; then
  python3 scripts/build-upgrade-ui.py --out "$legacy_dir/ui.jar"
  adb push "$legacy_dir/ui.jar" /data/local/tmp/tgwatch-upgrade-ui.jar
  adb shell chmod 444 /data/local/tmp/tgwatch-upgrade-ui.jar
  adb uninstall ru.tgwatch
  adb install -g app/build/outputs/apk/debug/app-debug.apk
  adb shell am start -W -n ru.tgwatch/.MainActivity
  if python3 scripts/prepare-upgrade-predecessor.py; then
    echo 'Fresh-install UI preflight passed'
  else
    # This is a fresh disposable fixture with no account or imported private settings.
    adb exec-out screencap -p > app/build/reports/androidTests/ui-screenshots/fresh-start-failure.png || true
    adb shell cat /data/local/tmp/tgwatch-upgrade-ui.xml \
      > app/build/reports/androidTests/fresh-start-failure.xml || true
    false
  fi
  await_observation 0
  check_crashes
fi
# API26/29: repeat the sensitive 65k persistence case and the new bounded-center
# preparation on the same real emulator. Each iteration must produce fresh JUnit evidence.
if [[ "$api_level" -le 29 ]]; then
  for iteration in 1 2 3; do
    echo "::notice title=Android incident regression::API $api_level, iteration $iteration of 3"
    bash ./gradlew :app:connectedDebugAndroidTest :app:assembleDebug --no-daemon --stacktrace \
      -Pandroid.testInstrumentationRunnerArguments.class='ru.tgwatch.HistoryPersistenceIntegrationTest#nextRecordKeepsBothMemoryAndDiskWithinTheRowLimit,ru.tgwatch.IncidentCenterPerformanceIntegrationTest'
    destination="app/build/outputs/androidTest-results/regression-$iteration"
    rm -rf "$destination"
    cp -a app/build/outputs/androidTest-results/connected "$destination"
    python3 scripts/report-android-tests.py "$destination"
  done
fi
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
