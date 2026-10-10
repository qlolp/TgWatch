#!/usr/bin/env bash
# Disposable root-capable emulator. Verify the real published 1.8.33 -> new release update.
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
adb root
adb wait-for-device
adb install -g "$old_apk"
adb shell am start -W -n ru.tgwatch/.MainActivity
await_observation 0
adb shell am force-stop ru.tgwatch
adb pull "$prefs" upgrade-before-prefs.xml
adb pull "$history" upgrade-before-history.csv
python3 - <<'PY'
import xml.etree.ElementTree as ET
tree = ET.parse('upgrade-before-prefs.xml')
root = tree.getroot()
for name, tag, value in [('power_profile', 'string', 'ECONOMY'),
                          ('interval_sec', 'int', '60'),
                          ('vibrate_offline', 'boolean', 'true'),
                          ('vibrate_partial', 'boolean', 'true')]:
    for node in list(root):
        if node.get('name') == name: root.remove(node)
    node = ET.SubElement(root, tag, name=name)
    if tag == 'string': node.text = value
    else: node.set('value', value)
tree.write('upgrade-seeded-prefs.xml', encoding='utf-8', xml_declaration=True)
PY
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
python3 - <<'PY'
import pathlib, xml.etree.ElementTree as ET
before = set(pathlib.Path('upgrade-before-history.csv').read_text().splitlines())
after = set(pathlib.Path('upgrade-after-history.csv').read_text().splitlines())
assert before and before <= after, 'Existing observations were lost during signed upgrade'
assert len(after) > len(before), 'No observations recorded after upgrade'
values = {e.get('name'): e.text if e.tag == 'string' else e.get('value')
          for e in ET.parse('upgrade-after-prefs.xml').getroot()}
assert values['power_profile'] == 'ECONOMY'
assert values['interval_sec'] == '60'
assert values['vibrate_offline'] == 'true'
assert values['vibrate_partial'] == 'true', 'Existing PARTIAL opt-in was lost'
assert values.get('event_sound', 'false') == 'false', 'Upgrade opted into new sounds'
print('Published 1.8.33 -> signed release: installation, history, settings and new observations verified.')
PY
