"""Quality guards keep legacy debt shrinking and the shipped APK within a pinned budget."""
import importlib.util
import io
import contextlib
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    path = ROOT / "scripts" / filename
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DetektBaselineTests(unittest.TestCase):
    def setUp(self):
        self.module = load("check_detekt_baseline", "check-detekt-baseline.py")

    def baseline(self, body):
        handle = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False)
        handle.write(body)
        handle.close()
        return handle.name

    def test_counts_every_recorded_finding(self):
        path = self.baseline("""<?xml version="1.0"?>
<SmellBaseline><ManuallySuppressedIssues/><CurrentIssues>
<ID>LongMethod:Foo.kt$Foo$bar</ID><ID>MagicNumber:Foo.kt$Foo$1</ID>
</CurrentIssues></SmellBaseline>""")
        self.assertEqual(2, len(self.module.baseline_ids(path)))

    def test_fixed_empty_baseline_is_allowed_but_valueless_ids_are_an_error(self):
        self.assertEqual([], self.module.baseline_ids(self.baseline("<SmellBaseline/>")))
        for body in ("<NotABaseline/>",
                     "<SmellBaseline><CurrentIssues><ID>  </ID></CurrentIssues></SmellBaseline>"):
            with self.assertRaises(ValueError):
                self.module.baseline_ids(self.baseline(body))

    def test_budget_fails_only_when_it_grows(self):
        path = self.baseline("<SmellBaseline><CurrentIssues><ID>a</ID><ID>b</ID>"
                             "</CurrentIssues></SmellBaseline>")
        self.assertEqual(0, self.module.main([path, "--reference", path, "--max", "2"]))
        self.assertEqual(0, self.module.main([path, "--reference", path, "--max", "3"]))
        self.assertEqual(1, self.module.main([path, "--reference", path, "--max", "1"]))

    def test_missing_or_malformed_baseline_is_not_a_clean_result(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(1, self.module.main([str(Path(directory) / "missing.xml"), "--max", "0"]))
        self.assertEqual(1, self.module.main([self.baseline("<SmellBaseline>"), "--max", "0"]))


class ApkSizeTests(unittest.TestCase):
    def setUp(self):
        self.module = load("check_apk_size", "check-apk-size.py")
        self.directory = tempfile.TemporaryDirectory()
        self.apk = Path(self.directory.name) / "app-release.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("classes.dex", b"x" * 1_000)
            archive.writestr("classes2.dex", b"y" * 500)
            archive.writestr("resources.arsc", b"z" * 100)

    def tearDown(self):
        self.directory.cleanup()

    def test_reports_apk_and_dex_bytes(self):
        total, dex = self.module.apk_sizes(self.apk)
        self.assertEqual(self.apk.stat().st_size, total)
        self.assertEqual(1_500, dex)

    def test_budget_and_glob(self):
        budget = self.apk.stat().st_size
        self.assertEqual(0, self.module.main([str(self.apk), "--budget-bytes", str(budget)]))
        self.assertEqual(1, self.module.main([str(self.apk), "--budget-bytes", str(budget - 1)]))
        self.assertEqual(0, self.module.main([str(Path(self.directory.name) / "*.apk"),
                                              "--budget-bytes", str(budget)]))

    def test_missing_and_corrupt_inputs_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(1, self.module.main([str(Path(directory) / "*.apk"),
                                                  "--budget-bytes", "1"]))
            corrupt = Path(directory) / "corrupt.apk"
            corrupt.write_bytes(b"not a zip")
            self.assertEqual(1, self.module.main([str(corrupt), "--budget-bytes", "1_000_000"]))

    def test_zero_budget_is_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.module.main([str(self.apk), "--budget-bytes", "0"])


if __name__ == "__main__":
    unittest.main()
