#!/usr/bin/env bash
# Disposable root-capable emulator. Verify a published APK -> new release update.
set -euo pipefail
old_apk=${1:?Usage: signed-upgrade-smoke.sh old.apk new.apk}
new_apk=${2:?Usage: signed-upgrade-smoke.sh old.apk new.apk}
trap 'adb logcat -d > signed-upgrade-logcat.txt 2>/dev/null || true' EXIT
prefs=/data/user_de/0/ru.tgwatch/shared_prefs/tgwatch.xml
last_check() {
  adb shell cat "$prefs" 2>/dev/null |
    sed -n 's/.*name="last_checked" value="\([0-9]*\)".*/\1/p' | tr -d '\r'
}
await_observation() {
  python3 scripts/target-snapshot.py await --after "$1" --timeout "${TGWATCH_OBSERVATION_TIMEOUT:-90}"
}
# The same bounded reconnect/root/boot gate used by the Android smoke suite.
python3 scripts/adb-readiness.py --timeout 180
bash ./gradlew :ui-probe:assembleDebug --no-daemon --stacktrace
adb install -r -t ui-probe/build/outputs/apk/debug/ui-probe-debug.apk
adb install -g "$old_apk"
adb shell am start -W -n ru.tgwatch/.MainActivity
python3 scripts/prepare-upgrade-predecessor.py
await_observation 0
adb shell am force-stop ru.tgwatch
adb pull "$prefs" upgrade-before-prefs.xml
python3 scripts/target-snapshot.py capture --out upgrade-before-targets.json
python3 scripts/upgrade-policy.py seed upgrade-before-prefs.xml upgrade-seeded-prefs.xml
adb push upgrade-seeded-prefs.xml /data/local/tmp/tgwatch-upgrade-prefs.xml
# Overwrite the existing inode so its app ownership and permissions remain intact.
adb shell "cat /data/local/tmp/tgwatch-upgrade-prefs.xml > $prefs"
before=$(last_check)
# Never uninstall or clear data between these two installations.
adb install -r -g "$new_apk"
adb shell am start -W -n ru.tgwatch/.MainActivity
await_observation "$before"
adb exec-out screencap -p > release-screen.png
adb pull "$prefs" upgrade-after-prefs.xml
python3 scripts/target-snapshot.py capture --out upgrade-after-targets.json
python3 scripts/upgrade-policy.py verify upgrade-before-prefs.xml upgrade-after-prefs.xml \
  upgrade-before-targets.json upgrade-after-targets.json
