"""Upgrade baseline selection is offline: the inventory arrives as a plain fixture."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("upgrade_baseline", SCRIPTS / "upgrade-baseline.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


def release(tag, apk=True, draft=False):
    assets = []
    if apk:
        assets.append({"name": "TgWatch.apk", "size": 10})
        assets.append({"name": "TgWatch.apk.sha256", "size": 70})
    return {"tag_name": tag, "draft": draft, "assets": assets}


class SelectBaselineTest(unittest.TestCase):
    def test_picks_the_newest_older_release(self):
        chosen = baseline.select_baseline(
            [release("v1.13.1"), release("v1.13.0"), release("v1.12.59")], "1.14.0")
        self.assertEqual("v1.13.1", chosen["tag_name"])

    def test_ignores_drafts_and_the_legacy_rolling_release(self):
        chosen = baseline.select_baseline(
            [release("v1.13.1", draft=True), release("build"), release("v1.13.0")], "1.14.0")
        self.assertEqual("v1.13.0", chosen["tag_name"])

    def test_never_uses_the_version_being_published_or_newer(self):
        chosen = baseline.select_baseline(
            [release("v1.14.0"), release("v1.15.0"), release("v1.13.1")], "1.14.0")
        self.assertEqual("v1.13.1", chosen["tag_name"])

    def test_first_release_has_no_baseline(self):
        self.assertIsNone(baseline.select_baseline([release("build")], "1.13.0"))

    def test_refuses_a_release_without_one_apk(self):
        with self.assertRaises(ValueError):
            baseline.select_baseline([release("v1.13.1", apk=False)], "1.14.0")

    def test_refuses_unrecognized_tags_instead_of_guessing(self):
        with self.assertRaises(ValueError):
            baseline.select_baseline([release("v1.13")], "1.14.0")


class ChecksumTest(unittest.TestCase):
    def write(self, directory, name, data):
        path = Path(directory) / name
        path.write_bytes(data)
        return path

    def test_accepts_the_checksum_of_the_downloaded_apk(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = self.write(tmp, "TgWatch.apk", b"apk bytes")
            digest = hashlib.sha256(b"apk bytes").hexdigest()
            for line in (f"{digest}  TgWatch.apk\n", f"{digest}\n"):
                checksum = self.write(tmp, "TgWatch.apk.sha256", line.encode())
                self.assertEqual(digest, baseline.verify_checksum(apk, checksum))

    def test_rejects_a_foreign_checksum(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = self.write(tmp, "TgWatch.apk", b"apk bytes")
            checksum = self.write(tmp, "TgWatch.apk.sha256", ("0" * 64 + "  TgWatch.apk\n").encode())
            with self.assertRaises(ValueError):
                baseline.verify_checksum(apk, checksum)

    def test_rejects_a_checksum_written_for_another_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = self.write(tmp, "TgWatch.apk", b"apk bytes")
            digest = hashlib.sha256(b"apk bytes").hexdigest()
            checksum = self.write(tmp, "TgWatch.apk.sha256", f"{digest}  other.apk\n".encode())
            with self.assertRaises(ValueError):
                baseline.verify_checksum(apk, checksum)


if __name__ == "__main__":
    unittest.main()
