#!/usr/bin/env python3
"""Start a fresh published predecessor through its real UI on a disposable emulator."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PREFS = '/data/user_de/0/ru.tgwatch/shared_prefs/tgwatch.xml'
UI = '/data/local/tmp/tgwatch-upgrade-ui.xml'
# API35's legacy launcher omits its optional android.test dependency from CLASSPATH.
UI_RUNTIME = ':'.join('/system/framework/' + name + '.jar' for name in
                      ('uiautomator', 'android.test.runner', 'android.test.base', 'android.test.mock')) + \
             ':/data/local/tmp/tgwatch-upgrade-ui.jar'


class UiPreparationError(ValueError):
    """Contains fixed control metadata only, never screen text or arbitrary IDs."""


def center(bounds):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds or '')
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    if not (left < right <= 20000 and top < bottom <= 20000):
        return None
    return (left + right) // 2, (top + bottom) // 2


def prepare(command, timeout=30, clock=time.monotonic, pause=time.sleep):
    deadline = clock() + timeout
    skipped = False
    started = False
    captures = swipes = 0
    disabled = None
    control_state = 'unread'
    last_failure = 'none'
    while clock() < deadline:
        if 'isForeground=true' in command(['shell', 'dumpsys', 'activity', 'services',
                                           'ru.tgwatch/.MonitorService']):
            return
        try:
            # dump waits for an idle second, which a one-second status ticker need not provide.
            # A failed/null-root capture must not reuse old coordinates.
            command(['shell', 'rm', '-f', UI])
            command(['shell', 'env', 'CLASSPATH=' + UI_RUNTIME, 'app_process', '/system/bin',
                     'com.android.commands.uiautomator.Launcher', 'runtest',
                     '-c', 'ru.tgwatch.tools.UpgradeUiSnapshot'])
            root = ET.fromstring(command(['shell', '-T', 'cat', UI]))
            nodes = {n.get('resource-id'): n for n in root.iter('node') if n.get('package') == 'ru.tgwatch'}
            captures += 1
            fixed = ('btnOnboardingSkip', 'btnToggle', 'mainScroll')
            control_state = ','.join(name + ':' + str((node.get('enabled') == 'true',
                node.get('clickable') == 'true', center(node.get('bounds'))))
                for name in fixed if (node := nodes.get('ru.tgwatch:id/' + name)) is not None)
            control_state += ';scrollable=' + str(any(n.get('scrollable') == 'true' for n in nodes.values()))
            def tap(name):
                node = nodes.get('ru.tgwatch:id/' + name)
                point = center(node.get('bounds')) if node is not None else None
                if point and node.get('enabled') == 'true' and node.get('clickable') == 'true':
                    command(['shell', 'input', 'tap', *map(str, point)])
                    return True
                return False
            if not skipped and tap('btnOnboardingSkip'):
                skipped = True
            elif not started and 'ru.tgwatch:id/btnOnboardingSkip' not in nodes:
                prefs = ET.fromstring(command(['shell', '-T', 'cat', PREFS]))
                disabled = any(n.get('name') == 'enabled' and n.get('value') == 'false'
                               for n in prefs.findall('boolean'))
                if disabled:
                    started = tap('btnToggle')
                    scroll = nodes.get('ru.tgwatch:id/mainScroll')
                    if not started and scroll is not None:
                        match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', scroll.get('bounds', ''))
                        if match and center(scroll.get('bounds')):
                            left, top, right, bottom = map(int, match.groups())
                            x = str((left + right) // 2)
                            # Overlapping, slow steps keep every row observable instead of flinging past it.
                            command(['shell', 'input', 'swipe', x, str(top + (bottom - top) * 3 // 4),
                                     x, str(top + (bottom - top) // 2), '1000'])
                            swipes += 1
        except (subprocess.SubprocessError, ET.ParseError, ValueError) as error:
            last_failure = type(error).__name__
        pause(.5)
    raise UiPreparationError('Predecessor did not enter foreground monitoring through its UI; '
        f'captures={captures}, skipped={skipped}, started={started}, disabled={disabled}, swipes={swipes}, '
        f'last_failure={last_failure}, controls={control_state}')


def main():
    prefix = ['adb'] + (['-s', os.environ['ANDROID_SERIAL']] if os.environ.get('ANDROID_SERIAL') else [])
    deadline = time.monotonic() + 30
    def command(args):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('Predecessor UI deadline expired')
        return subprocess.run(prefix + args, capture_output=True, text=True, check=True,
                              timeout=min(20, remaining)).stdout
    try:
        if command(['shell', 'getprop', 'ro.kernel.qemu']).strip() != '1':
            raise ValueError('Predecessor preparation requires a disposable emulator')
        prepare(command, max(0, deadline - time.monotonic()))
        print('Published predecessor started through its own UI; observation verification follows')
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        detail = str(error) if isinstance(error, UiPreparationError) else type(error).__name__
        print('Predecessor UI preparation failed: ' + detail, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
