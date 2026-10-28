"""Local-only Android evidence capture and redacted aggregation tests."""
import importlib.util
import json
from pathlib import Path
import stat
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/android-device-evidence.py'


def load_tool():
    spec = importlib.util.spec_from_file_location('android_device_evidence', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def observation(target, at, until, status='OK', latency=25, revision=0, epoch=1):
    return dict(target_id=target, at_ms=at, until_ms=until, status=status,
                latency_ms=latency, clock_epoch=epoch, endpoint_revision=revision)


def snapshot(captured_at, rows):
    return dict(schema_version=1, captured_at_ms=captured_at,
                target_ids=sorted({row['target_id'] for row in rows}),
                observations=rows)


def evidence(phase, captured_at, rows, battery_level, sampled_at=None, powered='false'):
    return {
        'schema_version': 1,
        'phase': phase,
        'captured_at_ms': captured_at,
        'capture_started_at_ms': captured_at,
        'battery_sampled_at_ms': sampled_at if sampled_at is not None else captured_at,
        'device': {'manufacturer': 'Test', 'model': 'Fixture', 'android_release': '16', 'android_sdk': '36'},
        'battery': {'level_percent': battery_level, 'ac_powered': 'false',
                    'usb_powered': powered, 'wireless_powered': 'false'},
        'targets': snapshot(captured_at, rows),
    }


class AndroidDeviceEvidenceTests(unittest.TestCase):
    def test_summary_reports_probe_and_check_percentiles_and_detects_stale_gaps(self):
        tool = load_tool()
        before = evidence('before', 1000, [observation('telegram', 900, 1100)], 80)
        after = evidence('after', 11000, [
            observation('telegram', 900, 2000),
            observation('telegram', 2000, 3000, latency=10),
            observation('telegram', 4000, 5000, latency=30),
            observation('telegram', 9000, 10000, status='DOWN', latency=50),
        ], 79)
        metrics = tool.target_metrics(before, after, 3000)[0]
        self.assertEqual(3, metrics['observations'])
        self.assertEqual(30, metrics['probe_latency_ms_p50'])
        self.assertEqual(50, metrics['probe_latency_ms_p95'])
        self.assertEqual(2000, metrics['check_interval_ms_p50'])
        self.assertEqual(5000, metrics['check_interval_ms_p95'])
        self.assertEqual(3, metrics['history_gap_count'])
        self.assertEqual(4000, metrics['history_gap_max_ms'])
        self.assertTrue(metrics['history_gap_over_limit'])
        self.assertFalse(metrics['stale'])

    def test_capture_is_read_only_and_keeps_raw_dumps_private(self):
        tool = load_tool()
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'device-run'
            target_csv = Path(temp) / 'telegram.csv'
            target_csv.write_text(
                'from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch,'
                'in_current_statistics,target_id,endpoint_revision\n'
                '1970-01-01T00:00:00.500Z,1970-01-01T00:00:01.000Z,OK,500,25,1,true,telegram,0\n'
            )
            calls = []

            def fake_command(adb, serial, arguments, timeout=30):
                calls.append(arguments)
                self.assertNotIn('reset', arguments)
                self.assertNotIn('settings', arguments)
                if arguments == ['shell', 'dumpsys', 'battery']:
                    return b'level: 77\nUSB powered: false\n'
                return b'private dump payload'

            args = SimpleNamespace(phase='before', out=output, adb='adb', serial='fixture',
                                   targets_csv=[target_csv])
            with patch.object(tool, 'property', return_value='fixture'), \
                    patch.object(tool, 'command', side_effect=fake_command):
                tool.capture(args)

            self.assertTrue(calls)
            self.assertTrue(all(call[:2] == ['shell', 'dumpsys'] for call in calls))
            self.assertEqual(0o700, stat.S_IMODE(output.stat().st_mode))
            for name in ('before-batterystats.txt', 'before-alarm.txt', 'before-deviceidle.txt',
                         'before-connectivity.txt', 'before-jobscheduler.txt',
                         'before-activity-services.txt', 'before.json'):
                path = output / name
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            metadata = json.loads((output / 'before.json').read_text())
            self.assertEqual(77, metadata['battery']['level_percent'])
            self.assertEqual(b'private dump payload', (output / 'before-batterystats.txt').read_bytes())
            self.assertEqual('telegram', metadata['targets']['target_ids'][0])
            self.assertEqual(target_csv.read_bytes(), (output / 'before-history-telegram.csv').read_bytes())

    def test_public_csv_import_validates_target_identity_and_history_cohort(self):
        tool = load_tool()
        header = ('from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch,'
                  'in_current_statistics,target_id,endpoint_revision\n')
        row = ('2026-10-28T08:00:00.000Z,2026-10-28T08:00:01.000Z,OK,1000,25,1,true,telegram,0\n')
        observations, targets = tool.parse_target_csv((header + row).encode())
        self.assertEqual(['telegram'], targets)
        self.assertEqual(1, observations[0]['clock_epoch'])
        with self.assertRaises(ValueError):
            tool.parse_target_csv((header + row.replace(',true,telegram,', ',false,telegram,')).encode())

    def test_daily_battery_rate_requires_full_unpowered_window(self):
        tool = load_tool()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            before_path, after_path = root / 'before.json', root / 'after.json'
            before = evidence('before', 1000, [], 80, sampled_at=1000)
            after = evidence('after', 1000 + 24 * 60 * 60 * 1000, [], 70,
                             sampled_at=1000 + 24 * 60 * 60 * 1000)
            before_path.write_text(json.dumps(before))
            after_path.write_text(json.dumps(after))
            report_path = root / 'aggregate.json'
            tool.summarize(SimpleNamespace(before=before_path, after=after_path,
                                           max_gap_seconds=600, out=report_path))
            report = json.loads(report_path.read_text())
            self.assertEqual(10.0, report['battery_percentage_points_per_24h'])
            self.assertNotIn('private dump payload', report_path.read_text())

            after['battery']['usb_powered'] = 'true'
            after_path.write_text(json.dumps(after))
            powered_report = root / 'powered.json'
            tool.summarize(SimpleNamespace(before=before_path, after=after_path,
                                           max_gap_seconds=600, out=powered_report))
            self.assertIsNone(json.loads(powered_report.read_text())['battery_percentage_points_per_24h'])

            after['battery']['usb_powered'] = 'false'
            after['battery']['wireless_powered'] = None
            after_path.write_text(json.dumps(after))
            unknown_power_report = root / 'unknown-power.json'
            tool.summarize(SimpleNamespace(before=before_path, after=after_path,
                                           max_gap_seconds=600, out=unknown_power_report))
            self.assertIsNone(json.loads(unknown_power_report.read_text())['battery_percentage_points_per_24h'])

    def test_private_directory_rejects_symlink_and_permissive_existing_directory(self):
        tool = load_tool()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            exposed = root / 'exposed'
            exposed.mkdir(mode=0o755)
            exposed.chmod(0o755)
            with self.assertRaises(ValueError):
                tool.secure_directory(exposed)
            target = root / 'target'
            target.mkdir(mode=0o700)
            link = root / 'link'
            link.symlink_to(target, target_is_directory=True)
            with self.assertRaises(ValueError):
                tool.secure_directory(link)

    def test_after_capture_requires_a_before_record_before_touching_adb(self):
        tool = load_tool()
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(phase='after', out=Path(temp) / 'run',
                                   adb='adb', serial=None, targets_csv=[])
            with patch.object(tool, 'command', side_effect=AssertionError('ADB must not run')):
                with self.assertRaisesRegex(ValueError, 'before evidence'):
                    tool.capture(args)


if __name__ == '__main__':
    unittest.main()
