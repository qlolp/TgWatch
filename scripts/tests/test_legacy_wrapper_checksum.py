"""The immutable legacy smoke build must verify its Gradle distribution too."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_SHA256 = "31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"


class LegacyWrapperChecksumTests(unittest.TestCase):
    def block(self):
        text = (ROOT / "scripts/android-smoke.sh").read_text()
        marker = "# Verify the immutable legacy Gradle distribution before it executes."
        self.assertIn(marker, text, "Legacy wrapper download needs its pinned SHA256")
        return text.split(marker, 1)[1].split('(cd "$legacy_dir"', 1)[0]

    def legacy_properties(self):
        return subprocess.run(["git", "show", "fef8aef77da5a8b5d2363ef2167deab3a3d4d4d2:gradle/wrapper/gradle-wrapper.properties"],
                              cwd=ROOT, check=True, text=True, capture_output=True).stdout

    def run_check(self, properties):
        block = self.block()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "gradle/wrapper/gradle-wrapper.properties"
            path.parent.mkdir(parents=True)
            path.write_text(properties)
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", block],
                                    env=dict(os.environ, legacy_dir=str(root)), capture_output=True, text=True)
            return result, path.read_text()

    def test_verified_legacy_gradle_url_receives_source_backed_checksum(self):
        current = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("distributionSha256Sum=" + EXPECTED_SHA256, current)
        result, properties = self.run_check(self.legacy_properties())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, properties.count("distributionSha256Sum=" + EXPECTED_SHA256))

    def test_unknown_legacy_url_or_conflicting_checksum_is_rejected(self):
        original = self.legacy_properties()
        for content in (original.replace("gradle-8.10.2-bin.zip", "gradle-8.11-bin.zip"),
                        original + "\ndistributionSha256Sum=" + "0" * 64 + "\n",
                        original + "\ndistributionUrl=https\\://evil.example/gradle.zip\n"):
            with self.subTest(content=content):
                result, properties = self.run_check(content)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(content, properties, "Do not rewrite unknown trust metadata")


if __name__ == "__main__":
    unittest.main()
