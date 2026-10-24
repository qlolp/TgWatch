"""A separate fresh denial cycle must execute the watch test and restore the app permission even on failure."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RegionalWatchPermissionSmokeTests(unittest.TestCase):
    def run_cycle(self, transcript):
        with tempfile.TemporaryDirectory(prefix='tgwatch-denial-') as directory:
            folder = Path(directory)
            commands = folder / 'commands.jsonl'
            fixture = folder / 'instrumentation.txt'
            fixture.write_text(transcript)
            adb = folder / 'adb'
            adb.write_text('''#!/usr/bin/env python3
import json,os,pathlib,sys
with open(os.environ['TGWATCH_COMMANDS'], 'a') as out:out.write(json.dumps(sys.argv[1:])+'\\n')
if sys.argv[1:4]==['shell','am','instrument']:print(pathlib.Path(os.environ['TGWATCH_TRANSCRIPT']).read_text())
''')
            adb.chmod(0o755)
            env = dict(os.environ, PATH=str(folder)+os.pathsep+os.environ['PATH'],
                       TGWATCH_COMMANDS=str(commands), TGWATCH_TRANSCRIPT=str(fixture))
            result = subprocess.run(['bash', str(ROOT / 'scripts/regional-watch-permission-smoke.sh'),
                                     str(folder / 'reports')], env=env, cwd=ROOT, capture_output=True, text=True, timeout=10)
            calls = [json.loads(line) for line in commands.read_text().splitlines()] if commands.exists() else []
            return result, calls

    def test_actual_pass_requires_one_executed_case_and_real_revoke_before_instrumentation(self):
        result, calls = self.run_cycle('INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n')
        self.assertEqual(0, result.returncode, result.stderr)
        revoke = next(i for i,c in enumerate(calls) if c[:3] == ['shell','pm','revoke'])
        instrument = next(i for i,c in enumerate(calls) if c[:3] == ['shell','am','instrument'])
        self.assertLess(revoke, instrument)
        self.assertIn('ru.tgwatch.RegionalWatchIntegrationTest#deniedAppNotificationsDoNotConsumeTheArmedWatch', calls[instrument])
        self.assertEqual(['shell','pm','grant','ru.tgwatch','android.permission.POST_NOTIFICATIONS'], calls[-1])

    def test_skipped_failed_and_missing_cases_are_rejected_without_losing_permission_cleanup(self):
        for transcript in ['INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)\n',
                           'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\n',
                           'OK (0 tests)\n']:
            result, calls = self.run_cycle(transcript)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(['shell','pm','grant','ru.tgwatch','android.permission.POST_NOTIFICATIONS'], calls[-1])


if __name__ == '__main__':
    unittest.main()
