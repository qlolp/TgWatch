"""Offline release-inventory, checksum and downloaded-APK upgrade gates."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
SCRIPT = SCRIPTS / "upgrade-baseline.py"
APK_BYTES = b"published production APK fixture"
APK_SHA256 = hashlib.sha256(APK_BYTES).hexdigest()
CHECKSUM = f"{APK_SHA256}  TgWatch.apk\n"


def asset(name, data):
    return {"name": name, "size": len(data), "state": "uploaded",
            "digest": "sha256:" + hashlib.sha256(data).hexdigest()}


def release(tag, **overrides):
    return {"tag_name": tag, "draft": False, "prerelease": False,
            "assets": [asset("TgWatch.apk", APK_BYTES),
                       asset("TgWatch.apk.sha256", CHECKSUM.encode())], **overrides}


class BaselineFixture(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "Missing upgrade-baseline.py")
        sys.path.insert(0, str(SCRIPTS))
        self.addCleanup(lambda: sys.path.remove(str(SCRIPTS)))
        spec = importlib.util.spec_from_file_location("upgrade_baseline", SCRIPT)
        self.baseline = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.baseline)


class SelectBaselineTests(BaselineFixture):
    def test_picks_highest_predecessor_independent_of_inventory_order(self):
        chosen = self.baseline.select_baseline(
            [release("v1.13.1"), release("v1.14.0"), release("v1.13.9")], "1.15.0")
        self.assertEqual("v1.14.0", chosen["tag_name"])

    def test_skips_drafts_prereleases_and_rolling_build(self):
        chosen = self.baseline.select_baseline([
            release("v1.14.2", draft=True), release("v1.14.1", prerelease=True),
            release("v1.15.0-rc.1", prerelease=True), release("build"),
            release("v1.14.0")], "1.15.0")
        self.assertEqual("v1.14.0", chosen["tag_name"])

    def test_never_uses_current_or_newer_version(self):
        chosen = self.baseline.select_baseline(
            [release("v1.15.0"), release("v1.16.0"), release("v1.14.0")], "1.15.0")
        self.assertEqual("v1.14.0", chosen["tag_name"])

    def test_first_release_has_no_predecessor(self):
        self.assertIsNone(self.baseline.select_baseline(
            [release("build"), release("v1.14.0", draft=True)], "1.13.0"))

    def test_historical_release_without_checksum_does_not_hide_newest(self):
        old = release("v1.10.47")
        old["assets"] = old["assets"][:1]
        chosen = self.baseline.select_baseline([old, release("v1.14.0")], "1.15.0")
        self.assertEqual("v1.14.0", chosen["tag_name"])

    def test_missing_chosen_apk_or_checksum_fails_instead_of_falling_back(self):
        for name in ("TgWatch.apk", "TgWatch.apk.sha256"):
            with self.subTest(name=name):
                newest = release("v1.14.0")
                newest["assets"] = [a for a in newest["assets"] if a["name"] != name]
                with self.assertRaises(ValueError):
                    self.baseline.select_baseline([newest, release("v1.13.1")], "1.15.0")

    def test_chosen_asset_metadata_must_be_unique_uploaded_and_nonempty(self):
        for field, value in (("size", 0), ("size", -1), ("size", True),
                             ("size", "10"), ("state", "new"), ("digest", "sha256:bad")):
            for index in (0, 1):
                with self.subTest(field=field, value=value, index=index):
                    newest = release("v1.14.0")
                    newest["assets"][index][field] = value
                    with self.assertRaises(ValueError):
                        self.baseline.select_baseline([newest], "1.15.0")
        newest = release("v1.14.0")
        newest["assets"].append(dict(newest["assets"][0]))
        with self.assertRaises(ValueError):
            self.baseline.select_baseline([newest], "1.15.0")

    def test_refuses_unrecognized_stable_tag(self):
        with self.assertRaises(ValueError):
            self.baseline.select_baseline([release("v1.14")], "1.15.0")


class ChecksumTests(BaselineFixture):
    def verify(self, text):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk, checksum = root / "TgWatch.apk", root / "TgWatch.apk.sha256"
            apk.write_bytes(APK_BYTES)
            checksum.write_text(text)
            return self.baseline.verify_checksum(apk, checksum)

    def test_accepts_downloaded_checksum_named_or_bare(self):
        for text in (CHECKSUM, f"{APK_SHA256}\n", f"{APK_SHA256} *TgWatch.apk\n"):
            with self.subTest(text=text):
                self.assertEqual(APK_SHA256, self.verify(text))

    def test_rejects_mismatch_foreign_name_and_multiline_checksum(self):
        for text in ("0" * 64 + "  TgWatch.apk\n", f"{APK_SHA256}  other.apk\n",
                     CHECKSUM + CHECKSUM, "not a checksum\n"):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    self.verify(text)


class BaselineCliTests(BaselineFixture):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.bin = self.work / "bin"
        self.bin.mkdir()
        self.log = self.work / "calls.jsonl"
        self.state = self.work / "state.json"
        self.output = self.work / "github-output.txt"
        self.version = self.work / "version.properties"
        self.version.write_text("version=1.15.0\n")
        self.fixture = {"releases": [release("v1.14.0")], "downloads": {
            "TgWatch.apk": APK_BYTES.decode(), "TgWatch.apk.sha256": CHECKSUM},
            "badging": "package: name='ru.tgwatch' versionCode='1014000' versionName='1.14.0'\n"}
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        BASELINE_STATE=str(self.state), BASELINE_LOG=str(self.log))
        self.executable("gh", """import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['BASELINE_LOG'], 'a') as stream:
    stream.write(json.dumps(['gh'] + args) + '\\n')
state = json.loads(Path(os.environ['BASELINE_STATE']).read_text())
if state.get('api_error') and args[0] == 'api':
    print('HTTP 403: API unavailable', file=sys.stderr); sys.exit(1)
if args[0] == 'api': print(json.dumps(state['releases']))
elif args[:2] == ['release', 'download']:
    name = args[args.index('--pattern') + 1]
    if name == state.get('download_error'):
        print('download unavailable', file=sys.stderr); sys.exit(1)
    directory = Path(args[args.index('--dir') + 1])
    (directory / name).write_text(state['downloads'][name])
else: raise AssertionError(args)
""")
        self.executable("aapt2", """import json, os, sys
from pathlib import Path
with open(os.environ['BASELINE_LOG'], 'a') as stream:
    stream.write(json.dumps(['aapt2'] + sys.argv[1:]) + '\\n')
state = json.loads(Path(os.environ['BASELINE_STATE']).read_text())
if state.get('aapt_error'):
    print('APK could not be inspected', file=sys.stderr); sys.exit(1)
print(state['badging'])
""")

    def executable(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env python3\n" + body)
        path.chmod(0o755)

    def run_cli(self):
        self.state.write_text(json.dumps(self.fixture))
        return subprocess.run([sys.executable, str(SCRIPT), "--repo", "owner/repo",
                               "--version-file", str(self.version), "--out", str(self.work / "previous-latest"),
                               "--aapt2", str(self.bin / "aapt2"), "--github-output", str(self.output)],
                              env=self.env, text=True, capture_output=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_downloads_checks_and_inspects_selected_predecessor(self):
        result = self.run_cli()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("v1.14.0\n", (self.work / "previous-latest/tag.txt").read_text())
        self.assertIn("available=true\n", self.output.read_text())
        downloads = [c for c in self.calls() if c[1:3] == ["release", "download"]]
        self.assertEqual(["v1.14.0", "v1.14.0"], [c[3] for c in downloads])
        self.assertEqual(["TgWatch.apk", "TgWatch.apk.sha256"],
                         [c[c.index("--pattern") + 1] for c in downloads])
        self.assertTrue(any(c[:3] == ["aapt2", "dump", "badging"] for c in self.calls()))

    def test_no_predecessor_skips_only_additional_path_without_downloads(self):
        self.fixture["releases"] = [release("build")]
        result = self.run_cli()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("available=false\n", self.output.read_text())
        self.assertFalse((self.work / "previous-latest/tag.txt").exists())
        self.assertFalse(any(c[1:3] == ["release", "download"] for c in self.calls()))

    def assert_rejected(self, reason):
        result = self.run_cli()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(reason, result.stderr)
        self.assertFalse(self.output.exists(), result.stdout)
        self.assertFalse((self.work / "previous-latest/tag.txt").exists())

    def test_api_error_fails_without_claiming_no_predecessor(self):
        self.fixture["api_error"] = True
        self.assert_rejected("API unavailable")

    def test_missing_checksum_download_fails_without_available_output(self):
        self.fixture["download_error"] = "TgWatch.apk.sha256"
        self.assert_rejected("download unavailable")

    def test_downloaded_size_must_match_release_metadata(self):
        self.fixture["releases"][0]["assets"][0]["size"] += 1
        self.assert_rejected("size")

    def test_downloaded_metadata_digest_must_match(self):
        self.fixture["releases"][0]["assets"][1]["digest"] = "sha256:" + "0" * 64
        self.assert_rejected("digest")

    def test_checksum_contents_must_match_apk_even_when_asset_metadata_matches(self):
        self.fixture["downloads"]["TgWatch.apk.sha256"] = "0" * 64 + "  TgWatch.apk\n"
        self.fixture["releases"][0]["assets"][1] = asset(
            "TgWatch.apk.sha256", self.fixture["downloads"]["TgWatch.apk.sha256"].encode())
        self.assert_rejected("checksum mismatch")

    def test_wrong_version_name_cannot_define_baseline(self):
        self.fixture["badging"] = self.fixture["badging"].replace("1.14.0", "1.13.0")
        self.assert_rejected("versionName")

    def test_wrong_version_code_cannot_define_baseline(self):
        self.fixture["badging"] = self.fixture["badging"].replace("1014000", "1013000")
        self.assert_rejected("versionCode")

    def test_wrong_package_cannot_define_baseline(self):
        self.fixture["badging"] = self.fixture["badging"].replace("ru.tgwatch", "other.app")
        self.assert_rejected("package")

    def test_unreadable_apk_cannot_define_baseline(self):
        self.fixture["aapt_error"] = True
        self.assert_rejected("APK could not be inspected")

    def test_stale_destination_cannot_be_reused_as_verified_baseline(self):
        stale = self.work / "previous-latest"
        stale.mkdir()
        (stale / "tag.txt").write_text("v1.13.0\n")
        result = self.run_cli()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("empty", result.stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
