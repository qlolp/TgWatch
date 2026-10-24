"""Inspect a real signed app and launch its native menu through macOS LaunchServices, with no opt-in changes."""
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MacBundleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix='tgwatch-bundle-')
        cls.app = Path(cls.directory.name) / 'TgWatch.app'
        builder = ROOT / 'scripts/build-mac-app.sh'
        result = subprocess.run(['bash', str(builder), '--output', str(cls.app)], cwd=ROOT,
                                capture_output=True, text=True, timeout=180)
        if result.returncode:
            cls.directory.cleanup()
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_real_universal_app_signature_and_resources(self):
        info = plistlib.loads((self.app / 'Contents/Info.plist').read_bytes())
        self.assertEqual('ru.tgwatch.macos', info['CFBundleIdentifier'])
        self.assertEqual('13.0', info['LSMinimumSystemVersion'])
        self.assertTrue(info['LSUIElement'])
        engine = self.app / 'Contents/Resources/regional-services.py'
        self.assertEqual((ROOT / 'scripts/regional-services.py').read_bytes(), engine.read_bytes())
        for language in ['ru', 'en']:
            self.assertTrue((self.app / ('Contents/Resources/' + language + '.lproj/Localizable.strings')).exists())
        executable = self.app / 'Contents/MacOS/TgWatch'
        arches = subprocess.check_output(['/usr/bin/lipo', '-archs', str(executable)], text=True).split()
        self.assertEqual({'arm64', 'x86_64'}, set(arches))
        subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(self.app)], check=True, capture_output=True)
        signature = subprocess.run(['/usr/bin/codesign', '-dv', '--verbose=4', str(self.app)], check=True,
                                   capture_output=True, text=True).stderr
        self.assertIn('Signature=adhoc', signature)
        # The archive is created before an output folder/provider can attach Finder metadata.
        archive = self.app.parent / 'TgWatch-mac-local-build.zip'
        extracted = self.app.parent / 'extracted'
        subprocess.run(['/usr/bin/ditto', '-x', '-k', str(archive), str(extracted)], check=True)
        subprocess.run(['/usr/bin/codesign', '--verify', '--strict', str(extracted / 'TgWatch.app')],
                       check=True, capture_output=True)

    def test_native_menu_has_five_profiles_without_enabling_any_monitor_or_permission(self):
        report = self.app.parent / 'native-smoke.json'
        opened = subprocess.run(['/usr/bin/open', '-n', '-W', str(self.app), '--args', '--smoke-test',
                                 '--smoke-report', str(report)], capture_output=True, text=True, timeout=20)
        self.assertEqual(0, opened.returncode, opened.stderr)
        self.assertTrue(report.exists(), 'The real native application must construct and report its menu')
        result = json.loads(report.read_text())
        self.assertEqual(5, result['profiles'])
        self.assertTrue(result['native_menu'])
        self.assertFalse(result['monitoring'])
        self.assertFalse(result['notification_request'])
        self.assertFalse(result['login_registration'])


if __name__ == '__main__':
    unittest.main()
