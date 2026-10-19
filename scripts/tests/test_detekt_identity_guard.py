"""Exercise the actual CLI against reference files and committed Git baselines."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "check-detekt-baseline.py"


class DetektIdentityGuardTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.reference = self.root / "reference.xml"
        self.current = self.root / "baseline.xml"

    def baseline(self, path, ids):
        path.write_text("<SmellBaseline><ManuallySuppressedIssues/><CurrentIssues>"
                        + "".join("<ID>" + value + "</ID>" for value in ids)
                        + "</CurrentIssues></SmellBaseline>", encoding="utf-8")

    def run_guard(self, *reference_args):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.current),
                               "--max", "3", *reference_args], cwd=self.root,
                              text=True, capture_output=True, check=False)

    def test_replacing_a_finding_at_the_same_count_is_rejected(self):
        self.baseline(self.reference, ["OldRule:existing", "OtherRule:existing"])
        self.baseline(self.current, ["NewRule:replacement", "OtherRule:existing"])
        result = self.run_guard("--reference", str(self.reference))
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("NewRule:replacement", result.stdout)

    def test_removing_findings_and_fixing_every_finding_are_allowed(self):
        self.baseline(self.reference, ["OldRule:existing", "OtherRule:existing"])
        for ids in (["OldRule:existing"], []):
            with self.subTest(ids=ids):
                self.baseline(self.current, ids)
                result = self.run_guard("--reference", str(self.reference))
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_removed_findings_cannot_be_reintroduced_under_the_original_budget(self):
        self.baseline(self.reference, ["RemainingRule:existing"])
        self.baseline(self.current, ["RemainingRule:existing", "RemovedRule:old"])
        result = self.run_guard("--reference", str(self.reference))
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)

    def test_unknown_or_unreadable_reference_is_not_a_clean_result(self):
        self.baseline(self.current, ["OldRule:existing"])
        for args in (("--reference", str(self.reference)), ("--base-ref", "missing-commit")):
            with self.subTest(args=args):
                result = self.run_guard(*args)
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)

    def test_git_reference_reads_the_committed_baseline_instead_of_the_worktree(self):
        env = dict(os.environ, GIT_AUTHOR_NAME="Guard Test", GIT_COMMITTER_NAME="Guard Test",
                   GIT_AUTHOR_EMAIL="guard@example.invalid", GIT_COMMITTER_EMAIL="guard@example.invalid")
        def git(*args):
            return subprocess.run(["git", *args], cwd=self.root, env=env,
                                  text=True, capture_output=True, check=True).stdout.strip()
        git("init", "-q")
        self.baseline(self.current, ["OldRule:existing"])
        git("add", "baseline.xml")
        git("commit", "-qm", "original baseline")
        original = git("rev-parse", "HEAD")
        self.baseline(self.current, ["NewRule:replacement"])
        result = self.run_guard("--base-ref", original)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("NewRule:replacement", result.stdout)


if __name__ == "__main__":
    unittest.main()
