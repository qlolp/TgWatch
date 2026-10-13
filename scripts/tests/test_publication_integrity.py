"""Publication must validate the APK itself and the bytes GitHub actually serves."""
import hashlib
import importlib.util
import io
import json
import os
from contextlib import redirect_stderr, redirect_stdout
import subprocess
import sys
import unittest
from unittest import mock

from test_release_tooling import SCRIPTS, ToolFixture


class PublicationIntegrityTests(ToolFixture, unittest.TestCase):
    def path(self, name):
        return next(path for path in self.files if path.name == name)

    def assert_refused_before_create(self, text):
        result = self.run_release()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertFalse(self.created(), "Invalid local artifacts must never be uploaded")
        self.assertIn(text.lower(), result.stderr.lower())

    def test_checksum_mismatch_cannot_be_published_directly(self):
        self.path("TgWatch.apk.sha256").write_text("0" * 64 + "  TgWatch.apk\n")
        self.assert_refused_before_create("checksum")

    def test_checksum_rejects_foreign_name_and_extra_lines(self):
        digest = hashlib.sha256(self.path("TgWatch.apk").read_bytes()).hexdigest()
        for content in (digest + "  other.apk\n", (digest + "  TgWatch.apk\n") * 2):
            with self.subTest(content=content):
                self.path("TgWatch.apk.sha256").write_text(content)
                self.assert_refused_before_create("checksum")

    def test_invalid_signature_cannot_be_published_directly(self):
        self.fixture["signature_error"] = True
        self.assert_refused_before_create("signature")

    def test_other_certificate_cannot_be_published_directly(self):
        self.fixture["certificate"] = "0" * 64
        self.assert_refused_before_create("production")

    def test_additional_signer_cannot_change_production_identity(self):
        self.fixture["extra_signer"] = True
        self.assert_refused_before_create("production")

    def test_manifest_package_version_and_code_must_match_source(self):
        for metadata in (
                "package: name='other.app' versionCode='1013000' versionName='1.13.0'",
                "package: name='ru.tgwatch' versionCode='1013000' versionName='1.12.0'",
                "package: name='ru.tgwatch' versionCode='113' versionName='1.13.0'",
                "package: name='ru.tgwatch' versionCode='1013000' versionName='1.13.0' versionName='1.13.0'"):
            with self.subTest(metadata=metadata):
                self.fixture["badging"] = metadata
                self.assert_refused_before_create("APK")

    def test_unreadable_manifest_cannot_be_published_directly(self):
        self.fixture["aapt_error"] = True
        self.assert_refused_before_create("APK")

    def test_asset_changed_during_final_preflight_is_not_uploaded(self):
        self.fixture["mutate_on_compare"] = "TgWatch.apk"
        self.assert_refused_before_create("changed")

    def test_success_redownloads_every_uploaded_asset(self):
        self.fixture["tag_missing"] = True
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        downloads = [call for call in self.calls() if call[1:3] == ["release", "download"]]
        self.assertEqual({path.name for path in self.files},
                         {call[call.index("--pattern") + 1] for call in downloads})
        self.assertTrue(all(call[3] == "v1.13.0" for call in downloads))

    def test_changed_published_asset_fails_without_mutating_existing_release(self):
        for path in self.files:
            with self.subTest(asset=path.name):
                self.log.unlink(missing_ok=True)
                self.fixture["download"] = {path.name: "different published bytes"}
                result = self.run_release()
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn("published", result.stderr.lower())
                self.assertEqual(1, len(self.created()))
                self.assertFalse(any(call[1:3] in (["release", "edit"], ["release", "upload"],
                                                  ["release", "delete"]) for call in self.calls()))


class PublicationTimeoutTests(ToolFixture, unittest.TestCase):
    def policy_main(self, subprocess_effect=None, clock_effect=None):
        self.state.write_text(json.dumps(self.fixture))
        source, notes = self.work / "version.properties", self.work / "notes.md"
        source.write_text("version=1.13.0\n")
        notes.write_text("Fixture notes\n")
        sys.path.insert(0, str(SCRIPTS))
        try:
            spec = importlib.util.spec_from_file_location("publication_timeout_policy", SCRIPTS / "release-policy.py")
            policy = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(policy)
        finally:
            sys.path.remove(str(SCRIPTS))
        argv = ["release-policy.py", "--repo", "owner/repo", "--version-file", str(source),
                "--sha", "a" * 40, "--ref", "refs/heads/main", "--notes", str(notes),
                "--assets", *map(str, self.files)]
        stderr, stdout = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, self.env), mock.patch.object(sys, "argv", argv), \
             redirect_stderr(stderr), redirect_stdout(stdout):
            if subprocess_effect:
                with mock.patch.object(policy.subprocess, "run", side_effect=subprocess_effect):
                    result = policy.main()
            elif clock_effect:
                with mock.patch("time.monotonic", side_effect=clock_effect):
                    result = policy.main()
            else:
                result = policy.main()
        return result, stderr.getvalue()

    def assert_immutable_failure(self, result, stderr):
        self.assertEqual(1, result)
        self.assertEqual(1, len(self.created()))
        self.assertIn("was created", stderr)
        self.assertIn("immutable", stderr)
        self.assertIn("timed out", stderr)
        self.assertFalse(any(call[1:3] in (["release", "edit"], ["release", "upload"],
                                          ["release", "delete"]) for call in self.calls()))

    def test_download_timeout_reports_created_immutable_release(self):
        run = subprocess.run
        limits = []
        def timeout_download(command, **kwargs):
            if command[:3] == ["gh", "release", "download"]:
                limit = kwargs.get("timeout", 180)
                limits.append(limit)
                raise subprocess.TimeoutExpired(command, limit)
            return run(command, **kwargs)
        result, stderr = self.policy_main(subprocess_effect=timeout_download)
        self.assert_immutable_failure(result, stderr)
        self.assertEqual(1, len(limits))
        self.assertLessEqual(limits[0], 120)

    def test_overall_download_budget_stops_further_requests(self):
        result, stderr = self.policy_main(clock_effect=[0, 0, 301])
        self.assert_immutable_failure(result, stderr)
        self.assertEqual(1, sum(call[1:3] == ["release", "download"] for call in self.calls()))

    def test_create_timeout_reports_unknown_outcome_without_retry_or_repair(self):
        run = subprocess.run
        def timeout_create(command, **kwargs):
            completed = run(command, **kwargs)
            if command[:3] == ["gh", "release", "create"]:
                # The server accepted creation before the client lost its response.
                raise subprocess.TimeoutExpired(command, kwargs.get("timeout", 180))
            return completed
        result, stderr = self.policy_main(subprocess_effect=timeout_create)
        self.assertEqual(1, result)
        self.assertEqual(1, len(self.created()))
        self.assertIn("result is unknown", stderr)
        self.assertIn("read-only", stderr)
        self.assertIn("preserve any existing assets", stderr)
        self.assertNotIn("refused publication", stderr)
        self.assertFalse(any(call[1:3] in (["release", "edit"], ["release", "upload"],
                                          ["release", "delete"], ["release", "download"]) for call in self.calls()))


if __name__ == "__main__":
    unittest.main()
