"""Real XML/CSV signed-upgrade assertions without adb or signing credentials."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


class UpgradePolicyTests(unittest.TestCase):
    def load(self):
        path = ROOT / "scripts/upgrade-policy.py"
        self.assertTrue(path.exists(), "Missing unified upgrade policy")
        spec = importlib.util.spec_from_file_location("upgrade_policy", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def fixture(self, root, policy):
        before = root / "before.xml"
        before.write_text('<map><long name="last_checked" value="100"/><int name="quiet_start_minute" value="1"/></map>')
        seeded = root / "seeded.xml"
        policy.seed(before, seeded)
        tree = ET.parse(seeded)
        values = {node.get("name"): node for node in tree.getroot()}
        self.assertNotIn("quiet_start_minute", values)
        self.assertNotIn("quiet_end_minute", values)
        self.assertEqual("23", values["quiet_start_hour"].get("value"))
        self.assertEqual("7", values["quiet_end_hour"].get("value"))
        after = root / "after.xml"
        values["last_checked"].set("value", "200")
        tree.write(after)
        before_history, after_history = root / "before.csv", root / "after.csv"
        before_history.write_text("100,200,OK,10,1\n")
        after_history.write_text("100,200,OK,10,1\n200,300,TG_DOWN,20,1\n")
        return before, after, before_history, after_history

    def test_independent_false_choices_and_legacy_hours_survive(self):
        policy = self.load()
        with tempfile.TemporaryDirectory() as directory:
            files = self.fixture(Path(directory), policy)
            policy.verify(*files)
            # A migration that mistakenly falls back to event_sound=true must fail.
            tree = ET.parse(files[1])
            for node in tree.getroot():
                if node.get("name") == "sound_outage": node.set("value", "true")
            tree.write(files[1])
            with self.assertRaises(AssertionError): policy.verify(*files)

    def test_explicit_minute_migration_must_preserve_effective_hours(self):
        policy = self.load()
        with tempfile.TemporaryDirectory() as directory:
            files = self.fixture(Path(directory), policy)
            tree = ET.parse(files[1])
            ET.SubElement(tree.getroot(), "int", name="quiet_start_minute", value="1380")
            ET.SubElement(tree.getroot(), "int", name="quiet_end_minute", value="420")
            tree.write(files[1])
            policy.verify(*files)
            for node in tree.getroot():
                if node.get("name") == "quiet_start_minute": node.set("value", "1")
            tree.write(files[1])
            with self.assertRaises(AssertionError): policy.verify(*files)

    def test_stale_or_missing_observation_cannot_pass(self):
        policy = self.load()
        with tempfile.TemporaryDirectory() as directory:
            files = self.fixture(Path(directory), policy)
            files[3].write_text("100,200,OK,10,1\n150,200,TG_DOWN,20,1\n")
            with self.assertRaises(AssertionError): policy.verify(*files)

    def test_canonical_target_snapshot_preserves_legacy_identity(self):
        policy = self.load()
        with tempfile.TemporaryDirectory() as directory:
            files = self.fixture(Path(directory), policy)
            def row(at, until, target='telegram'):
                return dict(target_id=target, at_ms=at, until_ms=until, status='OK',
                    latency_ms=10, clock_epoch=1, endpoint_revision=0)
            before = dict(schema_version=1, captured_at_ms=200, source='legacy', generation=None,
                observations=[row(100, 200)])
            after = dict(schema_version=1, captured_at_ms=300, source='targets', generation='a' * 32,
                observations=[row(100, 200), row(200, 300)])
            files[2].write_text(json.dumps(before))
            files[3].write_text(json.dumps(after))
            policy.verify(*files)
            # A new Matrix result with the same timestamp cannot validate the Telegram status.
            after['observations'][-1]['target_id'] = 'matrix'
            files[3].write_text(json.dumps(after))
            with self.assertRaises(AssertionError): policy.verify(*files)


if __name__ == "__main__": unittest.main()
