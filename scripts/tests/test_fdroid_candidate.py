"""Exercise F-Droid's --test output contract and retained comparison evidence offline."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml

SCRIPT = Path(__file__).resolve().parents[1] / 'verify-fdroid-candidate.py'


class FdroidCandidateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='tgwatch-fdroid-contract-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        (self.source / 'metadata').mkdir(parents=True)
        (self.source / 'version.properties').write_text('version=1.25.1\n')
        self.metadata = {'Builds': [{'versionName': '1.25.1', 'versionCode': 1025001,
                                    'disable': 'Pending review', 'commit': 'v1.25.1'}]}
        (self.source / 'metadata/ru.tgwatch.yml').write_text(yaml.safe_dump(self.metadata))
        self.gradle_home = self.root / 'gradle-home'
        binary = self.gradle_home / 'wrapper/dists/gradle-9.4.1-bin/hash/gradle-9.4.1/bin/gradle'
        binary.parent.mkdir(parents=True)
        binary.touch()
        self.output = self.root / 'evidence'
        self.unsigned = b'independent-unsigned-canary'
        self.recipe_bytes = self.unsigned
        self.published = b'published-production-signature-canary'
        self.commands = []
        self.comparisons = []
        spec = importlib.util.spec_from_file_location('fdroid_candidate', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.module.ROOT = self.source

    def run_command(self, arguments, *, cwd, timeout=1800):
        self.commands.append(arguments)
        cwd = Path(cwd)
        if arguments[:2] == ['git', 'clone']:
            import shutil
            shutil.copytree(self.source, arguments[-1])
        elif arguments[:2] == ['git', 'checkout']:
            pass
        elif arguments[:2] == ['bash', './gradlew']:
            apk = cwd / 'app/build/outputs/apk/release/app-release-unsigned.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(self.unsigned)
        elif arguments[:2] in (['fdroid', 'readmeta'], ['fdroid', 'lint']):
            pass
        elif arguments[:2] == ['fdroid', 'build']:
            self.assertEqual(['--test', '--stop', 'ru.tgwatch:1025001'], arguments[2:])
            # fdroidserver2.4.5 build.py: options.test selects tmp, never unsigned.
            apk = cwd / 'tmp/ru.tgwatch_1025001.apk'
            apk.parent.mkdir(parents=True)
            apk.write_bytes(self.recipe_bytes)
        elif arguments[0] == os.sys.executable:
            pass  # Supported VCS preparation; no network is needed for the output-contract test.
        elif arguments[:3] == ['gh', 'release', 'download']:
            (self.output / 'TgWatch.apk').write_bytes(self.published)
            (self.output / 'TgWatch.apk.sha256').write_text(
                hashlib.sha256(self.published).hexdigest() + '  TgWatch.apk\n')
        elif arguments[:3] == ['apksigcopier', 'compare', '--unsigned']:
            self.assertTrue(Path(arguments[-2]).is_file())
            self.assertTrue(Path(arguments[-1]).is_file())
            self.comparisons.append(Path(arguments[-1]).name)
        else:
            self.fail(f'Unexpected external command: {arguments}')

    def check_output(self, arguments, **kwargs):
        if arguments[:2] == ['git', 'rev-parse']:
            return '1' * 40 + '\n'
        if arguments[:2] == ['apksigner', 'verify']:
            return 'Signer #1 certificate SHA-256 digest: ' + self.module.CERTIFICATE + '\n'
        self.fail(f'Unexpected output command: {arguments}')

    def verify(self):
        categories = b'Connectivity:\n  name: Connectivity\n'
        with patch.object(self.module, 'run', side_effect=self.run_command), \
                patch.object(self.module.subprocess, 'check_output', side_effect=self.check_output), \
                patch.object(self.module.urllib.request, 'urlopen', side_effect=lambda *a, **k: io.BytesIO(categories)), \
                patch.object(self.module, 'CATEGORIES_SHA', hashlib.sha256(categories).hexdigest()), \
                patch.dict(os.environ, {'GRADLE_USER_HOME': str(self.gradle_home),
                                        'ANDROID_HOME': str(self.root / 'sdk'),
                                        'JAVA_HOME': str(self.root / 'jdk')}):
            self.module.verify('v1.25.1', self.output)

    def test_test_mode_recipe_apk_is_retained_and_both_payloads_are_compared(self):
        self.verify()
        self.assertEqual(self.unsigned, (self.output / 'recipe-unsigned.apk').read_bytes())
        self.assertEqual(['independent-unsigned.apk', 'recipe-unsigned.apk'], self.comparisons)
        report = json.loads((self.output / 'evidence.json').read_text())
        self.assertEqual('passed', report['status'])
        self.assertFalse(report['catalogSubmitted'])
        self.assertFalse(report['checks']['normalRecipeBuild']['scannerBypassed'])
        self.assertTrue(self.metadata['Builds'][0]['disable'])

    def test_different_recipe_bytes_fail_and_keep_both_unsigned_outputs(self):
        self.recipe_bytes = b'different-recipe-canary'
        with self.assertRaisesRegex(ValueError, 'APK bytes differ'):
            self.verify()
        self.assertEqual(self.unsigned, (self.output / 'independent-unsigned.apk').read_bytes())
        self.assertEqual(self.recipe_bytes, (self.output / 'recipe-unsigned.apk').read_bytes())
        self.assertEqual([], self.comparisons)
        report = json.loads((self.output / 'evidence.json').read_text())
        self.assertEqual('failed', report['status'])
        self.assertEqual('passed', report['checks']['normalRecipeBuild']['status'])
