"""HA package syntax and payload contract fixtures; does not run a Home Assistant server."""
import ast
import json
from pathlib import Path
import re
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / 'docs/examples/home-assistant/tgwatch-events.yaml'


class SecretLoader(yaml.SafeLoader):
    pass


SecretLoader.add_constructor('!secret', lambda loader, node: ('secret', loader.construct_scalar(node)))


class HomeAssistantExampleTest(unittest.TestCase):
    def setUp(self):
        self.source = PACKAGE.read_text()
        self.package = yaml.load(self.source, Loader=SecretLoader)
        self.automation = self.package['automation'][0]
        self.condition = self.automation['conditions'][0]['value_template']

    def allowed(self, field):
        match = re.search(r'trigger\.json\.' + field + r' in (\[[^\]]+\])', self.condition)
        self.assertIsNotNone(match, field)
        return ast.literal_eval(match.group(1))

    def accepts_fixture(self, payload):
        # Independently apply the package's allowlists, without pretending to emulate HA/Jinja.
        return (payload.get('schema_version') == 1 and payload.get('service_id') == 'telegram'
                and payload.get('event') in self.allowed('event')
                and payload.get('status') in self.allowed('status')
                and type(payload.get('checked_at_ms')) in (int, float) and payload['checked_at_ms'] > 0)

    def test_webhook_is_post_only_local_and_uses_a_secret(self):
        trigger = self.automation['triggers'][0]
        self.assertEqual('webhook', trigger['trigger'])
        self.assertEqual(('secret', 'tgwatch_webhook_id'), trigger['webhook_id'])
        self.assertEqual(['POST'], trigger['allowed_methods'])
        self.assertTrue(trigger['local_only'])
        self.assertIn('trigger.json is mapping', self.condition)
        self.assertIn('trigger.json.schema_version == 1', self.condition)
        self.assertIn("trigger.json.service_id == 'telegram'", self.condition)
        self.assertIn('trigger.json.checked_at_ms is not boolean', self.condition)

    def test_real_payload_updates_only_last_event_and_measurement_time(self):
        payload = json.loads('{"schema_version":1,"service_id":"telegram","event":"unreachable",'
                             '"status":"DOWN","previous_status":"OK","checked_at_ms":1792038600000,'
                             '"latency_ms":null,"network":"WIFI"}')
        self.assertTrue(self.accepts_fixture(payload))
        actions = self.automation['actions']
        self.assertEqual(2, len(actions))
        self.assertEqual('input_text.set_value', actions[0]['action'])
        self.assertEqual('{{ trigger.json.status }}', actions[0]['data']['value'])
        self.assertEqual('input_datetime.set_datetime', actions[1]['action'])
        self.assertEqual('{{ trigger.json.checked_at_ms / 1000 }}', actions[1]['data']['timestamp'])
        self.assertEqual(1792038600, payload['checked_at_ms'] / 1000)

    def test_test_unknown_wrong_target_and_missing_time_do_not_replace_helpers(self):
        good = dict(schema_version=1, service_id='telegram', event='recovery', status='OK', checked_at_ms=1234)
        self.assertTrue(self.accepts_fixture(good))
        for change in [dict(event='test'), dict(status='UNKNOWN'), dict(service_id='matrix'),
                       dict(schema_version=2), dict(checked_at_ms=None), dict(checked_at_ms=True),
                       dict(checked_at_ms=0)]:
            with self.subTest(change=change):
                self.assertFalse(self.accepts_fixture(dict(good, **change)))

    def test_event_only_helpers_never_claim_persistent_online(self):
        self.assertEqual({'unreachable', 'partial', 'network_lost', 'recovery', 'network_restored'},
                         set(self.allowed('event')))
        self.assertEqual({'OK', 'DOWN', 'PARTIAL', 'NO_NETWORK'}, set(self.allowed('status')))
        self.assertNotIn('binary_sensor', self.package)
        self.assertNotIn('sensor', self.package)
        self.assertIn('no heartbeat', self.source)
        self.assertIn('stale', self.source)
        self.assertEqual(32, self.package['input_text']['tgwatch_last_event']['max'])
