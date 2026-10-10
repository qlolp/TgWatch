#!/usr/bin/env bash
# Disposable root-capable emulator. Verify the immutable published 1.10.47 -> new release update.
set -euo pipefail
old_apk=${1:?Usage: signed-upgrade-smoke.sh old.apk new.apk}
new_apk=${2:?Usage: signed-upgrade-smoke.sh old.apk new.apk}
trap 'adb logcat -d > signed-upgrade-logcat.txt 2>/dev/null || true' EXIT
prefs=/data/user_de/0/ru.tgwatch/shared_prefs/tgwatch.xml
history=/data/user_de/0/ru.tgwatch/files/history-v2.csv
last_check() {
  adb shell cat "$prefs" 2>/dev/null |
    sed -n 's/.*name="last_checked" value="\([0-9]*\)".*/\1/p' | tr -d '\r'
}
await_observation() {
  local after=$1 value
  for attempt in $(seq 1 45); do
    value=$(last_check || true)
    if [[ -n "$value" && "$value" -gt "$after" ]]; then
      # Preferences are persisted before History.record; wait for its matching row too.
      if adb shell cat "$history" 2>/dev/null | tr -d '\r' |
          awk -F, -v checked="$value" '$1 == checked && NF == 5 && $5 ~ /^[0-9]+$/ {found=1} END {exit !found}'; then
        return 0
      fi
    fi
    sleep 2
  done
  echo "No fresh observation after $after" >&2
  exit 1
}
# Emulator startup can restart adbd concurrently with the first root request.
for attempt in 1 2 3; do
  if adb root > upgrade-root.txt 2>&1; then break; fi
  if ! grep -q 'unable to connect for root: closed' upgrade-root.txt; then cat upgrade-root.txt; exit 1; fi
  adb wait-for-device
done
adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" = 0 ]]
adb install -g "$old_apk"
adb shell am start -W -n ru.tgwatch/.MainActivity
await_observation 0
adb shell am force-stop ru.tgwatch
adb pull "$prefs" upgrade-before-prefs.xml
adb pull "$history" upgrade-before-history.csv
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
adb pull "$history" upgrade-after-history.csv
python3 scripts/upgrade-policy.py verify upgrade-before-prefs.xml upgrade-after-prefs.xml \
  upgrade-before-history.csv upgrade-after-history.csv
