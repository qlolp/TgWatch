"""Execute the example's actual Jinja templates/actions with deterministic HA state seams.

This validates the package contract, not a running Home Assistant installation.
"""
import copy
from datetime import datetime, timezone
import json
from pathlib import Path
from types import SimpleNamespace
import unittest

from jinja2 import StrictUndefined, TemplateError
from jinja2.sandbox import SandboxedEnvironment
import yaml

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / 'docs/examples/home-assistant/tgwatch-heartbeat.yaml'
IDS = ('telegram', 'matrix', 'whatsapp', 'custom')


class SecretLoader(yaml.SafeLoader):
    pass


SecretLoader.add_constructor('!secret', lambda loader, node: ('secret', loader.construct_scalar(node)))


class Receiver:
    def __init__(self, package):
        self.package = package
        self.automation = package['automation'][0]
        self.now_ms = 1792396800000
        self.helpers = {}
        self.deadlines = {}
        self.environment = SandboxedEnvironment(undefined=StrictUndefined, autoescape=False)
        self.environment.filters['from_json'] = self.from_json
        self.environment.filters['to_json'] = lambda value: json.dumps(value, separators=(',', ':'), allow_nan=False)

    @staticmethod
    def from_json(value, default=None):
        try:
            return json.loads(value)
        except (ValueError, TypeError):
            return default

    def states(self, entity):
        if entity.startswith('timer.'):
            return 'active' if self.now_ms < self.deadlines.get(entity, 0) else 'idle'
        return self.helpers.get(entity, 'unknown')

    def render(self, source, payload=None):
        return self.environment.from_string(str(source)).render(
            trigger=SimpleNamespace(json=payload), states=self.states,
            now=lambda: datetime.fromtimestamp(self.now_ms / 1000, timezone.utc),
            as_timestamp=lambda value: value.timestamp())

    def accepts(self, payload):
        try:
            return all(self.render(condition['value_template'], payload).strip() == 'True'
                       for condition in self.automation['conditions'])
        except (TemplateError, ValueError, TypeError):
            return False

    def receive(self, payload):
        return self.accepts(payload) and self.run_actions(payload)

    def run_actions(self, payload):
        for action in self.automation['actions']:
            if action.get('condition') == 'template':
                if self.render(action['value_template'], payload).strip() != 'True':
                    return False
                continue
            entity = self.render(action['target']['entity_id'], payload)
            if action['action'] == 'input_text.set_value':
                assert entity.removeprefix('input_text.') in self.package['input_text']
                value = self.render(action['data']['value'], payload)
                assert len(value) <= self.package['input_text'][entity.removeprefix('input_text.')]['max']
                self.helpers[entity] = value
            elif action['action'] == 'timer.start':
                config = self.package['timer'][entity.removeprefix('timer.')]
                duration = action.get('data', {}).get('duration', config['duration'])
                hours, minutes, seconds = (int(value) for value in duration.split(':'))
                self.deadlines[entity] = self.now_ms + (hours * 3600 + minutes * 60 + seconds) * 1000
            else:
                raise AssertionError('Unexpected example action')
        return True

    def available(self, service):
        sensor = next(sensor for sensor in self.package['template'][0]['sensor']
                      if sensor['unique_id'] == 'tgwatch_' + service)
        return self.render(sensor['availability']).strip() == 'True'

    def record(self, service):
        return json.loads(self.helpers['input_text.tgwatch_' + service])


def packet(**changes):
    return dict(dict(schema_version=2, service_id='telegram', event='heartbeat', status='OK',
                     previous_status=None, checked_at_ms=1000, latency_ms=20, network='WIFI',
                     sent_at_ms=1100, monitoring=True, is_stale=False), **changes)


class HomeAssistantHeartbeatTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(PACKAGE.exists(), 'Missing receipt-time heartbeat package')
        self.package = yaml.load(PACKAGE.read_text(), Loader=SecretLoader)
        self.receiver = Receiver(self.package)

    def test_authenticated_post_only_ingress_and_four_fixed_restored_timers(self):
        trigger = self.package['automation'][0]['triggers'][0]
        self.assertEqual('webhook', trigger['trigger'])
        self.assertEqual(('secret', 'tgwatch_webhook_id'), trigger['webhook_id'])
        self.assertEqual(['POST'], trigger['allowed_methods'])
        self.assertTrue(trigger['local_only'])
        self.assertEqual({'tgwatch_' + service for service in IDS}, set(self.package['input_text']))
        self.assertEqual({'tgwatch_expiry_' + service for service in IDS}, set(self.package['timer']))
        for timer in self.package['timer'].values():
            self.assertTrue(timer['restore'])
            self.assertEqual('00:12:00', timer['duration'])
        self.assertEqual('queued', self.package['automation'][0]['mode'])
        for service in IDS:
            self.assertFalse(self.receiver.available(service))

    def test_original_timestamp_and_local_receipt_expire_at_exact_twelve_minutes(self):
        original_now = self.receiver.now_ms
        self.assertTrue(self.receiver.receive(packet()))
        self.assertEqual(dict(checked_at_ms=1000, received_at_ms=original_now, status='OK'),
                         self.receiver.record('telegram'))
        self.assertTrue(self.receiver.available('telegram'))
        self.receiver.now_ms += 719999
        self.assertTrue(self.receiver.available('telegram'))
        self.receiver.now_ms += 1
        self.assertFalse(self.receiver.available('telegram'))

    def test_duplicates_and_delayed_older_packets_cannot_refresh_ttl(self):
        self.assertTrue(self.receiver.receive(packet(checked_at_ms=3000, sent_at_ms=3100)))
        original = copy.deepcopy(self.receiver.helpers), copy.deepcopy(self.receiver.deadlines)
        self.receiver.now_ms += 600000
        for checked in [3000, 2999, 1000]:
            self.assertFalse(self.receiver.receive(packet(checked_at_ms=checked, sent_at_ms=9999)))
        self.assertEqual(original, (self.receiver.helpers, self.receiver.deadlines))
        self.receiver.now_ms += 120000
        self.assertFalse(self.receiver.available('telegram'))

    def test_newer_measurement_extends_only_its_own_timer(self):
        self.assertTrue(self.receiver.receive(packet()))
        self.assertTrue(self.receiver.receive(packet(service_id='matrix', status='DOWN')))
        self.receiver.now_ms += 700000
        self.assertTrue(self.receiver.receive(packet(checked_at_ms=2000, sent_at_ms=2100)))
        self.receiver.now_ms += 20000
        self.assertTrue(self.receiver.available('telegram'))
        self.assertFalse(self.receiver.available('matrix'))
        self.assertEqual(2000, self.receiver.record('telegram')['checked_at_ms'])

    def test_phone_clock_offset_cannot_keep_sensor_live_and_rollback_requires_reset(self):
        self.assertTrue(self.receiver.receive(packet(checked_at_ms=9999999999999, sent_at_ms=10000000000000)))
        self.receiver.now_ms += 720000
        self.assertFalse(self.receiver.available('telegram'))
        self.assertFalse(self.receiver.receive(packet(checked_at_ms=2000, sent_at_ms=2100)))
        self.assertFalse(self.receiver.available('telegram'))
        self.receiver.helpers['input_text.tgwatch_telegram'] = '{}'
        self.assertTrue(self.receiver.receive(packet(checked_at_ms=2000, sent_at_ms=2100)))

    def test_test_unknown_malformed_stale_and_noninteger_packets_are_rejected(self):
        for change in [dict(schema_version=1), dict(schema_version=True), dict(event='test'),
                       dict(event='arbitrary'), dict(event='network_lost'), dict(previous_status='DOWN'),
                       dict(status='UNKNOWN'), dict(service_id='secret'),
                       dict(checked_at_ms=None), dict(checked_at_ms=True), dict(checked_at_ms=1.5),
                       dict(checked_at_ms=0), dict(checked_at_ms=2**63), dict(sent_at_ms=2**63),
                       dict(latency_ms=True), dict(latency_ms=-1), dict(sent_at_ms=0), dict(sent_at_ms=False),
                       dict(sent_at_ms=999), dict(monitoring=False), dict(monitoring=1),
                       dict(is_stale=True), dict(is_stale=0), dict(network='private-network')]:
            with self.subTest(change=change):
                self.assertFalse(self.receiver.receive(packet(**change)))
        for invalid in [None, [], 'text', {}, dict(packet(), private_url='https://private.invalid')]:
            self.assertFalse(self.receiver.receive(invalid))
        self.assertEqual({}, self.receiver.helpers)
        self.assertEqual({}, self.receiver.deadlines)

    def test_restart_and_local_clock_rollback_do_not_revive_expired_helpers(self):
        self.assertTrue(self.receiver.receive(packet()))
        restored = Receiver(self.package)
        restored.helpers = copy.deepcopy(self.receiver.helpers)
        restored.deadlines = copy.deepcopy(self.receiver.deadlines)
        restored.now_ms = self.receiver.now_ms + 720000
        self.assertFalse(restored.available('telegram'))
        restored.now_ms = self.receiver.now_ms - 1
        self.assertFalse(restored.available('telegram'))

    def test_queued_packets_recheck_order_at_execution_before_mutating_helpers(self):
        older = packet(checked_at_ms=1000, sent_at_ms=1100)
        newer = packet(checked_at_ms=2000, sent_at_ms=2100)
        self.assertTrue(self.receiver.accepts(older))
        self.assertTrue(self.receiver.accepts(newer))
        self.assertTrue(self.receiver.run_actions(newer))
        original = copy.deepcopy(self.receiver.helpers), copy.deepcopy(self.receiver.deadlines)
        self.receiver.now_ms += 600000
        self.assertFalse(self.receiver.run_actions(older))
        self.assertEqual(original, (self.receiver.helpers, self.receiver.deadlines))

    def test_real_transition_events_keep_their_status_and_source_measurement(self):
        for index, (event, status, previous) in enumerate([
                ('unreachable', 'DOWN', 'OK'), ('partial', 'PARTIAL', 'OK'),
                ('network_lost', 'NO_NETWORK', 'OK'), ('recovery', 'OK', 'DOWN'),
                ('network_restored', 'OK', 'NO_NETWORK'), ('network_restored', 'DOWN', 'NO_NETWORK')]):
            self.assertTrue(self.receiver.receive(packet(event=event, status=status,
                previous_status=previous, checked_at_ms=1000 + index, sent_at_ms=1100 + index)))
            self.assertEqual(status, self.receiver.record('telegram')['status'])
            self.assertTrue(self.receiver.available('telegram'))

    def test_all_fixed_services_work_without_creating_unknown_entities(self):
        for service in IDS:
            self.assertTrue(self.receiver.receive(packet(service_id=service)))
            self.assertTrue(self.receiver.available(service))
        self.assertEqual(4, len(self.receiver.helpers))
        self.assertFalse(self.receiver.receive(packet(service_id='other')))
        self.assertEqual(4, len(self.receiver.helpers))


if __name__ == '__main__':
    unittest.main()
