#!/usr/bin/env bash
# Dedicated disposable emulator only: changes PIN/network and reboots.
set -euo pipefail
trap 'adb logcat -d >> smoke-logcat.txt 2>/dev/null || true' EXIT
: > smoke-logcat.txt
check_crashes() {
  adb logcat -d >> smoke-logcat.txt
  if grep -A5 'FATAL EXCEPTION' smoke-logcat.txt | grep -q 'ru.tgwatch'; then
    echo 'TG Watch crashed during smoke test' >&2; exit 1
  fi
  adb logcat -c
}
last_check() {
  adb shell cat /data/user_de/0/ru.tgwatch/shared_prefs/tgwatch.xml 2>/dev/null |
    sed -n 's/.*name="last_checked" value="\([0-9]*\)".*/\1/p' | tr -d '\r'
}
await_observation() {
  local after=$1 value
  for attempt in $(seq 1 45); do
    value=$(last_check || true)
    if [[ -n "$value" && "$value" -gt "$after" ]]; then
      adb shell dumpsys activity services ru.tgwatch | grep -q 'isForeground=true'
      adb shell cat /data/user_de/0/ru.tgwatch/files/history-v2.csv | grep -q ',[A-Z_]*,'
      return 0
    fi
    sleep 2
  done
  echo "No new foreground-service observation after $after" >&2
  adb shell dumpsys activity services ru.tgwatch
  adb logcat -d -s TgWatch AndroidRuntime
  return 1
}
bash ./gradlew connectedDebugAndroidTest assembleDebug --no-daemon --stacktrace
# A genuine 1.6 -> 1.7 data-format upgrade using one test signing identity.
# This does not claim compatibility with an unrecoverable old CI debug key.
legacy_dir=$(mktemp -d)
git archive fef8aef77da5a8b5d2363ef2167deab3a3d4d4d2 | tar -x -C "$legacy_dir"
(cd "$legacy_dir" && bash ./gradlew assembleDebug --no-daemon)
if adb shell pm path ru.tgwatch | grep -q '^package:'; then adb uninstall ru.tgwatch; fi
adb install -g "$legacy_dir/app/build/outputs/apk/debug/app-debug.apk"
adb root
adb wait-for-device
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
adb shell cat /data/user_de/0/ru.tgwatch/files/history-v2.csv | grep -q "^$((minute * 60000)),"
# Connectivity loss and recovery must produce new persisted observations.
before=$(last_check)
adb shell svc wifi disable
adb shell svc data disable
sleep 6
await_observation "$before"
before=$(last_check)
adb shell svc wifi enable
adb shell svc data enable
await_observation "$before"
before=$(last_check)
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -W -n ru.tgwatch/.MainActivity
await_observation "$before"
# Collect pre-reboot evidence: reboot clears the Android log buffers.
check_crashes
before=$(last_check)
adb shell locksettings set-pin 1234
adb reboot
adb wait-for-device
for attempt in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]
adb root
adb wait-for-device
adb shell dumpsys user | grep -q RUNNING_LOCKED
await_observation "$before"
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent 82
adb shell input text 1234
adb shell input keyevent 66
sleep 3
adb shell locksettings clear --old 1234
adb shell am start -W -n ru.tgwatch/.MainActivity
adb shell run-as ru.tgwatch cat files/upgrade-sentinel | tr -d '\r' | grep -qx preserved
adb shell cat /data/user_de/0/ru.tgwatch/files/history-v2.csv | grep -q "^$((minute * 60000)),"
check_crashes
rm -rf "$legacy_dir"
