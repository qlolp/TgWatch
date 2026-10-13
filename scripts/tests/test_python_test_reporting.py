import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPORTER = Path(__file__).resolve().parents[1] / "report-python-tests.py"


class PythonReportingTests(unittest.TestCase):
    def run_fixture(self, body):
        with tempfile.TemporaryDirectory() as directory:
            if body is not None:
                (Path(directory) / "test_fixture.py").write_text(
                    "import unittest\nclass Fixture(unittest.TestCase):\n"
                    "    def test_result(self):\n" + body)
            return subprocess.run([sys.executable, str(REPORTER), directory],
                                  capture_output=True, text=True, timeout=30)

    def test_success_executes_the_suite_without_failure_annotations(self):
        result = self.run_fixture("        self.assertEqual(1, 1)\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Ran 1 test", result.stderr)
        self.assertNotIn("::error", result.stdout)

    def test_failure_keeps_nonzero_exit_and_reports_the_real_assertion(self):
        result = self.run_fixture("        self.assertEqual(1, 2, 'synthetic failure')\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("synthetic failure", result.stdout)
        self.assertIn("::error title=Python regression failed::", result.stdout)

    def test_empty_discovery_cannot_report_success(self):
        result = self.run_fixture(None)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No Python regression tests", result.stdout)

    def test_multiline_percent_text_stays_inside_one_annotation(self):
        result = self.run_fixture("        self.fail('synthetic 100%\\nsecond line')\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("100%25%0Asecond line", result.stdout)
        self.assertEqual(1, len(result.stdout.strip().splitlines()))
