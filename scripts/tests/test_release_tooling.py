"""Offline policy regression tests; gh, keytool, openssl and Trivy are disposable mocks."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
SCAN_ARTIFACT = "app/build/reports/sbom/TgWatch.sbom.cdx.json"
LIBRARY = {"type": "library", "group": "org.example", "name": "library", "version": "1.0",
           "purl": "pkg:maven/org.example/library@1.0?type=jar", "bom-ref": "pkg:maven/org.example/library@1.0?type=jar",
           "hashes": [{"alg": "SHA-256", "content": "a" * 64}]}
BOM = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1, "components": [LIBRARY]}


def scan_fixture(vulnerabilities=None, results=True, artifact=SCAN_ARTIFACT):
    scan = {"SchemaVersion": 2, "CreatedAt": "2026-10-10T00:00:00Z", "ArtifactName": artifact, "ArtifactType": "cyclonedx"}
    if results:
        result = {"Target": "Java", "Class": "lang-pkgs", "Type": "jar", "Packages": [
            {"Name": "org.example:library", "Version": "1.0", "Identifier": {"PURL": LIBRARY["purl"]}}]}
        if vulnerabilities is not None:
            result["Vulnerabilities"] = vulnerabilities
        scan["Results"] = [result]
    return scan


class ToolFixture:
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.bin = self.work / "bin"
        self.bin.mkdir()
        self.log = self.work / "calls.jsonl"
        self.state = self.work / "state.json"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        MOCK_LOG=str(self.log), MOCK_STATE=str(self.state))
        packages = [{"group": "org.example", "name": "library", "version": "1.0", "scopes": ["releaseRuntimeClasspath"]},
                    {"group": "junit", "name": "junit", "version": "4.13.2", "scopes": ["debugUnitTestRuntimeClasspath"]}]
        audit = {"checked_at": "2026-10-10T00:00:00+00:00", "source": "https://api.osv.dev/v1/query",
                 "packages": [dict(package, vulnerabilities=[]) for package in packages]}
        self.files = []
        for name, data in (("TgWatch.apk", b"apk"), ("TgWatch.apk.sha256", b"checksum"),
                           ("TgWatch.sbom.cdx.json", json.dumps(BOM).encode()),
                           ("TgWatch.dependencies.json", json.dumps({"packages": packages}).encode()),
                           ("TgWatch.osv.json", json.dumps(audit).encode()),
                           ("TgWatch.trivy.json", json.dumps(scan_fixture()).encode())):
            path = self.work / name
            path.write_bytes(data)
            self.files.append(path)
        self.fixture = {"releases": [], "latest": None, "sha": "a" * 40}
        self.mock("gh", '''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['MOCK_LOG'], 'a') as f: f.write(json.dumps(['gh'] + args) + '\\n')
state = json.loads(Path(os.environ['MOCK_STATE']).read_text())
if args[0] == 'api':
    endpoint = args[1]
    if state.get('api_error'): print('API failed', file=sys.stderr); sys.exit(1)
    if endpoint.endswith('/releases/latest'):
        if state['latest'] is None: print('HTTP 404: Not Found', file=sys.stderr); sys.exit(1)
        print(json.dumps(state['latest']))
    elif '/releases?' in endpoint: print(json.dumps(state['releases']))
    elif '/compare/' in endpoint: print(json.dumps({'status': state.get('main_status', 'ahead')}))
    elif '/git/ref/tags/' in endpoint:
        if state.get('tag_missing'): print('HTTP 404: Not Found', file=sys.stderr); sys.exit(1)
        print(json.dumps({'object': state.get('tag_object', {'type':'commit', 'sha':state.get('tag_shas', {}).get(endpoint.rsplit('/', 1)[-1], state['sha'])})}))
    elif '/git/tags/' in endpoint: print(json.dumps({'object': {'type':'commit', 'sha':state['sha']}}))
    else: raise AssertionError(endpoint)
elif args[:2] == ['release', 'download']:
    dest = Path(args[args.index('--dir') + 1]); dest.mkdir(exist_ok=True)
    name = args[args.index('--pattern') + 1]
    data = state.get('download', {}).get(name)
    (dest / name).write_bytes(data.encode() if data is not None else Path(os.environ['MOCK_STATE']).parent.joinpath(name).read_bytes())
elif args[:2] == ['secret', 'list']:
    if state.get('secret_list_error'): print('API failed', file=sys.stderr); sys.exit(1)
    print('\\n'.join(state.get('secrets', [])))
elif args[:2] in (['release', 'create'], ['secret', 'set']): pass
else: raise AssertionError(args)
''')

    def mock(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env python3\n" + body)
        path.chmod(0o755)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def run_release(self, version="1.13.0", **overrides):
        self.state.write_text(json.dumps(self.fixture))
        source = self.work / "version.properties"
        source.write_text(f"version={version}\n")
        notes = self.work / "notes.md"
        notes.write_text("Fixture release notes\n")
        return subprocess.run([sys.executable, str(SCRIPTS / "release-policy.py"),
                               "--repo", overrides.get("repo", "owner/repo"), "--version-file", str(source),
                               "--ref", overrides.get("ref", "refs/heads/main"),
                               "--sha", "a" * 40, "--notes", str(notes), "--assets",
                               *map(str, self.files)], env=self.env, text=True, capture_output=True)

    def created(self):
        return [call for call in self.calls() if call[1:3] == ["release", "create"]]


class ReleaseTests(ToolFixture, unittest.TestCase):
    def test_first_release_is_latest(self):
        self.fixture["tag_missing"] = True
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--latest=true", self.created()[0])
        self.assertIn("v1.13.0", self.created()[0])

    def test_orphan_existing_tag_other_commit_cannot_publish(self):
        self.fixture["sha"] = "b" * 40
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())

    def test_orphan_matching_annotated_tag_can_publish(self):
        self.fixture["tag_object"] = {"type": "tag", "sha": "c" * 40}
        result = self.run_release(ref="refs/tags/v1.13.0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(self.created()))

    def test_non_monotonic_release_is_refused(self):
        for tag in ("v1.14.0", "v1.13.1"):
            with self.subTest(tag=tag):
                self.fixture["latest"] = {"tag_name": tag, "draft": False}
                result = self.run_release()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.created())

    def test_legacy_published_version_allows_upgrade(self):
        self.fixture["latest"] = {"tag_name": "v1.10.47", "draft": False}
        result = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--latest=true", self.created()[0])

    def pinned_legacy_build(self):
        self.fixture["tag_shas"] = {"build": "fef8aef77da5a8b5d2363ef2167deab3a3d4d4d2"}
        return {"id": 408759859, "tag_name": "build", "draft": False, "assets": [{
            "id": 627276037, "name": "TgWatch.apk", "size": 905179,
            "digest": "sha256:0a5f4a61d30bb18f2d9f00117630657d5a0e790c3e593a18560ffcd927d53368"}]}

    def test_pinned_legacy_rolling_build_inventory_allows_publication(self):
        self.fixture["latest"] = {"tag_name": "v1.12.59", "draft": False}
        self.fixture["releases"] = [self.fixture["latest"], self.pinned_legacy_build()]
        result = self.run_release(repo="qlolp/TgWatch")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--latest=true", self.created()[0])

    def test_pinned_legacy_rolling_build_as_latest_allows_publication(self):
        build = self.pinned_legacy_build()
        self.fixture["latest"] = build
        self.fixture["releases"] = [build]
        result = self.run_release(repo="qlolp/TgWatch")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--latest=true", self.created()[0])

    def test_unpinned_rolling_build_inventory_or_latest_refuses_publication(self):
        for latest in (False, True):
            with self.subTest(latest=latest):
                build = {"tag_name": "build", "draft": False}
                self.fixture["latest"] = build if latest else None
                self.fixture["releases"] = [] if latest else [build]
                result = self.run_release(repo="qlolp/TgWatch")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Unrecognized or changed legacy build", result.stderr)
                self.assertFalse(self.created())

    def test_malformed_versioned_tag_still_blocks_publication(self):
        self.fixture["releases"] = [{"tag_name": "v1.10.0", "draft": False}]
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())

    def test_newer_published_nonlatest_blocks_rollback(self):
        self.fixture["latest"] = {"tag_name": "v1.12.59", "draft": False}
        self.fixture["releases"] = [{"tag_name": "v1.14.0", "draft": False}]
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())

    def test_nonlatest_and_draft_releases_also_block_reused_version(self):
        for draft in (False, True):
            with self.subTest(draft=draft):
                self.fixture["releases"] = [{"tag_name": "v1.13.0", "draft": draft}]
                result = self.run_release()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("immutable", result.stderr)
                self.assertFalse(self.created())
                self.assertFalse(any(call[1:3] in (["release", "edit"], ["release", "upload"]) for call in self.calls()))

    def test_unknown_latest_code_fails_closed(self):
        self.fixture["latest"] = {"tag_name": "unrecognized", "draft": False}
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())

    def test_api_failure_does_not_become_first_release(self):
        self.fixture["api_error"] = True
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())

    def test_invalid_source_version_fails_before_api(self):
        for value in ("", "1.13", "1.13.00", "1.13.0-beta", "2147.483.648"):
            with self.subTest(value=value):
                if self.log.exists(): self.log.unlink()
                result = self.run_release(version=value)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.calls())

    def test_removed_override_arguments_are_rejected(self):
        result = subprocess.run([sys.executable, str(SCRIPTS / "release-policy.py"),
                                 "--repo", "owner/repo", "--sha", "a" * 40,
                                 "--ref", "refs/heads/main", "--run-number", "40"],
                                env=self.env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.calls())

    def test_new_release_cannot_publish_high_vulnerability(self):
        next(path for path in self.files if path.name == "TgWatch.trivy.json").write_text(
            json.dumps(scan_fixture([{"Severity": "HIGH"}])))
        result = self.run_release()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.created())


class SigningTests(ToolFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.directory = self.work / "backup"
        for name in ("keytool", "openssl"):
            self.mock(name, '''import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['MOCK_LOG'], 'a') as f: f.write(json.dumps([name] + args) + '\\n')
if name == 'openssl': print('mock-password')
elif '-genkeypair' in args: Path(args[args.index('-keystore') + 1]).write_bytes(b'mock-store')
elif os.environ.get('MOCK_INVALID_KEY'): sys.exit(1)
elif os.environ.get('MOCK_CERTIFICATE_ONLY'): print('Entry type: trustedCertEntry')
else: print('Entry type: PrivateKeyEntry')
''')

    def run_signing(self, *args):
        self.state.write_text(json.dumps(self.fixture))
        return subprocess.run(["bash", str(SCRIPTS / "configure-signing.sh"), *args],
                               cwd=self.work, env=self.env, text=True, capture_output=True)

    def backup(self):
        self.directory.mkdir()
        (self.directory / "tgwatch-release.p12").write_bytes(b"restored-store")
        (self.directory / "tgwatch-signing-password.txt").write_text("mock-password\n")

    def test_missing_existing_backup_does_not_create_key(self):
        result = self.run_signing(str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.calls())

    def test_restore_missing_backup_never_generates(self):
        result = self.run_signing("restore", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("-genkeypair" in call or call[0] == "openssl" for call in self.calls()))

    def test_legacy_restore_mode_cannot_bypass_existing_keychain_interface(self):
        self.backup()
        result = self.run_signing("restore", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("-genkeypair" in call or call[0] == "openssl" for call in self.calls()))
        self.assertEqual(0, sum(call[1:3] == ["secret", "set"] for call in self.calls()))
        self.assertNotIn("mock-password", result.stdout + result.stderr)

    def test_restore_wrong_password_never_writes_secrets(self):
        self.backup()
        self.env["MOCK_INVALID_KEY"] = "1"
        result = self.run_signing("restore", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(call[1:3] == ["secret", "set"] for call in self.calls()))

    def test_restore_certificate_only_backup_is_not_a_signing_key(self):
        self.backup()
        self.env["MOCK_CERTIFICATE_ONLY"] = "1"
        result = self.run_signing("restore", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(call[1:3] == ["secret", "set"] for call in self.calls()))

    def test_init_cannot_treat_secret_inspection_failure_as_empty(self):
        self.fixture["secret_list_error"] = True
        result = self.run_signing("init", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("-genkeypair" in call or call[1:3] == ["secret", "set"] for call in self.calls()))

    def test_init_never_creates_a_new_key_even_without_existing_secrets(self):
        result = self.run_signing("init", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(0, sum("-genkeypair" in call for call in self.calls()))
        self.assertEqual(0, sum(call[1:3] == ["secret", "set"] for call in self.calls()))

    def test_init_existing_secrets_refuses_before_generation(self):
        self.fixture["secrets"] = ["TGWATCH_KEYSTORE_BASE64"]
        result = self.run_signing("init", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("-genkeypair" in call or call[1:3] == ["secret", "set"] for call in self.calls()))

    def test_init_replacement_flag_cannot_rotate_a_key(self):
        self.fixture["secrets"] = ["TGWATCH_KEYSTORE_BASE64"]
        result = self.run_signing("init", str(self.directory), "--allow-replace-existing-secrets")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any("-genkeypair" in call for call in self.calls()))

    def test_wrapper_delegates_existing_directory_with_pinned_certificate(self):
        self.backup()
        interpreter = self.bin / "python3"
        interpreter.write_text("#!" + sys.executable + "\nimport os,json,sys\n" +
                               "with open(os.environ['MOCK_LOG'],'a') as f: f.write(json.dumps(['python3']+sys.argv[1:])+'\\n')\n")
        interpreter.chmod(0o755)
        result = self.run_signing(str(self.directory))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([["python3", str(SCRIPTS / "signing-keychain.py"), "configure", str(self.directory),
                           "--expected-certificate", "AB74727A44F59684045E7DB4C820BE309F886433A18A3C06F3439417F3ACBEF1"]], self.calls())

    def test_init_existing_backup_refuses_to_overwrite(self):
        self.backup()
        result = self.run_signing("init", str(self.directory))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"restored-store", (self.directory / "tgwatch-release.p12").read_bytes())
        self.assertFalse(any("-genkeypair" in call for call in self.calls()))


class SbomTests(unittest.TestCase):
    def test_resolved_inventory_preserves_coordinates_and_artifact_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "library.aar"
            artifact.write_bytes(b"resolved artifact")
            inventory = root / "inventory.json"
            inventory.write_text(json.dumps({"applicationVersion": "1.10", "artifacts": [
                {"group": "org.example", "name": "library", "version": "2.3",
                 "type": "aar", "classifier": "", "file": str(artifact)}]}))
            output = root / "sbom.json"
            result = subprocess.run([sys.executable, str(SCRIPTS / "generate-sbom.py"),
                                     str(inventory), str(output)], text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            bom = json.loads(output.read_text())
            component = bom["components"][0]
            self.assertEqual("pkg:maven/org.example/library@2.3?type=aar", component["purl"])
            self.assertEqual("2.3", component["version"])
            self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), component["hashes"][0]["content"])
            self.assertEqual([component["bom-ref"]], bom["dependencies"][0]["dependsOn"])

    def test_missing_artifact_or_coordinate_is_not_silently_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = root / "inventory.json"
            for entry in ({"group": "org.example", "name": "library", "version": "1", "type": "jar", "file": "missing"},
                          {"file": "missing"}):
                inventory.write_text(json.dumps({"applicationVersion": "1.10", "artifacts": [entry]}))
                result = subprocess.run([sys.executable, str(SCRIPTS / "generate-sbom.py"),
                                         str(inventory), str(root / "sbom.json")], text=True, capture_output=True)
                self.assertNotEqual(0, result.returncode)


class ScanTests(unittest.TestCase):
    def run_scan(self, report, exit_code=0, stale=False, empty_bom=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            trivy = root / "trivy"
            if report is not None:
                try:
                    parsed = json.loads(report)
                    if isinstance(parsed, dict) and parsed.get("ArtifactName") == "input.json":
                        parsed["ArtifactName"] = str(root / "input.json")
                        report = json.dumps(parsed)
                except ValueError:
                    pass
            trivy.write_text("#!/usr/bin/env python3\nimport json,sys\nfrom pathlib import Path\n" +
                             "args=sys.argv[1:]\nassert args[0]=='sbom'\n" +
                             "assert args[args.index('--severity')+1]=='HIGH,CRITICAL'\n" +
                             "assert args[args.index('--exit-code')+1]=='1'\n" +
                             "assert '--list-all-pkgs' in args\n" +
                             ("Path(args[args.index('--output')+1]).write_text(" + repr(report) + ")\n"
                              if report is not None else "") + "sys.exit(" + str(exit_code) + ")\n")
            trivy.chmod(0o755)
            bom = dict(BOM, components=[] if empty_bom else BOM["components"])
            (root / "input.json").write_text(json.dumps(bom))
            output = root / "scan.json"
            if stale: output.write_text('{"SchemaVersion":2,"Results":[]}')
            result = subprocess.run(["bash", str(SCRIPTS / "scan-sbom.sh"), str(root / "input.json"), str(output)],
                                    env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"]),
                                    capture_output=True, text=True)
            status = json.loads((root / "scan.status.json").read_text())
            return result, status, output.exists()

    def test_clean_complete_scan_passes(self):
        result, status, exists = self.run_scan(json.dumps(scan_fixture(artifact="input.json")))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("passed", status["status"])
        self.assertTrue(exists)

    def test_high_vulnerabilities_fail_with_report(self):
        report = json.dumps(scan_fixture([{"Severity": "HIGH"}], artifact="input.json"))
        result, status, exists = self.run_scan(report, exit_code=1)
        self.assertEqual(1, result.returncode)
        self.assertEqual("vulnerabilities", status["status"])
        self.assertTrue(exists)

    def test_critical_vulnerabilities_cannot_pass_even_with_zero_exit(self):
        report = json.dumps(scan_fixture([{"Severity": "CRITICAL"}], artifact="input.json"))
        result, status, _ = self.run_scan(report)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("vulnerabilities", status["status"])

    def test_offline_unavailable_discards_stale_clean_report(self):
        result, status, exists = self.run_scan(None, exit_code=1, stale=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("unavailable", status["status"])
        self.assertFalse(exists)

    def test_scanner_exit_code_is_preserved(self):
        result, status, _ = self.run_scan(None, exit_code=127)
        self.assertEqual(127, result.returncode)
        self.assertEqual(127, status["scannerExitCode"])

    def test_malformed_report_is_not_clean(self):
        result, status, _ = self.run_scan('{}')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("unavailable", status["status"])

    def test_absent_or_null_results_cannot_be_accepted_as_clean(self):
        for report in ('{"SchemaVersion":2}', '{"SchemaVersion":2,"Results":null}'):
            with self.subTest(report=report):
                result, status, _ = self.run_scan(report)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("unavailable", status["status"])

    def test_authentic_empty_bom_scan_may_omit_results(self):
        result, status, _ = self.run_scan(json.dumps(scan_fixture(results=False, artifact="input.json")), empty_bom=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("passed", status["status"])

    def test_nonempty_bom_requires_package_coverage(self):
        report = scan_fixture(artifact="input.json")
        report["Results"][0]["Packages"] = []
        result, status, _ = self.run_scan(json.dumps(report))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("unavailable", status["status"])

    def test_report_for_different_artifact_cannot_pass(self):
        result, status, _ = self.run_scan(json.dumps(scan_fixture(artifact="other.json")))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("unavailable", status["status"])

    def test_malformed_result_cannot_be_accepted_as_clean(self):
        for report in ('{"SchemaVersion":2,"Results":[null]}',
                       '{"SchemaVersion":2,"Results":[{"Vulnerabilities":[{}]}]}'):
            with self.subTest(report=report):
                result, status, _ = self.run_scan(report)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("unavailable", status["status"])


class WorkflowTests(unittest.TestCase):
    def test_publications_are_serial_and_checks_cannot_cancel_them(self):
        import yaml
        workflow = yaml.safe_load((ROOT / ".github/workflows/build-apk.yml").read_text())
        self.assertNotIn("concurrency", workflow)
        release = workflow["jobs"]["release"]["concurrency"]
        self.assertIs(False, release["cancel-in-progress"])
        self.assertNotIn("github.ref", release["group"])
        for name in ("build", "android"):
            group = workflow["jobs"][name]["concurrency"]["group"]
            self.assertIn("github.event_name", group)
            self.assertIn("github.run_id", group)

    def test_smoke_matches_fresh_timestamp_and_signed_gate_remains(self):
        smoke = (SCRIPTS / "android-smoke.sh").read_text()
        self.assertIn('$1 == checked', smoke)
        workflow = (ROOT / ".github/workflows/build-apk.yml").read_text()
        self.assertIn("signed-upgrade-smoke.sh previous/TgWatch.apk TgWatch.apk", workflow)
        self.assertIn("efd2147dae453c12288da5b3f33840f3dfb2f6b85032411f348fabb5e8e009d1", workflow)

    def test_ci_scans_sbom_and_keeps_failed_scan_reports(self):
        text = (ROOT / ".github/workflows/build-apk.yml").read_text()
        self.assertIn("generateReleaseSbom", text)
        self.assertIn("scan-sbom.sh", text)
        self.assertIn("sbom-reports", text)
        self.assertIn("download-artifact", text)

    def test_version_code_is_validated_instead_of_falling_back(self):
        text = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('versionName = sourceVersion', text)
        self.assertIn('versionCode = sourceVersionCode.toInt()', text)
        self.assertNotIn("toIntOrNull()?.plus(100)", text)
        self.assertIn("2147483647", text)
        self.assertNotIn("GITHUB_RUN_NUMBER", text)
        self.assertIn('testImplementation("org.json:json:20240303")', text)


class AndroidSmokeTests(unittest.TestCase):
    def run_observation(self, history, foreground=True, mutate=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text("#!/usr/bin/env python3\nimport sys\nargs=' '.join(sys.argv[1:])\n" +
                           "if 'shared_prefs' in args: print('<long name=\"last_checked\" value=\"200\" />')\n" +
                           "elif 'history-v2.csv' in args: print(" + repr(history) + ")\n" +
                           "elif 'dumpsys activity services' in args: print(" + repr("isForeground=" + str(foreground).lower()) + ")\n")
            adb.chmod(0o755)
            prefix = (SCRIPTS / "android-smoke.sh").read_text().split("bash ./gradlew connectedDebugAndroidTest", 1)[0]
            if mutate:
                prefix = prefix.replace("$1 == checked", "$1 >= 0")
            return subprocess.run(["bash"], input=prefix + '\nsleep() { :; }\nawait_observation 100\n',
                                  cwd=root, env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"]),
                                  text=True, capture_output=True)

    def test_fresh_timestamp_requires_its_matching_history_row(self):
        result = self.run_observation("200,400,ONLINE,10,1")
        self.assertEqual(0, result.returncode, result.stderr)
        stale = self.run_observation("100,400,ONLINE,10,1")
        self.assertNotEqual(0, stale.returncode)
        # Mutation reproduces the old bug: any old row falsely satisfied the gate.
        regression = self.run_observation("100,400,ONLINE,10,1", mutate=True)
        self.assertEqual(0, regression.returncode, regression.stderr)

    def test_history_row_does_not_replace_foreground_service_requirement(self):
        result = self.run_observation("200,400,ONLINE,10,1", foreground=False)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
