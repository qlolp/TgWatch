"""Offline connected-test reporting and smoke failure annotations."""
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/report-android-tests.py'


class AndroidReportingTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('android_reporting', SCRIPT)
        self.reporter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.reporter)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)

    def write(self, text, name='TEST-device.xml'):
        (self.path / name).write_text(text)

    def run_report(self):
        output = io.StringIO()
        self.assertEqual(0, self.reporter.report(self.path, output))
        return output.getvalue()

    def test_counts_actual_cases_across_reports(self):
        self.write('<testsuite><testcase classname="ru.tgwatch.Tests" name="passes"/>'
                   '<testcase classname="ru.tgwatch.Tests" name="skips"><skipped/></testcase>'
                   '<testcase classname="ru.tgwatch.Tests" name="fails"><failure type="java.lang.AssertionError"/></testcase></testsuite>')
        self.write('<testsuites><testsuite><testcase classname="ru.tgwatch.Other" name="errors">'
                   '<error type="java.lang.NoClassDefFoundError"/></testcase></testsuite></testsuites>', 'TEST-other.xml')
        output = self.run_report()
        self.assertIn('tests=4 passed=1 failures=1 errors=1 skipped=1', output)
        self.assertEqual(2, output.count('::error'))
        self.assertIn('ru.tgwatch.Other.errors (java.lang.NoClassDefFoundError)', output)

    def test_failure_body_and_exception_message_are_never_printed(self):
        secret = 'bearer-token-secret-password-value'
        self.write('<testsuite><testcase classname="ru.tgwatch.Test" name="fails">'
                   f'<failure type="java.lang.AssertionError" message="{secret}">{secret}</failure>'
                   '</testcase></testsuite>')
        output = self.run_report()
        self.assertNotIn(secret, output)
        self.assertIn('ru.tgwatch.Test.fails (java.lang.AssertionError)', output)

    def test_safe_case_source_location_without_failure_body(self):
        secret = 'private-token-value'
        self.write('<testsuite><testcase classname="ru.tgwatch.StatusShareIntegrationTest" name="fails">'
                   f'<failure>{secret}&#10;'
                   ' at ru.tgwatch.Other.secret(Secret.java:99)&#10;'
                   ' at ru.tgwatch.StatusShareIntegrationTest.helperRead(StatusShareIntegrationTest.kt:166)'
                   '</failure></testcase></testsuite>')
        output = self.run_report()
        self.assertIn('StatusShareIntegrationTest.kt:166', output)
        self.assertNotIn(secret, output)
        self.assertNotIn('Secret.java', output)
        self.assertEqual(1, output.count('::error'))

    def test_unsafe_identifiers_and_exception_types_are_redacted(self):
        secret = 'token=secret-password'
        self.write('<testsuite><testcase classname="ru.tgwatch.Test&#10;::error::injected" '
                   f'name="{secret}"><error type="java.lang.Error: {secret}"/></testcase></testsuite>')
        output = self.run_report()
        self.assertNotIn(secret, output)
        self.assertNotIn('injected', output)
        self.assertEqual(1, output.count('::error'))
        self.assertIn('unknown-class.unknown-test (unknown-exception)', output)

    def test_workflow_escape_percent_cr_lf(self):
        self.assertEqual('100%25%0D%0A::error::', self.reporter.workflow_escape('100%\r\n::error::'))

    def test_missing_malformed_and_zero_cases_are_unavailable(self):
        for xml in (None, '<testsuite>', '<unexpected/>', '<testsuite tests="0"/>'):
            with self.subTest(xml=xml):
                for path in self.path.glob('*.xml'): path.unlink()
                if xml is not None: self.write(xml)
                output = self.run_report()
                self.assertIn('unavailable', output)
                self.assertIn('::warning', output)
                self.assertNotIn('passed=', output)

    def test_malformed_second_report_prevents_pass_summary(self):
        self.write('<testsuite><testcase classname="Example" name="passes"/></testsuite>')
        self.write('<testsuite>', 'TEST-broken.xml')
        self.assertNotIn('passed=', self.run_report())

    def test_failure_annotation_count_is_bounded(self):
        cases = ''.join(f'<testcase classname="Example" name="fails{i}"><failure type="Error"/></testcase>' for i in range(30))
        self.write('<testsuite>' + cases + '</testsuite>')
        output = self.run_report()
        self.assertIn('tests=30 passed=0 failures=30 errors=0 skipped=0', output)
        self.assertEqual(self.reporter.MAX_ANNOTATIONS, output.count('::error'))
        self.assertIn('annotation limit', output)

    def test_xml_entities_are_unavailable(self):
        self.write('<!DOCTYPE testsuite [<!ENTITY private "secret">]><testsuite><testcase name="&private;"/></testsuite>')
        self.assertIn('unavailable', self.run_report())

    def test_declared_counts_cannot_falsely_report_success(self):
        self.write('<testsuite tests="2"><testcase classname="Example" name="passes"/></testsuite>')
        output = self.run_report()
        self.assertIn('unavailable', output)
        self.assertNotIn('passed=', output)

    def test_report_size_and_file_limits_are_unavailable(self):
        self.write('<testsuite><testcase classname="Example" name="passes"/></testsuite>')
        with mock.patch.object(self.reporter, 'MAX_REPORT_BYTES', 16):
            self.assertIn('size limit', self.run_report())
        self.write('<testsuite/>', 'TEST-extra.xml')
        with mock.patch.object(self.reporter, 'MAX_REPORT_FILES', 1):
            self.assertIn('file limit', self.run_report())

    def test_reporting_errors_never_leak_paths_or_change_exit_status(self):
        with mock.patch.object(self.reporter.Path, 'rglob', side_effect=OSError('secret-password-path')):
            output = self.run_report()
        self.assertIn('unavailable', output)
        self.assertNotIn('secret-password-path', output)

    def test_cli_missing_report_preserves_zero_exit(self):
        result = subprocess.run([sys.executable, str(SCRIPT), str(self.path)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('unavailable', result.stdout)


class SmokeAnnotationTests(unittest.TestCase):
    def test_failed_gradle_preserves_status_exit_trap_and_freshness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gradlew').write_text('#!/usr/bin/env bash\nexit 17\n')
            adb = root / 'adb'
            adb.write_text('#!/usr/bin/env bash\nprintf "EXIT trap preserved\\n"\n')
            adb.chmod(0o755)
            stale = root / 'app/build/outputs/androidTest-results/connected/debug/TEST-stale.xml'
            stale.parent.mkdir(parents=True)
            stale.write_text('<testsuite/>')
            result = subprocess.run(['bash', str(ROOT / 'scripts/android-smoke.sh')], cwd=root,
                                    env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']),
                                    capture_output=True, text=True)
            self.assertEqual(17, result.returncode)
            self.assertRegex(result.stderr, r'::error file=scripts/android-smoke.sh,line=\d+.*exit status 17')
            self.assertNotIn('bash ./gradlew', result.stderr)
            self.assertIn('EXIT trap preserved', (root / 'smoke-logcat.txt').read_text())
            self.assertFalse(stale.exists())


    def test_permission_cycle_reinstalls_inputs_after_runner_uninstalls_them(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gradlew').write_text('#!/usr/bin/env bash\nexit 0\n')
            helper = root / 'python3'
            helper.write_text('#!/usr/bin/env bash\nexit 0\n')
            helper.chmod(0o755)
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env bash
if [[ "$1 $2 $3 $4" == "shell getprop ro.build.version.sdk " ]]; then echo 35; exit 0; fi
if [[ "$1" == install ]]; then
  case "$*" in
    *app-debug-androidTest.apk) touch test-installed ;;
    *app-debug.apk) touch app-installed ;;
  esac
fi
if [[ "$1 $2 $3" == "shell pm revoke" ]]; then
  [[ -e app-installed && -e test-installed ]] || exit 23
  exit 29
fi
exit 0
''')
            adb.chmod(0o755)
            result = subprocess.run(['bash', str(ROOT / 'scripts/android-smoke.sh')], cwd=root,
                                    env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH']),
                                    capture_output=True, text=True)
            self.assertEqual(29, result.returncode, result.stderr)


class WorkflowReportingTests(unittest.TestCase):
    def test_always_summary_precedes_upload_and_includes_raw_xml(self):
        import yaml
        steps = yaml.safe_load((ROOT / '.github/workflows/build-apk.yml').read_text())['jobs']['android']['steps']
        report = next(i for i, step in enumerate(steps) if 'report-android-tests.py' in step.get('run', ''))
        upload = next(i for i, step in enumerate(steps) if step.get('name') == 'Android reports')
        self.assertLess(report, upload)
        self.assertEqual('always()', steps[report]['if'])
        self.assertEqual('always()', steps[upload]['if'])
        self.assertIn('app/build/outputs/androidTest-results/connected/', steps[upload]['with']['path'])


if __name__ == '__main__': unittest.main()
