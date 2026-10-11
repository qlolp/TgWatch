"""Integration policies for unified 1.11; no production Keychain/Secrets access."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from test_release_tooling import BOM, LIBRARY, ROOT, SCRIPTS, ToolFixture


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class UnifiedGraphTests(unittest.TestCase):
    def test_hashed_sbom_retains_resolved_direct_and_transitive_edges(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifacts = []
            for name in ("direct", "transitive"):
                path = root / (name + ".jar")
                path.write_bytes(name.encode())
                artifacts.append({"group": "example", "name": name, "version": "1.0", "type": "jar", "file": str(path)})
            inventory = {"applicationVersion": "1.11", "artifacts": artifacts, "runtimeGraph": [
                {"ref": "ru.tgwatch:releaseRuntimeClasspath", "dependsOn": ["example:direct:1.0"]},
                {"ref": "example:direct:1.0", "dependsOn": ["example:transitive:1.0"]},
                {"ref": "example:transitive:1.0", "dependsOn": []}]}
            source = root / "inventory.json"
            source.write_text(json.dumps(inventory))
            destination = root / "bom.json"
            result = subprocess.run([sys.executable, str(SCRIPTS / "generate-sbom.py"), str(source), str(destination)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            bom = json.loads(destination.read_text())
            edges = {entry["ref"]: entry["dependsOn"] for entry in bom["dependencies"]}
            direct = "pkg:maven/example/direct@1.0?type=jar"
            transitive = "pkg:maven/example/transitive@1.0?type=jar"
            self.assertEqual([direct], edges["ru.tgwatch:releaseRuntimeClasspath"])
            self.assertEqual([transitive], edges[direct])
            self.assertEqual([], edges[transitive])
            self.assertTrue(all(component["hashes"][0]["alg"] == "SHA-256" for component in bom["components"]))


class UnifiedReleaseTests(ToolFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.packages = [{"group": "org.example", "name": "library", "version": "1.0", "scopes": ["releaseRuntimeClasspath"]},
                         {"group": "junit", "name": "junit", "version": "4.13.2", "scopes": ["debugUnitTestRuntimeClasspath"]}]
        self.inventory = self.work / "TgWatch.dependencies.json"
        self.inventory.write_text(json.dumps({"packages": self.packages}))
        self.audit = self.work / "TgWatch.osv.json"
        self.report = {"checked_at": "2026-10-10T00:00:00+00:00", "source": "https://api.osv.dev/v1/query",
                       "packages": [dict(package, vulnerabilities=[]) for package in self.packages]}
        self.audit.write_text(json.dumps(self.report))

    def publish_existing(self, report):
        old = json.dumps(report)
        import hashlib
        self.fixture["releases"] = [{"tag_name": "v1.11.40", "draft": False, "assets": [
            {"name": path.name, "size": len(old) if path == self.audit else path.stat().st_size,
             "digest": "sha256:" + hashlib.sha256(old.encode() if path == self.audit else path.read_bytes()).hexdigest()}
            for path in self.files]}]
        self.fixture["download"] = {self.audit.name: old}
        return self.run_release(version="1.11")

    def test_osv_timestamp_change_keeps_original_published_report(self):
        historical = dict(self.report, checked_at="2026-10-09T00:00:00+00:00")
        result = self.publish_existing(historical)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.created())

    def test_published_osv_report_cannot_omit_test_scope_package(self):
        historical = dict(self.report, packages=self.report["packages"][:1])
        result = self.publish_existing(historical)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())

    def test_new_release_cannot_publish_an_osv_finding(self):
        self.report["packages"][1]["vulnerabilities"] = ["OSV-regression"]
        self.audit.write_text(json.dumps(self.report))
        result = self.run_release(version="1.11")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())

    def test_new_release_requires_both_audits_and_the_full_inventory(self):
        self.files = [path for path in self.files if path.name not in ("TgWatch.dependencies.json", "TgWatch.osv.json")]
        result = self.run_release(version="1.11")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())


class UnifiedSigningTests(unittest.TestCase):
    def setUp(self):
        self.signing = load("unified_signing", SCRIPTS / "signing-keychain.py")
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = Path(self.directory.name) / "tgwatch-release.p12"
        self.store.write_bytes(b"existing encrypted fixture")
        self.calls = []
        self.keychain = mock.Mock()
        self.keychain.load.return_value = "fixture-only-password"

    def configure(self, expected=True, api_error=False, private=True):
        fingerprint = "AB74727A44F59684045E7DB4C820BE309F886433A18A3C06F3439417F3ACBEF1"
        args = ["signing", "configure", str(self.store.parent)]
        if expected: args += ["--expected-certificate", fingerprint]
        def run(command, **kwargs):
            self.calls.append((command, kwargs))
            if command[0] == "gh" and command[1:3] == ["secret", "list"]:
                if api_error: raise subprocess.CalledProcessError(1, command)
                return subprocess.CompletedProcess(command, 0, stdout="TGWATCH_KEYSTORE_BASE64\n")
            if command[0] == "gh": return subprocess.CompletedProcess(command, 0)
            output = ("Entry type: " + ("PrivateKeyEntry" if private else "trustedCertEntry") + "\nSHA256: " + fingerprint)
            return subprocess.CompletedProcess(command, 0, stdout=output)
        with mock.patch.object(self.signing, "Keychain", return_value=self.keychain), \
             mock.patch.object(self.signing.sys, "argv", args), \
             mock.patch.object(self.signing.subprocess, "run", side_effect=run):
            return self.signing.main()

    def test_configure_requires_expected_production_certificate(self):
        with self.assertRaises((RuntimeError, SystemExit)):
            self.configure(expected=False)
        self.assertFalse(any(command[:3] == ["gh", "secret", "set"] for command, _ in self.calls))

    def test_configure_requires_private_key_entry(self):
        with self.assertRaises(RuntimeError): self.configure(private=False)
        self.assertFalse(any(command[:3] == ["gh", "secret", "set"] for command, _ in self.calls))

    def test_api_readiness_error_never_saves_or_writes_secrets(self):
        with self.assertRaises(subprocess.CalledProcessError): self.configure(api_error=True)
        self.keychain.save.assert_not_called()
        self.assertFalse(any(command[:3] == ["gh", "secret", "set"] for command, _ in self.calls))

    def test_api_readiness_precedes_storing_new_keychain_password(self):
        self.keychain.load.side_effect = self.signing.KeychainItemMissing("No item")
        with mock.patch.object(self.signing.getpass, "getpass", return_value="fixture-only-password"):
            with self.assertRaises(subprocess.CalledProcessError): self.configure(api_error=True)
        self.keychain.save.assert_not_called()

    def test_denied_keychain_is_not_replaced_by_prompted_password(self):
        self.keychain.load.side_effect = RuntimeError("Keychain read denied")
        with mock.patch.object(self.signing.getpass, "getpass") as prompt:
            with self.assertRaises(RuntimeError): self.configure()
            prompt.assert_not_called()
        self.keychain.save.assert_not_called()
        self.assertFalse(self.calls)

    def test_existing_identity_uploaded_only_after_readiness(self):
        self.configure()
        commands = [command for command, _ in self.calls]
        self.assertEqual(4, sum(command[:3] == ["gh", "secret", "set"] for command in commands))
        self.assertLess(next(i for i, command in enumerate(commands) if command[:3] == ["gh", "secret", "list"]),
                        next(i for i, command in enumerate(commands) if command[:3] == ["gh", "secret", "set"]))
        self.assertFalse(any("-genkeypair" in command for command in commands))
        self.assertTrue(all("fixture-only-password" not in str(command) for command in commands))


class UnifiedWorkflowTests(unittest.TestCase):
    def test_both_audits_and_full_inventory_are_release_assets(self):
        text = (ROOT / ".github/workflows/build-apk.yml").read_text()
        self.assertNotIn("<<<<<<<", text)
        self.assertIn("dependencyInventory", text)
        self.assertIn("generateReleaseSbom", text)
        self.assertIn("TgWatch.osv.json", text)
        self.assertIn("TgWatch.dependencies.json", text)
        self.assertIn("efd2147dae453c12288da5b3f33840f3dfb2f6b85032411f348fabb5e8e009d1", text)
        self.assertIn("v1.10.47", text)

    def test_new_version_and_keychain_wrapper_are_safe(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertNotIn("<<<<<<<", gradle)
        self.assertIn('versionName = "1.11"', gradle)
        self.assertIn("?: 12", gradle)
        wrapper = (SCRIPTS / "configure-signing.sh").read_text()
        self.assertNotIn("<<<<<<<", wrapper)
        self.assertIn("signing-keychain.py", wrapper)
        self.assertIn("--expected-certificate", wrapper)
        self.assertNotIn("-genkeypair", wrapper)


if __name__ == "__main__": unittest.main()
