"""App-only IPC cannot change the public CLI contract or leak volatile network identity."""
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


class MacAppIpcTests(unittest.TestCase):
    def load(self):
        spec = importlib.util.spec_from_file_location('regional_services', ROOT / 'scripts/regional-services.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_app_report_has_only_fixed_fields_and_unrelated_random_context(self):
        module = self.load()
        evidence = module.AppEvidence(['gemini'])
        context, state = evidence.update('gemini', 'REGION_RESTRICTED', ('raw-route-secret', 'ip-hash-secret'), 0)
        evidence.update('gemini', 'REGION_RESTRICTED', ('raw-route-secret', 'ip-hash-secret'), 15)
        report = module.app_report('gemini', 'REGION_RESTRICTED', 403, 'DE', 1000, context, state.confirmed)
        self.assertEqual({'schema', 'source', 'service', 'status', 'http_code', 'country', 'checked_at',
                          'region_confirmed', 'context_id'}, set(report))
        self.assertEqual(1, report['schema'])
        self.assertTrue(report['region_confirmed'])
        self.assertRegex(report['context_id'], r'^[0-9a-f]{32}$')
        self.assertNotIn('secret', json.dumps(report))
        self.assertEqual(6, len(module.safe_report('gemini', 'UNKNOWN', 403, None, 1000)))

    def test_change_seen_by_another_profile_prevents_a_to_b_to_a_confirmation(self):
        module = self.load()
        evidence = module.AppEvidence(['gemini', 'youtube'])
        first, _ = evidence.update('gemini', 'REGION_RESTRICTED', ('a', 'egress-a'), 0)
        same, _ = evidence.update('youtube', 'WEB_REACHABLE', ('a', 'egress-a'), 5)
        self.assertEqual(first, same)
        evidence.update('youtube', 'WEB_REACHABLE', ('b', 'egress-b'), 10)
        returned, state = evidence.update('gemini', 'REGION_RESTRICTED', ('a', 'egress-a'), 20)
        self.assertNotEqual(first, returned)
        self.assertFalse(state.confirmed)
        context, state = evidence.update('gemini', 'WEB_REACHABLE', None, 21)
        self.assertIsNone(context)
        unknown = module.app_report('gemini', 'WEB_REACHABLE', 200, 'DE', 1000, context, state.confirmed)
        self.assertEqual(('UNKNOWN', None, False),
                         (unknown['status'], unknown['country'], unknown['region_confirmed']))
        restored, _ = evidence.update('gemini', 'REGION_RESTRICTED', ('a', 'egress-a'), 22)
        self.assertNotEqual(returned, restored)

    def test_app_mode_emits_private_schema_without_changing_json_output(self):
        module = self.load()
        for option in ['--json', '--app-json']:
            output = io.StringIO()
            with patch.object(module, 'measure', return_value=('WEB_REACHABLE', 200, 'DE', ('route', 'egress'))), \
                 patch('sys.stdout', output):
                self.assertEqual(0, module.main(['--service', 'gemini', option]))
            report = json.loads(output.getvalue())
            self.assertEqual(6 if option == '--json' else 9, len(report))

    def test_app_mode_cannot_mix_schemas_or_duplicate_native_notifications(self):
        for options in [['--json', '--app-json'], ['--app-json', '--notify']]:
            run = subprocess.run([sys.executable, str(ROOT / 'scripts/regional-services.py'), *options],
                                 capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            self.assertNotIn('Traceback', run.stderr)

    def test_app_confirmation_delay_is_bounded_and_requires_known_current_context(self):
        module = self.load()
        app = module.AppEvidence(['gemini'])
        scope = ('route', 'egress')
        app.update('gemini', 'WEB_REACHABLE', scope, 0)
        self.assertEqual(15, app.next_delay('gemini', 0, 300))
        app.update('gemini', 'WEB_REACHABLE', scope, 14)
        self.assertEqual(1, app.next_delay('gemini', 14, 300))
        app.update('gemini', 'WEB_REACHABLE', scope, 15)
        self.assertEqual(300, app.next_delay('gemini', 15, 300))
        app.update('gemini', 'CHALLENGE', scope, 16)
        self.assertEqual(300, app.next_delay('gemini', 16, 300))
        app.update('gemini', 'REGION_RESTRICTED', scope, 17)
        self.assertEqual(15, app.next_delay('gemini', 17, 300))
        app.update('gemini', 'REGION_RESTRICTED', scope, 32)
        self.assertEqual(300, app.next_delay('gemini', 32, 300))
        app.update('gemini', 'WEB_REACHABLE', None, 33)
        self.assertEqual(300, app.next_delay('gemini', 33, 300))

    def test_only_private_app_loop_schedules_a_public_response_confirmation(self):
        module = self.load()
        for option, expected in [('--json', 300), ('--app-json', 15)]:
            with patch.object(module, 'measure', return_value=('WEB_REACHABLE', 200, 'DE', ('route', 'egress'))), \
                 patch.object(module.time, 'monotonic', return_value=123), \
                 patch.object(module.time, 'sleep', side_effect=KeyboardInterrupt) as sleep, \
                 patch('sys.stdout', io.StringIO()):
                self.assertEqual(0, module.main(['--service', 'gemini', '--watch', option]))
                self.assertEqual(expected, sleep.call_args.args[0])

    @unittest.skipUnless(os.name == 'posix', 'SIGTERM process ownership is a POSIX contract')
    def test_sigterm_stops_actual_owned_hanging_probe_child(self):
        with tempfile.TemporaryDirectory(prefix='tgwatch-app-ipc-') as directory:
            marker = Path(directory) / 'child.pid'
            fixture = Path(directory) / 'fixture.py'
            fixture.write_text(textwrap.dedent('''\
                import importlib, os, pathlib, sys, time
                def slow_worker(kind, service, connection):
                    pathlib.Path(service).write_text(str(os.getpid()))
                    time.sleep(60)
                if __name__ == '__main__':
                    sys.path.insert(0, sys.argv[1])
                    module = importlib.import_module('regional-services')
                    module.worker = slow_worker
                    module.measure = lambda _: module.bounded_probe('service', sys.argv[2])
                    raise SystemExit(module.main(['--app-json', '--watch', '--service', 'gemini']))
                '''))
            parent = subprocess.Popen([sys.executable, str(fixture), str(ROOT / 'scripts'), str(marker)],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            child = None
            try:
                deadline = time.monotonic() + 10
                while not marker.exists() and parent.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(marker.exists(), 'The fixture must enter a real owned probe before termination')
                child = int(marker.read_text())
                parent.send_signal(signal.SIGTERM)
                stdout, stderr = parent.communicate(timeout=5)
                self.assertEqual(0, parent.returncode, stderr)
                self.assertNotIn('Traceback', stderr)
                self.assertEqual('', stdout)
                with self.assertRaises(ProcessLookupError):
                    os.kill(child, 0)
            finally:
                if parent.poll() is None:
                    parent.kill()
                if child is not None:
                    try:
                        os.kill(child, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                parent.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main()
