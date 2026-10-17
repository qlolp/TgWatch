"""Execute the workflow's source guard against disposable Git repositories."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class ReleaseWorkspaceTests(unittest.TestCase):
    def workflow(self):
        return yaml.safe_load((ROOT / ".github/workflows/build-apk.yml").read_text())

    def guard(self):
        steps = self.workflow()["jobs"]["release"]["steps"]
        guard = next((step for step in steps if step.get("name") == "Verify pristine release sources after emulator"), None)
        self.assertIsNotNone(guard, "Publication needs a source guard before executing workspace validators")
        return guard["run"]

    def run_guard(self, mutation=None):
        guard = self.guard()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout.strip()
            git("init", "-q")
            git("config", "user.name", "Release guard fixture")
            git("config", "user.email", "release-guard@example.invalid")
            for name, content in (("version.properties", "version=1.16.0\n"),
                                  ("scripts/release-policy.py", "print('trusted policy')\n"),
                                  ("scripts/helper with spaces.py", "print('trusted helper')\n"),
                                  ("docs/releases/1.16.0.md", "Trusted notes\n")):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            git("add", ".")
            git("commit", "-qm", "Disposable guard fixture")
            sha = git("rev-parse", "HEAD")
            if mutation:
                mutation(root, git)
            return subprocess.run(["bash", "-c", guard], cwd=root,
                                  env=dict(os.environ, GITHUB_SHA=sha), capture_output=True, text=True)

    def test_checkout_credentials_are_not_persisted_in_any_job(self):
        for name, job in self.workflow()["jobs"].items():
            for step in job["steps"]:
                if step.get("uses", "").startswith("actions/checkout@"):
                    with self.subTest(job=name):
                        self.assertIs(False, step.get("with", {}).get("persist-credentials"))

    def test_clean_sources_with_explicit_generated_assets_are_allowed(self):
        def generate(root, git):
            for name in ("TgWatch.apk", "TgWatch.apk.sha256", "release-evidence/TgWatch.trivy.json",
                         "previous-latest/tag.txt", "signed-upgrade/latest/upgrade-after-targets.json"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("Generated artifact\n")
        result = self.run_guard(generate)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_policy_version_and_notes_changes_are_rejected(self):
        for path in ("version.properties", "scripts/release-policy.py", "docs/releases/1.16.0.md"):
            with self.subTest(path=path):
                result = self.run_guard(lambda root, git: (root / path).write_text("Changed source\n"))
                self.assertNotEqual(0, result.returncode)

    def test_staged_change_is_rejected_even_if_worktree_is_restored(self):
        def stage(root, git):
            path = root / "scripts/release-policy.py"
            original = path.read_text()
            path.write_text("Changed policy\n")
            git("add", "scripts/release-policy.py")
            path.write_text(original)
        self.assertNotEqual(0, self.run_guard(stage).returncode)

    def test_index_flags_cannot_hide_modified_policy(self):
        for flag in ("--assume-unchanged", "--skip-worktree"):
            for path in ("scripts/release-policy.py", "scripts/helper with spaces.py"):
                with self.subTest(flag=flag, path=path):
                    def hide(root, git):
                        git("update-index", flag, path)
                        (root / path).write_text("Changed hidden policy\n")
                    self.assertNotEqual(0, self.run_guard(hide).returncode)

    def test_git_stat_configuration_cannot_hide_same_size_policy_change(self):
        def hide(root, git):
            path = root / "scripts/release-policy.py"
            original = path.read_text()
            git("config", "core.trustctime", "false")
            git("config", "core.checkStat", "minimal")
            timestamp = 1_600_000_000_000_000_000
            os.utime(path, ns=(timestamp, timestamp))
            git("add", "scripts/release-policy.py")
            path.write_text(original.replace("trusted", "intrude"))
            os.utime(path, ns=(timestamp, timestamp))
        self.assertNotEqual(0, self.run_guard(hide).returncode)

    def test_clean_filter_cannot_transform_modified_policy_into_trusted_blob(self):
        def hide(root, git):
            path = root / "scripts/release-policy.py"
            git("config", "filter.review-shadow.clean", "sed s/intrude/trusted/g")
            (root / ".git/info/attributes").write_text("/scripts/release-policy.py filter=review-shadow\n")
            path.write_text(path.read_text().replace("trusted", "intrude"))
        self.assertNotEqual(0, self.run_guard(hide).returncode)

    def test_replacement_commit_cannot_redefine_expected_source_tree(self):
        def replace(root, git):
            original = git("rev-parse", "HEAD")
            (root / "scripts/release-policy.py").write_text("Changed replacement policy\n")
            git("add", "scripts/release-policy.py")
            tree = git("write-tree")
            replacement = git("commit-tree", tree, "-p", original, "-m", "Disposable replacement fixture")
            git("replace", original, replacement)
        self.assertNotEqual(0, self.run_guard(replace).returncode)

    def test_git_filemode_setting_cannot_hide_executable_source_change(self):
        def hide(root, git):
            git("config", "core.filemode", "false")
            path = root / "scripts/release-policy.py"
            path.chmod(path.stat().st_mode | 0o100)
        self.assertNotEqual(0, self.run_guard(hide).returncode)

    def test_changed_head_is_rejected(self):
        def new_head(root, git):
            (root / "version.properties").write_text("version=1.17.0\n")
            git("add", "version.properties")
            git("commit", "-qm", "Another disposable fixture commit")
        self.assertNotEqual(0, self.run_guard(new_head).returncode)

    def test_untracked_shadow_module_and_prefix_lookalike_are_rejected(self):
        for name in ("scripts/json.py", "TgWatch.malicious.py", "release-evidence/json.py"):
            with self.subTest(name=name):
                def add(root, git):
                    path = root / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("Untrusted code\n")
                self.assertNotEqual(0, self.run_guard(add).returncode)

    def test_git_excludes_cannot_hide_an_untracked_import(self):
        def hide(root, git):
            (root / "scripts/json.py").write_text("Untrusted JSON module\n")
            (root / ".git/info/exclude").write_text("scripts/json.py\n")
        self.assertNotEqual(0, self.run_guard(hide).returncode)

    def test_generated_build_directories_are_allowed(self):
        def build(root, git):
            for name in ("app/build/generated/sources/BuildConfig.java", ".gradle/cache.bin"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("Build output\n")
        result = self.run_guard(build)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_guard_runs_before_final_verification_and_inside_publication_step(self):
        steps = self.workflow()["jobs"]["release"]["steps"]
        guard = self.guard()
        guard_index = next(i for i, step in enumerate(steps) if step.get("run") == guard)
        final_index = next(i for i, step in enumerate(steps) if step.get("name") == "Re-verify the signed APK before publication")
        publish = next(step for step in steps if step.get("name") == "Publish versioned release")
        self.assertLess(guard_index, final_index)
        self.assertIn(guard.strip(), publish["run"], "Recheck sources before running publication policy with a write token")
        self.assertLess(publish["run"].index(guard.strip()), publish["run"].index("python3 scripts/release-policy.py"))

    def test_build_tools_are_pinned_and_checked_before_signing(self):
        workflow = self.workflow()
        self.assertEqual("35.0.0", workflow.get("env", {}).get("BUILD_TOOLS"))
        steps = workflow["jobs"]["release"]["steps"]
        install = next((i for i, step in enumerate(steps) if step.get("name") == "Ensure pinned Android build tools"), None)
        signing = next(i for i, step in enumerate(steps) if step.get("id") == "signed")
        self.assertIsNotNone(install)
        self.assertLess(install, signing)
        self.assertNotIn("build-tools/35.0.0/", str(steps))
        publish = next(step for step in steps if step.get("name") == "Publish versioned release")["run"]
        for tool in ("--apksigner", "--aapt2"):
            self.assertIn(tool, publish)


if __name__ == "__main__":
    unittest.main()
