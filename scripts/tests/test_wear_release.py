import importlib.util
from pathlib import Path
import sys
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class WearReleaseTests(unittest.TestCase):
    def test_twelve_exact_assets_and_no_unexpected_phone_files(self):
        from release_apps import release_assets
        expected = release_assets()
        self.assertEqual(12, len(expected))
        self.assertIn('TgWatch-wear.apk', expected)
        self.assertIn('TgWatch.apk', expected)
        self.assertNotIn('debug.apk', expected)

    def test_wear_evidence_cannot_reuse_phone_root(self):
        from release_apps import verify_application_evidence
        inventory = {'applicationId': 'ru.tgwatch.wear', 'applicationVersion': '1.19.0'}
        phone = {'metadata': {'component': {'name': 'TgWatch', 'version': '1.19.0',
                                          'bom-ref': 'ru.tgwatch:releaseRuntimeClasspath'}}}
        with self.assertRaises(ValueError):
            verify_application_evidence(inventory, phone, application_id='ru.tgwatch.wear')
        wear = {'metadata': {'component': {'name': 'TgWatch Wear', 'version': '1.19.0',
                                         'bom-ref': 'ru.tgwatch.wear:releaseRuntimeClasspath'}}}
        verify_application_evidence(inventory, wear, application_id='ru.tgwatch.wear')
        with self.assertRaises(ValueError):
            verify_application_evidence(inventory, wear, {'applicationId': 'ru.tgwatch'}, 'ru.tgwatch.wear')

    def test_wear_package_and_api_floor_are_verified(self):
        from apk_verification import verify_apk_identity
        import subprocess
        good = "package: name='ru.tgwatch.wear' versionCode='1019000' versionName='1.19.0'\nsdkVersion:'30'\n"
        with patch('apk_verification.subprocess.run', return_value=subprocess.CompletedProcess([], 0, good, '')):
            verify_apk_identity(Path('watch.apk'), '1.19.0', 1019000, 'aapt2', application_id='ru.tgwatch.wear')
        modern = good.replace('sdkVersion:', 'minSdkVersion:')
        with patch('apk_verification.subprocess.run', return_value=subprocess.CompletedProcess([], 0, modern, '')):
            verify_apk_identity(Path('watch.apk'), '1.19.0', 1019000, 'aapt2', application_id='ru.tgwatch.wear')
        duplicate = modern + "sdkVersion:'30'\n"
        with patch('apk_verification.subprocess.run', return_value=subprocess.CompletedProcess([], 0, duplicate, '')):
            with self.assertRaises(ValueError):
                verify_apk_identity(Path('watch.apk'), '1.19.0', 1019000, 'aapt2', application_id='ru.tgwatch.wear')
        for bad in (good.replace("sdkVersion:'30'", "sdkVersion:'29'"), good.replace('ru.tgwatch.wear', 'ru.tgwatch')):
            with patch('apk_verification.subprocess.run', return_value=subprocess.CompletedProcess([], 0, bad, '')):
                with self.assertRaises(ValueError):
                    verify_apk_identity(Path('watch.apk'), '1.19.0', 1019000, 'aapt2', application_id='ru.tgwatch.wear')

    def test_smoke_invokes_real_surface_tests_and_requires_executed_results(self):
        from wear_smoke_report import EXPECTED, verify
        transcript = '\n'.join(f'INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 0'
                               for name in sorted(EXPECTED)) + f'\nOK ({len(EXPECTED)} tests)\n'
        self.assertEqual(len(EXPECTED), verify(transcript, 30)['executed'])
        for bad in ('OK (0 tests)', transcript.replace('CODE: 0', 'CODE: -4', 1),
                    transcript.replace(sorted(EXPECTED)[0], 'unrelatedTest')):
            with self.assertRaises(ValueError):
                verify(bad, 30)

    def test_smoke_uses_standard_runner_tools_without_ripgrep(self):
        from wear_smoke_report import EXPECTED
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            for name in ['mkdir', 'tr', 'grep', 'timeout', 'cat']:
                (bin_dir / name).symlink_to(shutil.which(name))
            (bin_dir / 'python3').symlink_to(sys.executable)
            transcript = '\n'.join(f'INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 0'
                                   for name in sorted(EXPECTED)) + f'\nOK ({len(EXPECTED)} tests)\n'
            fake_adb = bin_dir / 'adb'
            fake_adb.write_text(f'#!{sys.executable}\nimport sys\nargs=" ".join(sys.argv[1:])\n'
                'if "getprop" in args: print("30")\n'
                'elif "list features" in args: print("feature:android.hardware.type.watch")\n'
                f'elif "am instrument" in args: print({transcript!r})\n')
            fake_adb.chmod(0o755)
            repo = Path(__file__).resolve().parents[2]
            result = subprocess.run(['/bin/bash', str(repo / 'scripts/wear-smoke.sh')], cwd=repo,
                env=dict(os.environ, PATH=str(bin_dir), WEAR_SMOKE_SKIP_BUILD='true',
                         WEAR_SMOKE_REPORT_DIR=str(root / 'reports')),
                text=True, capture_output=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__':
    unittest.main()
