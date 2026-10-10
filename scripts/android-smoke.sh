#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d > smoke-logcat.txt || true' EXIT
bash ./gradlew connectedDebugAndroidTest assembleDebug --no-daemon --stacktrace
apk=app/build/outputs/apk/debug/app-debug.apk
adb install -r "$apk"
adb shell am start -W -n ru.tgwatch/.MainActivity
# A replacement install must keep app data and its signing identity.
adb shell run-as ru.tgwatch sh -c "'echo preserved > files/upgrade-sentinel'"
adb install -r "$apk"
adb shell run-as ru.tgwatch cat files/upgrade-sentinel | tr -d '\r' | grep -qx preserved
adb shell am start -W -n ru.tgwatch/.MainActivity
# Network transition and idle recovery. They must not crash the foreground service.
adb shell svc wifi disable
adb shell svc data disable
sleep 6
adb shell svc wifi enable
adb shell svc data enable
adb shell dumpsys deviceidle force-idle || true
adb shell dumpsys deviceidle unforce
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -W -n ru.tgwatch/.MainActivity
# Test actual locked boot, not a synthetic protected broadcast.
adb shell locksettings set-pin 1234
adb reboot
adb wait-for-device
for attempt in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]
adb shell pidof ru.tgwatch
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent 82
adb shell input text 1234
adb shell input keyevent 66
sleep 3
adb shell locksettings clear --old 1234
adb shell am start -W -n ru.tgwatch/.MainActivity
adb shell run-as ru.tgwatch cat files/upgrade-sentinel | tr -d '\r' | grep -qx preserved
adb logcat -d > smoke-logcat.txt
if grep -A4 'FATAL EXCEPTION' smoke-logcat.txt | grep -q 'ru.tgwatch'; then
  echo 'TG Watch crashed during smoke test' >&2
  exit 1
fi
