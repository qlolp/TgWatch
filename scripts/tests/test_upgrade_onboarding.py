import importlib.util
from pathlib import Path
import unittest


class UpgradeOnboardingTests(unittest.TestCase):
    def load(self):
        path = Path(__file__).resolve().parents[1] / 'prepare-upgrade-predecessor.py'
        self.assertTrue(path.exists(), 'Missing real predecessor onboarding preparation')
        spec = importlib.util.spec_from_file_location('upgrade_onboarding', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def run_fixture(self, module, guide=True, start_works=True, valid_button=True):
        now = [0.0]
        taps = []
        state = {'guide': guide, 'started': not guide}
        def command(args):
            if args[:4] == ['shell', 'dumpsys', 'activity', 'services']:
                return 'isForeground=true' if state['started'] else ''
            if args[:3] == ['shell', '-T', 'cat']:
                if args[-1].endswith('tgwatch.xml'):
                    return '<map><boolean name="enabled" value="false" /></map>'
                name = 'btnOnboardingSkip' if state['guide'] else 'btnToggle'
                enabled = 'true' if valid_button else 'false'
                return ('<hierarchy><node package="ru.tgwatch" resource-id="ru.tgwatch:id/' + name +
                        '" enabled="' + enabled + '" clickable="true" bounds="[10,20][110,80]" /></hierarchy>')
            if args[:3] == ['shell', 'input', 'tap']:
                taps.append(args)
                if state['guide']: state['guide'] = False
                elif start_works: state['started'] = True
            return ''
        pause = lambda seconds: now.__setitem__(0, now[0] + seconds)
        return command, pause, now, taps

    def test_fresh_predecessor_skips_guide_and_starts_only_through_real_controls(self):
        module = self.load()
        command, pause, now, taps = self.run_fixture(module)
        module.prepare(command, 8, lambda: now[0], pause)
        self.assertEqual(2, len(taps))
        self.assertEqual(['shell', 'input', 'tap', '60', '50'], taps[0])

    def test_already_running_legacy_predecessor_is_never_toggled_off(self):
        module = self.load()
        command, pause, now, taps = self.run_fixture(module, guide=False)
        module.prepare(command, 8, lambda: now[0], pause)
        self.assertEqual([], taps)

    def test_start_without_foreground_cannot_pass_or_repeat_toggle(self):
        module = self.load()
        command, pause, now, taps = self.run_fixture(module, start_works=False)
        with self.assertRaises(ValueError): module.prepare(command, 8, lambda: now[0], pause)
        self.assertEqual(2, len(taps))

    def test_disabled_controls_cannot_be_clicked_to_claim_readiness(self):
        module = self.load()
        command, pause, now, taps = self.run_fixture(module, valid_button=False)
        with self.assertRaises(ValueError): module.prepare(command, 8, lambda: now[0], pause)
        self.assertEqual([], taps)

    def test_small_viewport_scrolls_until_start_is_visible_within_the_shared_deadline(self):
        module = self.load()
        now, swipes, taps = [0.0], [], []
        def command(args):
            if args[:4] == ['shell', 'dumpsys', 'activity', 'services']:
                return 'isForeground=true' if taps else ''
            if args[:3] == ['shell', '-T', 'cat']:
                if args[-1].endswith('tgwatch.xml'):
                    return '<map><boolean name="enabled" value="false" /></map>'
                start = ('<node package="ru.tgwatch" resource-id="ru.tgwatch:id/btnToggle" '
                         'enabled="true" clickable="true" bounds="[10,400][110,450]" />') if len(swipes) >= 4 else ''
                return ('<hierarchy><node package="ru.tgwatch" resource-id="ru.tgwatch:id/mainScroll" '
                        'bounds="[0,20][320,620]" />' + start + '</hierarchy>')
            if args[:3] == ['shell', 'input', 'swipe']: swipes.append(args)
            if args[:3] == ['shell', 'input', 'tap']: taps.append(args)
            return ''
        module.prepare(command, 8, lambda: now[0], lambda seconds: now.__setitem__(0, now[0] + seconds))
        self.assertEqual(4, len(swipes))
        self.assertEqual(1, len(taps))

    def test_bounds_reject_missing_negative_or_empty_coordinates(self):
        module = self.load()
        for bounds in ['', '[-1,0][20,30]', '[10,10][10,20]', '[0,20][10,10]']:
            self.assertIsNone(module.center(bounds))


if __name__ == '__main__':
    unittest.main()
