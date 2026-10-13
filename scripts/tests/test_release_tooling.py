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
        apk_fixture = b"APK fixture inspected by external Android tool mocks"
        checksum = hashlib.sha256(apk_fixture).hexdigest() + "  TgWatch.apk\n"
        for name, data in (("TgWatch.apk", apk_fixture), ("TgWatch.apk.sha256", checksum.encode()),
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
    elif '/compare/' in endpoint:
        comparisons = sum('/compare/' in line for line in Path(os.environ['MOCK_LOG']).read_text().splitlines())
        if state.get('mutate_on_compare') and comparisons == 2:
            Path(os.environ['MOCK_STATE']).parent.joinpath(state['mutate_on_compare']).write_bytes(b'changed after validation')
        print(json.dumps({'status': state.get('main_status', 'ahead')}))
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
        self.mock("apksigner", '''import json, os, sys
from pathlib import Path
state = json.loads(Path(os.environ['MOCK_STATE']).read_text())
if state.get('signature_error'): print('Invalid APK signature', file=sys.stderr); sys.exit(1)
print('Signer #1 certificate SHA-256 digest: ' + state.get('certificate',
      'ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1'))
if state.get('extra_signer'): print('Signer #2 certificate SHA-256 digest: ' + 'b' * 64)
''')
        self.mock("aapt2", '''import json, os, sys
from pathlib import Path
state = json.loads(Path(os.environ['MOCK_STATE']).read_text())
if state.get('aapt_error'): print('Invalid APK manifest', file=sys.stderr); sys.exit(1)
print(state.get('badging', "package: name='ru.tgwatch' versionCode='1013000' versionName='1.13.0'"))
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
        self.assertRegex(text, r'testImplementation\("org\.json:json:[0-9]{8}"\)')
        self.assertNotRegex(text, r'(?m)^\s*(?:implementation|api|runtimeOnly)\("org\.json:json:')


class LockedRequirementsTests(unittest.TestCase):
    def check(self, requirements, lock, *, source_requirements=None):
        script = SCRIPTS / "lock-requirements.py"
        self.assertTrue(script.is_file(), "Missing requirements lock generator/checker")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, destination = root / "requirements.txt", root / "requirements.lock"
            source.write_text(requirements)
            manifest = requirements if source_requirements is None else source_requirements
            destination.write_text("# Source requirements SHA256: " + hashlib.sha256(manifest.encode()).hexdigest() + "\n" + lock)
            return subprocess.run([sys.executable, str(script), "--check", str(source), str(destination)],
                                  text=True, capture_output=True)

    def test_direct_pins_can_be_checked_offline_with_transitive_hashes(self):
        result = self.check("# direct packages\njsonschema==4.23.0\nPyYAML==6.0.2\n",
                            "jsonschema==4.23.0 \\\n    --hash=sha256:" + "a" * 64 + "\n" +
                            "pyyaml==6.0.2 \\\n    --hash=sha256:" + "b" * 64 + "\n" +
                            "attrs==26.1.0 \\\n    --hash=sha256:" + "c" * 64 + "\n")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_changed_direct_pin_requires_regenerating_lock(self):
        result = self.check("jsonschema==4.24.0\n", "jsonschema==4.23.0 \\\n    --hash=sha256:" + "a" * 64 + "\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("regenerate", result.stderr.lower())
        self.assertIn("jsonschema==4.24.0", result.stderr)

    def test_missing_direct_dependency_fails_lock_check(self):
        result = self.check("PyYAML==6.0.2\n", "attrs==26.1.0 \\\n    --hash=sha256:" + "a" * 64 + "\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("PyYAML==6.0.2", result.stderr)

    def test_missing_distribution_hash_fails_lock_check(self):
        result = self.check("jsonschema==4.23.0\n", "jsonschema==4.23.0\n")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("hash", result.stderr.lower())

    def test_removing_direct_requirement_requires_regenerating_lock(self):
        original = "jsonschema==4.23.0\nPyYAML==6.0.2\n"
        result = self.check("PyYAML==6.0.2\n",
                            "jsonschema==4.23.0 \\\n    --hash=sha256:" + "a" * 64 + "\n" +
                            "PyYAML==6.0.2 \\\n    --hash=sha256:" + "b" * 64 + "\n",
                            source_requirements=original)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("regenerate", result.stderr.lower())


class SignedUpgradeWorkflowTests(unittest.TestCase):
    def setUp(self):
        import yaml
        workflow = yaml.safe_load((ROOT / ".github/workflows/build-apk.yml").read_text())
        steps = workflow["jobs"]["release"]["steps"]
        self.script = next(step["with"]["script"] for step in steps
                           if "signed-upgrade-smoke.sh" in step.get("with", {}).get("script", ""))
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        scripts, tools = self.work / "scripts", self.work / "bin"
        scripts.mkdir()
        tools.mkdir()
        for name in ("signed-upgrade-smoke.sh", "upgrade-policy.py"):
            (scripts / name).write_text((SCRIPTS / name).read_text())
        self.log, self.state = self.work / "calls.jsonl", self.work / "state.json"
        self.state.write_text(json.dumps({"installed": None, "checked": 0, "prefs": "", "history": ""}))
        (scripts / "adb-readiness.py").write_text("import json, os, sys\n"
            "with open(os.environ['UPGRADE_LOG'], 'a') as stream: "
            "stream.write(json.dumps(['readiness'] + sys.argv[1:]) + '\\n')\n")
        adb = tools / "adb"
        adb.write_text('''#!/usr/bin/env python3
import json, os, sys, xml.etree.ElementTree as ET
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['UPGRADE_LOG'], 'a') as stream:
    stream.write(json.dumps(['adb'] + args) + '\\n')
path = Path(os.environ['UPGRADE_STATE'])
state = json.loads(path.read_text())
if args[0] in ('root', 'wait-for-device'): pass
elif args[:3] == ['shell', 'id', '-u']: print('0')
elif args[0] == 'install':
    if '-r' not in args:
        if state['installed'] is not None: sys.exit(17)
        state.update(installed=args[-1], checked=0, prefs='<map><long name="last_checked" value="0"/></map>', history='')
    elif state['installed'] is None: sys.exit(18)
    else: state['installed'] = args[-1]
elif args[0] == 'uninstall':
    if state['installed'] is None: sys.exit(19)
    state.update(installed=None, checked=0, prefs='', history='')
elif args[:3] == ['shell', 'am', 'start']:
    state['checked'] += 100
    tree = ET.fromstring(state['prefs'])
    tree.find(".//*[@name='last_checked']").set('value', str(state['checked']))
    state['prefs'] = ET.tostring(tree, encoding='unicode')
    state['history'] += str(state['checked']) + ',400,ONLINE,10,1\\n'
elif args[:3] == ['shell', 'am', 'force-stop']: pass
elif args[:2] == ['shell', 'cat']:
    print(state['prefs'] if 'shared_prefs' in args[2] else state['history'])
elif args[0] == 'pull':
    Path(args[2]).write_text(state['prefs'] if 'shared_prefs' in args[1] else state['history'])
elif args[0] == 'push': state['seeded'] = Path(args[1]).read_text()
elif args[0] == 'shell' and args[1].startswith('cat /data/local/tmp/'):
    state['prefs'] = state['seeded']
elif args[:2] == ['exec-out', 'screencap']: sys.stdout.buffer.write(b'fixture screenshot')
elif args[0] == 'logcat': print('fixture upgrade logcat')
else: raise AssertionError(args)
path.write_text(json.dumps(state))
''')
        adb.chmod(0o755)
        self.env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"],
                        UPGRADE_LOG=str(self.log), UPGRADE_STATE=str(self.state))

    def run_paths(self, latest=True):
        script = self.script.replace("${{ steps.latest_baseline.outputs.available }}", str(latest).lower())
        # The pinned emulator runner parses each noncomment line as a separate sh -c command.
        for command in (line.strip() for line in script.splitlines()):
            if command and not command.startswith("#"):
                result = subprocess.run(["sh", "-c", command], cwd=self.work, env=self.env, text=True, capture_output=True)
                self.assertEqual(0, result.returncode, result.stderr)
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_both_upgrade_pairs_preserve_data_and_reset_only_between_pairs(self):
        calls = self.run_paths()
        installations = [(index, call[-1]) for index, call in enumerate(calls) if call[:2] == ["adb", "install"]]
        self.assertEqual(["previous/TgWatch.apk", "TgWatch.apk", "previous-latest/TgWatch.apk", "TgWatch.apk"],
                         [apk for _, apk in installations])
        resets = [index for index, call in enumerate(calls) if call[:2] == ["adb", "uninstall"]]
        self.assertEqual(1, len(resets))
        self.assertLess(installations[1][0], resets[0])
        self.assertLess(resets[0], installations[2][0])
        self.assertFalse(any(call[:3] == ["adb", "shell", "pm"] for call in calls))
        self.assertLess(next(i for i, call in enumerate(calls) if call[:1] == ["readiness"]), installations[0][0])
        for path in ("legacy", "latest"):
            reports = self.work / "signed-upgrade" / path
            self.assertTrue((reports / "signed-upgrade-logcat.txt").is_file())
            self.assertTrue((reports / "upgrade-seeded-prefs.xml").is_file())
            self.assertTrue((reports / "upgrade-before-history.csv").is_file())
            self.assertTrue((reports / "upgrade-after-history.csv").is_file())

    def test_first_release_still_runs_legacy_gate(self):
        calls = self.run_paths(latest=False)
        self.assertEqual(["previous/TgWatch.apk", "TgWatch.apk"],
                         [call[-1] for call in calls if call[:2] == ["adb", "install"]])
        self.assertFalse(any(call[:2] == ["adb", "uninstall"] for call in calls))
        self.assertTrue((self.work / "signed-upgrade/legacy/upgrade-after-prefs.xml").is_file())


class FinalSignedApkWorkflowTests(unittest.TestCase):
    def run_guard(self, *, tamper=False, fingerprint=None, name="1.15.0", code="1015000"):
        import yaml
        steps = yaml.safe_load((ROOT / ".github/workflows/build-apk.yml").read_text())["jobs"]["release"]["steps"]
        guard = next(step for step in steps if step.get("name") == "Re-verify the signed APK before publication")
        self.assertNotIn("if", guard, "Final signed-byte and production-identity gate must be unconditional")
        self.assertEqual("${{ steps.signed.outputs.apk_sha256 }}", guard["env"]["APK_SHA256"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = hashlib.sha256(b"original signed APK").hexdigest()
            data = b"substituted APK" if tamper else b"original signed APK"
            (root / "TgWatch.apk").write_bytes(data)
            (root / "TgWatch.apk.sha256").write_text(hashlib.sha256(data).hexdigest() + "  TgWatch.apk\n")
            (root / "version.properties").write_text("version=1.15.0\n")
            tools = root / "sdk/build-tools/35.0.0"
            tools.mkdir(parents=True)
            certificate = fingerprint or "ab74727a44f59684045e7db4c820be309f886433a18a3c06f3439417f3acbef1"
            for executable, output in (("apksigner", "Signer #1 certificate SHA-256 digest: " + certificate),
                                       ("aapt2", f"package: name='ru.tgwatch' versionCode='{code}' versionName='{name}'")):
                path = tools / executable
                path.write_text("#!/usr/bin/env python3\nprint(" + repr(output) + ")\n")
                path.chmod(0o755)
            env = dict(os.environ, APK_SHA256=original, ANDROID_HOME=str(root / "sdk"), BUILD_TOOLS="35.0.0")
            return subprocess.run(["bash", "-c", guard["run"]], cwd=root, env=env, text=True, capture_output=True)

    def test_original_signed_apk_can_pass_publication_guard(self):
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_replacing_both_apk_and_checksum_cannot_replace_original_signed_bytes(self):
        self.assertNotEqual(0, self.run_guard(tamper=True).returncode)

    def test_production_certificate_is_rechecked_after_emulator(self):
        self.assertNotEqual(0, self.run_guard(fingerprint="0" * 64).returncode)

    def test_version_name_is_rechecked_after_emulator(self):
        self.assertNotEqual(0, self.run_guard(name="1.14.0").returncode)

    def test_version_code_is_rechecked_after_emulator(self):
        self.assertNotEqual(0, self.run_guard(code="1014000").returncode)


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
