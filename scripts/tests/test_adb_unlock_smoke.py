import importlib.util
from pathlib import Path
import unittest


class UnlockSmokeTests(unittest.TestCase):
    def load(self):
        path = Path(__file__).resolve().parents[1] / 'adb-unlock-smoke.py'
        self.assertTrue(path.exists(), 'Missing bounded PIN UI readiness helper')
        spec = importlib.util.spec_from_file_location('unlock_smoke', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_pin_is_sent_only_after_pin_entry_exists_and_unlock_is_verified(self):
        module = self.load()
        calls = []
        now = [0]
        injected = [False]
        def command(args):
            calls.append(args)
            if args == ['shell', 'dumpsys', 'user']:
                return 'RUNNING_UNLOCKED' if injected[0] else 'RUNNING_LOCKED'
            if args[:3] == ['shell', '-T', 'cat']:
                return '<hierarchy><node resource-id="com.android.systemui:id/pinEntry" /></hierarchy>'
            if 'KEYCODE_1' in args:
                injected[0] = True
            return ''
        module.unlock(command, 20, lambda: now[0], lambda seconds: now.__setitem__(0, now[0] + seconds))
        self.assertTrue(injected[0])
        self.assertEqual(['shell', 'dumpsys', 'user'], calls[-1])
        self.assertFalse(any('locksettings' in args for args in calls))

    def test_no_pin_ui_or_wrong_pin_cannot_report_success(self):
        module = self.load()
        for hierarchy in ['<hierarchy/>', '<hierarchy><node resource-id="com.android.systemui:id/pinEntry" /></hierarchy>']:
            calls = []
            now = [0]
            def command(args):
                calls.append(args)
                return hierarchy if args[:3] == ['shell', '-T', 'cat'] else 'RUNNING_LOCKED'
            with self.assertRaises(ValueError):
                module.unlock(command, 10, lambda: now[0], lambda seconds: now.__setitem__(0, now[0] + seconds))
            if hierarchy == '<hierarchy/>':
                self.assertFalse(any('KEYCODE_1' in args for args in calls))
