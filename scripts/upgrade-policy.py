#!/usr/bin/env python3
"""Seed and verify the real published 1.10.47 signed upgrade without clearing data."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SEED = {
    "power_profile": ("string", "ECONOMY"), "interval_sec": ("int", "60"),
    "vibrate_offline": ("boolean", "true"), "vibrate_partial": ("boolean", "true"),
    "event_sound": ("boolean", "true"), "vibrate_recovery": ("boolean", "false"),
    "vibration_pattern": ("string", "LONG"),
    "notify_outage": ("boolean", "false"), "notify_partial": ("boolean", "true"),
    "notify_recovery": ("boolean", "false"),
    "sound_outage": ("boolean", "false"), "sound_partial": ("boolean", "true"),
    "sound_recovery": ("boolean", "false"),
    "quiet_hours": ("boolean", "true"), "quiet_start_hour": ("int", "23"),
    "quiet_end_hour": ("int", "7"),
}


def seed(source, destination):
    tree = ET.parse(source)
    root = tree.getroot()
    # Published 1.10 has hour settings; remove minute keys to exercise fallback.
    for node in list(root):
        if node.get("name") in SEED or node.get("name") in ("quiet_start_minute", "quiet_end_minute"):
            root.remove(node)
    for name, (tag, value) in SEED.items():
        node = ET.SubElement(root, tag, name=name)
        if tag == "string": node.text = value
        else: node.set("value", value)
    tree.write(destination, encoding="utf-8", xml_declaration=True)


def prefs(path):
    return {node.get("name"): node.text if node.tag == "string" else node.get("value")
            for node in ET.parse(path).getroot()}


def verify(before_prefs, after_prefs, before_history, after_history):
    before = set(Path(before_history).read_text().splitlines())
    after = set(Path(after_history).read_text().splitlines())
    assert before and before <= after, "Existing observations were lost during signed upgrade"
    assert len(after) > len(before), "No observations recorded after upgrade"
    values = prefs(after_prefs)
    for name, (_, expected) in SEED.items():
        if name not in ("quiet_start_hour", "quiet_end_hour"):
            assert values.get(name) == expected, f"Existing {name} setting was lost"
    # Minute getters deliberately migrate lazily. Either stored minutes or the
    # preserved legacy hours must resolve to the same effective 23:00–07:00 range.
    start = int(values.get("quiet_start_minute", int(values.get("quiet_start_hour", "-1")) * 60))
    end = int(values.get("quiet_end_minute", int(values.get("quiet_end_hour", "-1")) * 60))
    assert (start, end) == (1380, 420), "Legacy quiet hours changed during minute migration"
    previous, checked = int(prefs(before_prefs)["last_checked"]), int(values["last_checked"])
    assert checked > previous, "Persisted status is not fresh after upgrade"
    assert any(len(row.split(",")) == 5 and row.split(",")[0] == str(checked)
               and row.split(",")[4].isdigit() for row in after), "Fresh timestamp has no matching history row"


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "seed":
        seed(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 6 and sys.argv[1] == "verify":
        verify(*sys.argv[2:])
        print("Published 1.10.47 -> signed release: history, independent event choices, legacy hours and fresh observation verified.")
    else:
        print("Usage: upgrade-policy.py seed before.xml seeded.xml | verify before.xml after.xml before.csv after.csv", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
