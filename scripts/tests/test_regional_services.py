"""Public regional checks never infer feature availability or export response contents."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


class RegionalServicesTests(unittest.TestCase):
    def load(self):
        path = ROOT / 'scripts/regional-services.py'
        self.assertTrue(path.exists(), 'Missing independent Mac public checks')
        spec = importlib.util.spec_from_file_location('regional_services', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_http_codes_and_explicit_region_evidence(self):
        module = self.load()
        cases = [(403, 'Access denied', 'UNKNOWN'), (451, 'Legal restriction', 'UNKNOWN'),
                 (401, '', 'AUTH_REQUIRED'), (429, '', 'RATE_LIMITED'),
                 (200, '<h1>Not available in your country</h1>', 'REGION_RESTRICTED'),
                 (200, '<script>Not available in your country</script>', 'WEB_REACHABLE'),
                 (403, '<h1>Verify you are human</h1>', 'CHALLENGE')]
        for code, body, expected in cases:
            self.assertEqual(expected, module.classify(code, 'text/html', body))

    def test_duplicate_json_and_incomplete_responses_are_not_region_evidence(self):
        module = self.load()
        raw = '{"error":{"code":"unsupported_country","code":"other"}}'
        self.assertEqual('UNKNOWN', module.classify(403, 'application/json', raw))
        raw = '{"error":{"code":"unsupported_country"}}'
        self.assertEqual('REGION_RESTRICTED', module.classify(403, 'application/json', raw))
        self.assertEqual('UNKNOWN', module.classify(403, 'application/json', raw, complete=False))

    def test_redirects_are_fixed_https_hosts_without_credentials(self):
        module = self.load()
        self.assertTrue(module.allowed_redirect('notebook', 'https://notebooklm.google.com/'))
        for url in ('http://notebook.google.com/', 'https://notebook.google.com.attacker.example/',
                    'https://secret@notebook.google.com/', 'https://notebook.google.com:444/'):
            self.assertFalse(module.allowed_redirect('notebook', url))

    def test_confirmation_and_network_change(self):
        module = self.load()
        state = module.Consensus()
        state.update('REGION_RESTRICTED', 'wifi', 0)
        self.assertFalse(state.should_notify(0))
        state.update('REGION_RESTRICTED', 'wifi', 14.999)
        self.assertFalse(state.should_notify(14.999))
        state.update('REGION_RESTRICTED', 'vpn', 15)
        self.assertFalse(state.should_notify(15))
        state.update('REGION_RESTRICTED', 'vpn', 30)
        self.assertTrue(state.should_notify(30))
        state.delivered(30)
        state.update('NETWORK_ERROR', 'vpn', 45)
        state.update('REGION_RESTRICTED', 'vpn', 600)
        state.update('REGION_RESTRICTED', 'vpn', 615)
        self.assertFalse(state.should_notify(615), 'A network error cannot invent recovery')

    def test_public_output_has_no_raw_body_ip_scope_or_redirect_url(self):
        module = self.load()
        result = module.safe_report('gemini', 'UNKNOWN', 403, 'DE', 1000)
        self.assertEqual({'source', 'service', 'status', 'http_code', 'country', 'checked_at'}, set(result))
        self.assertEqual('mac_public' if sys.platform == 'darwin' else 'computer_public', result['source'])

    def test_oversized_stream_is_bounded_and_classified_as_incomplete(self):
        module = self.load()
        class Stream:
            count = 0
            def read(self, size):
                self.count += size
                return b'x' * size
        stream = Stream()
        body, complete = module.read_bounded(stream, 32768)
        self.assertEqual(32768, len(body))
        self.assertFalse(complete)
        self.assertEqual(32769, stream.count)

    def test_cli_rejects_unknown_profile_and_unsafe_intervals_before_network(self):
        module = self.load()
        path = ROOT / 'scripts/regional-services.py'
        for args in (['--service', 'attacker'], ['--watch', '--interval', '1']):
            run = subprocess.run([sys.executable, str(path), *args], capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, run.returncode)
            self.assertNotIn('Traceback', run.stderr)

    def test_notification_arguments_are_fixed_and_do_not_interpolate_page_text(self):
        module = self.load()
        with patch.object(module.sys, 'platform', 'darwin'), patch.object(module.subprocess, 'run') as run:
            self.assertTrue(module.notify('gemini'))
            args = run.call_args.args[0]
            self.assertEqual('/usr/bin/osascript', args[0])
            self.assertNotIn('http', ' '.join(args))


if __name__ == '__main__':
    unittest.main()
