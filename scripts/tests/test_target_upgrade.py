"""Canonical migration/observation fixtures; never execute an imported path in a shell."""
import importlib.util
import json
import io
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
IDS = ('telegram', 'matrix', 'whatsapp', 'custom')
GEN = 'a' * 32

class TargetUpgradeTests(unittest.TestCase):
    def load(self):
        path = ROOT / 'scripts/target-snapshot.py'
        self.assertTrue(path.exists(), 'Missing manifest-aware target snapshot reader')
        spec = importlib.util.spec_from_file_location('target_snapshot', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def generation(self, rows=None, generation=GEN):
        targets = [dict(id=name, enabled=name == 'telegram', intervalSec=30 if name == 'telegram' else 60,
                        requiresEndpoint=name == 'custom', alerts=dict(notifyOutage=True,
                        notifyRecovery=True, notifySlow=False, slowThresholdMs=1000)) for name in IDS]
        registry = dict(formatVersion=3, createdAt=1000, settings={}, targets=targets, observations=[], log=[])
        files = {'targets-v1/active-generation': ('TGWTARGET1\n' + generation + '\n').encode(),
                 f'targets-v1/{generation}/registry.json': json.dumps(registry).encode()}
        for name in IDS:
            files[f'targets-v1/{generation}/{name}.csv'] = (rows or {}).get(name, '').encode()
        return files

    @staticmethod
    def reader(files):
        def read(path):
            if path not in files: raise FileNotFoundError(path)
            return files[path]
        return read

    def test_legacy_fixture_waits_for_a_persisted_check_and_foreground_before_stopping(self):
        module = self.load()
        self.assertTrue(hasattr(module, 'wait_legacy_start'), 'Legacy upgrade must establish a configured predecessor')
        clock = [0.0]
        reads = []
        def state():
            reads.append(clock[0])
            return 1234 if clock[0] >= 2 else 0
        with patch.object(module.time, 'monotonic', side_effect=lambda: clock[0]), \
             patch.object(module.time, 'sleep', side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
            module.wait_legacy_start(10, state, lambda: clock[0] >= 4)
        self.assertGreaterEqual(clock[0], 4)
        self.assertGreaterEqual(len(reads), 3)

    def test_legacy_fixture_cannot_pass_with_only_a_timestamp_or_only_a_service(self):
        module = self.load()
        self.assertTrue(hasattr(module, 'wait_legacy_start'), 'Missing legacy baseline readiness gate')
        for checked, foreground in [(0, True), (1234, False)]:
            clock = [0.0]
            with patch.object(module.time, 'monotonic', side_effect=lambda: clock[0]), \
                 patch.object(module.time, 'sleep', side_effect=lambda delay: clock.__setitem__(0, clock[0] + delay)):
                with self.assertRaises(ValueError):
                    module.wait_legacy_start(5, lambda: checked, lambda: foreground)

    def test_legacy_only_upgrade_preserves_telegram(self):
        module = self.load()
        before = module.capture(self.reader({'history-v2.csv': b'1000,2000,TG_DOWN,40,2\n'}), 2000)
        after = module.capture(self.reader(self.generation({'telegram':
            '1000,2000,DOWN,40,2,0\n3000,4000,OK,20,2,0\n'})), 4000)
        module.assert_preserved(before, after)
        self.assertEqual({'telegram'}, {row['target_id'] for row in before['observations']})
        self.assertTrue(module.has_observation(after, 'telegram', 3000))

    def test_manifest_selects_whole_generation_and_ignores_uncommitted_stage(self):
        module = self.load()
        files = self.generation({'telegram': '1000,2000,OK,20,1,0\n'})
        files['history-v2.csv'] = b'9000,10000,TG_DOWN,10,1\n'
        files[f'targets-v1/{"b" * 32}/telegram.csv'] = b'8000,9000,DOWN,10,1,0\n'
        data = module.capture(self.reader(files), 10000)
        self.assertEqual(GEN, data['generation'])
        self.assertEqual([1000], [row['at_ms'] for row in data['observations']])

    def test_failed_generation_does_not_fall_back_to_stale_legacy(self):
        module = self.load()
        files = self.generation()
        del files[f'targets-v1/{GEN}/custom.csv']
        files['history-v2.csv'] = b'1000,2000,OK,20,1\n'
        with self.assertRaises(ValueError): module.capture(self.reader(files), 2000)

    def test_matrix_timestamp_cannot_satisfy_telegram_await(self):
        module = self.load()
        data = module.capture(self.reader(self.generation({'matrix': '3000,4000,OK,10,1,0\n',
            'telegram': '1000,2000,OK,20,1,0\n'})), 4000)
        self.assertFalse(module.has_observation(data, 'telegram', 3000))
        self.assertTrue(module.has_observation(data, 'matrix', 3000))

    def await_boot_observation(self, at, boot_stat):
        module = self.load()
        data = module.capture(self.reader(self.generation({'telegram': f'{at},{at + 1000},OK,20,1,0\n'})), at + 1000)
        args = SimpleNamespace(after=1000, timeout=90, foreground=True, since_boot=True,
                               status=None, exclude_status=[])
        def adb(*arguments):
            return boot_stat if arguments == ('shell', 'cat', '/proc/stat') else b'isForeground=true'
        with patch.object(module, 'state', return_value=at), patch.object(module, 'capture', return_value=data), \
             patch.object(module, 'adb', side_effect=adb), \
             patch.object(module.time, 'monotonic', side_effect=[0, 0, 91]), \
             patch.object(module.time, 'sleep'), patch('sys.stdout', new_callable=io.StringIO):
            module.wait_observation(args)

    def test_preboot_check_after_old_baseline_cannot_satisfy_locked_boot_await(self):
        with self.assertRaises(ValueError):
            self.await_boot_observation(2000, b'cpu 1 2 3\nbtime 2\n')

    def test_real_observation_after_current_kernel_boot_is_accepted(self):
        self.await_boot_observation(3000, b'cpu 1 2 3\nbtime 2\n')

    def test_missing_or_ambiguous_boot_time_cannot_fall_back_to_old_baseline(self):
        for value in (b'cpu 1 2 3\n', b'btime 0\n', b'btime -1\n', b'btime 2\nbtime 3\n'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.await_boot_observation(3000, value)

    def test_offline_observation_must_be_no_network_not_unknown(self):
        module = self.load()
        files = self.generation({'telegram': '1000,2000,UNKNOWN,-1,1,0\n'})
        data = module.capture(self.reader(files), 2000)
        self.assertFalse(module.has_observation(data, 'telegram', 1000, status='NO_NETWORK'))
        files[f'targets-v1/{GEN}/telegram.csv'] = b'1000,2000,NO_NETWORK,-1,1,0\n'
        data = module.capture(self.reader(files), 2000)
        self.assertTrue(module.has_observation(data, 'telegram', 1000, status='NO_NETWORK'))
        self.assertFalse(module.has_observation(data, 'telegram', 1000, exclude_status=('NO_NETWORK',)))

    def test_recovery_requires_new_measured_observation_without_demanding_target_success(self):
        module = self.load()
        data = module.capture(self.reader(self.generation({'telegram': '1000,2000,UNKNOWN,-1,1,0\n'})), 2000)
        self.assertTrue(module.has_observation(data, 'telegram', 1000, exclude_status=('NO_NETWORK',)))
        data['observations'][0]['until_ms'] = 1000
        self.assertFalse(module.has_observation(data, 'telegram', 1000, exclude_status=('NO_NETWORK',)))
        smoke = (ROOT / 'scripts/android-smoke.sh').read_text()
        self.assertNotIn('--exclude-status UNKNOWN', smoke)

    def test_old_endpoint_and_clock_epoch_cannot_satisfy_fresh_await(self):
        module = self.load()
        data = module.capture(self.reader(self.generation({'telegram':
            '1000,2000,OK,20,5,0\n3000,4000,OK,20,1,1\n2500,2500,UNKNOWN,-1,2,1\n'})), 4000)
        self.assertFalse(module.has_observation(data, 'telegram', 1000))
        self.assertFalse(module.has_observation(data, 'telegram', 3000))
        self.assertFalse(module.has_observation(data, 'telegram', 2500))

    def test_manifest_rejects_paths_before_reading_imported_names(self):
        module = self.load()
        called = []
        def read(path):
            called.append(path)
            return b'TGWTARGET1\n../../private\n'
        with self.assertRaises(ValueError): module.capture(read, 2000)
        self.assertEqual(['targets-v1/active-generation'], called)

    def test_predicted_future_tail_may_be_clipped_but_observed_history_must_survive(self):
        module = self.load()
        before = module.capture(self.reader({'history-v2.csv': b'1000,5000,OK,20,1\n'}), 2000)
        files = self.generation({'telegram': '1000,2500,OK,20,1,0\n'})
        after = module.capture(self.reader(files), 3000)
        module.assert_preserved(before, after)
        after['observations'][0]['until_ms'] = 1500
        with self.assertRaises(AssertionError): module.assert_preserved(before, after)

    def test_archived_clock_coverage_must_not_be_treated_as_a_future_prediction(self):
        module = self.load()
        files = self.generation({'telegram':
            '10000,11000,DOWN,40,0,0\n1000,5000,OK,20,1,0\n'})
        before = module.capture(self.reader(files), 2000)
        after = module.capture(self.reader(files), 3000)
        after['observations'][0]['until_ms'] = 10500
        with self.assertRaises(AssertionError): module.assert_preserved(before, after)

    def test_invalid_registry_cannot_count_as_a_complete_generation(self):
        module = self.load()
        files = self.generation()
        name = f'targets-v1/{GEN}/registry.json'
        value = json.loads(files[name])
        value['targets'][0]['intervalSec'] = True
        files[name] = json.dumps(value).encode()
        with self.assertRaises(ValueError): module.capture(self.reader(files), 2000)

    def test_generation_switch_retries_the_entire_cohort(self):
        module = self.load()
        files = self.generation({'telegram': '1000,2000,OK,20,1,0\n'})
        newer = self.generation({'telegram': '3000,4000,DOWN,20,1,0\n'}, 'b' * 32)
        files.update({key: value for key, value in newer.items() if key != 'targets-v1/active-generation'})
        reads = 0
        def read(name):
            nonlocal reads
            if name == 'targets-v1/active-generation':
                reads += 1
                return files[name] if reads == 1 else newer[name]
            return files[name]
        data = module.capture(read, 4000)
        self.assertEqual('b' * 32, data['generation'])
        self.assertEqual([3000], [row['at_ms'] for row in data['observations']])

    def test_remote_reader_uses_shell_v2_exit_status_for_missing_files(self):
        module = self.load()
        from unittest.mock import patch
        from types import SimpleNamespace
        def run(command, **kwargs):
            self.assertEqual(['adb', 'shell', '-T', 'cat'], command[:4])
            return SimpleNamespace(returncode=1, stdout=b'', stderr=b'cat: No such file or directory')
        with patch.object(module.subprocess, 'run', side_effect=run):
            with self.assertRaises(FileNotFoundError): module.remote_read('targets-v1/active-generation')

if __name__ == '__main__': unittest.main()
