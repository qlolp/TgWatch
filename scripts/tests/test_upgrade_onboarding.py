import importlib.util
from pathlib import Path
import subprocess
import unittest


class UpgradeOnboardingTests(unittest.TestCase):
    def test_ui_deadline_reports_only_fixed_control_state_for_diagnosis(self):
        module = self.load()
        command, pause, now, taps = self.run_fixture(module, valid_button=False)
        with self.assertRaises(ValueError) as failure:
            module.prepare(command, 2, lambda: now[0], pause)
        self.assertIn('controls=', str(failure.exception))
        self.assertIn('btnOnboardingSkip', str(failure.exception))
        self.assertNotIn('http', str(failure.exception))
        self.assertEqual([], taps)

    def test_continuously_updating_predecessor_still_starts_through_visible_controls(self):
        module = self.load()
        base, pause, now, taps = self.run_fixture(module)
        def command(args):
            if taps and args[:3] == ['shell', 'uiautomator', 'dump']:
                # Android's dump waits for one idle second; the status screen ticks every second.
                raise subprocess.CalledProcessError(1, args, stderr='could not get idle state')
            return base(args)
        try:
            module.prepare(command, 8, lambda: now[0], pause)
        except ValueError:
            self.fail('A ticking status screen must not prevent starting through real controls')
        self.assertEqual(2, len(taps))

    def test_failed_snapshot_cannot_click_coordinates_from_an_old_xml_file(self):
        module = self.load()
        now = [0.0]
        stale_file = [True]
        taps = []
        def command(args):
            if args[:3] == ['shell', 'rm', '-f']:
                stale_file[0] = False
            elif args[:3] == ['shell', '-T', 'cat']:
                if args[-1].endswith('tgwatch.xml'):
                    return '<map><boolean name="enabled" value="false" /></map>'
                if not stale_file[0]:
                    raise subprocess.CalledProcessError(1, args)
                return ('<hierarchy><node package="ru.tgwatch" resource-id="ru.tgwatch:id/btnToggle" '
                        'enabled="true" clickable="true" bounds="[10,20][110,80]" /></hierarchy>')
            elif args[:3] == ['shell', 'input', 'tap']:
                taps.append(args)
            # A capture process can exit without producing a hierarchy (null root or idle timeout).
            return ''
        with self.assertRaises(ValueError):
            module.prepare(command, 2, lambda: now[0], lambda delay: now.__setitem__(0, now[0] + delay))
        self.assertEqual([], taps, 'A missing fresh snapshot must never reuse stale coordinates')

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

    def test_overlapping_scroll_steps_do_not_fling_past_the_start_button(self):
        module = self.load()
        now, offset, taps = [0.0], [0], []
        def command(args):
            if args[:4] == ['shell', 'dumpsys', 'activity', 'services']:
                return 'isForeground=true' if taps else ''
            if args[:3] == ['shell', '-T', 'cat']:
                if args[-1].endswith('tgwatch.xml'):
                    return '<map><boolean name="enabled" value="false" /></map>'
                button = ('<node package="ru.tgwatch" resource-id="ru.tgwatch:id/btnToggle" '
                          'enabled="true" clickable="true" bounds="[10,500][110,560]" />') \
                         if 450 <= offset[0] <= 900 else ''
                return ('<hierarchy><node package="ru.tgwatch" resource-id="ru.tgwatch:id/mainScroll" '
                        'bounds="[0,20][320,620]" scrollable="true" />' + button + '</hierarchy>')
            if args[:3] == ['shell', 'input', 'swipe']:
                distance = abs(int(args[4]) - int(args[6]))
                duration = int(args[-1])
                # A fast fling can jump beyond the target; a slow overlapping drag cannot.
                offset[0] += 1_200 if duration < 1_000 else distance
                now[0] += duration / 1_000
            if args[:3] == ['shell', 'input', 'tap']: taps.append(args)
            return ''
        module.prepare(command, 12, lambda: now[0], lambda seconds: now.__setitem__(0, now[0] + seconds))
        self.assertEqual(1, len(taps))

    def test_a_restored_bottom_viewport_rewinds_to_the_real_start_button(self):
        module = self.load()
        now, offset, taps, swipes = [0.0], [900], [], []
        def command(args):
            if args[:4] == ['shell', 'dumpsys', 'activity', 'services']:
                return 'isForeground=true' if taps else ''
            if args[:3] == ['shell', '-T', 'cat']:
                if args[-1].endswith('tgwatch.xml'):
                    return '<map><boolean name="enabled" value="false" /></map>'
                marker = 'tvVersion' if offset[0] >= 800 else 'targetSelector' if offset[0] <= 0 else ''
                extra = ('<node package="ru.tgwatch" resource-id="ru.tgwatch:id/' + marker +
                         '" bounds="[10,20][110,60]" />') if marker else ''
                if 100 <= offset[0] <= 400:
                    extra += ('<node package="ru.tgwatch" resource-id="ru.tgwatch:id/btnToggle" '
                              'enabled="true" clickable="true" bounds="[10,300][110,360]" />')
                return ('<hierarchy><node package="ru.tgwatch" resource-id="ru.tgwatch:id/mainScroll" '
                        'bounds="[0,20][320,620]" scrollable="true" />' + extra + '</hierarchy>')
            if args[:3] == ['shell', 'input', 'swipe']:
                swipes.append(args)
                offset[0] += int(args[4]) - int(args[6])
                now[0] += int(args[-1]) / 1_000
            if args[:3] == ['shell', 'input', 'tap']: taps.append(args)
            return ''
        module.prepare(command, 12, lambda: now[0], lambda seconds: now.__setitem__(0, now[0] + seconds))
        self.assertEqual(1, len(taps))
        self.assertTrue(any(int(call[4]) < int(call[6]) for call in swipes))

    def test_bounds_reject_missing_negative_or_empty_coordinates(self):
        module = self.load()
        for bounds in ['', '[-1,0][20,30]', '[10,10][10,20]', '[0,20][10,10]']:
            self.assertIsNone(module.center(bounds))


if __name__ == '__main__':
    unittest.main()
