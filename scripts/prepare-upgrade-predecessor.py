#!/usr/bin/env python3
"""Start a fresh published predecessor through its real UI on a disposable emulator."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PREFS = '/data/user_de/0/ru.tgwatch/shared_prefs/tgwatch.xml'


def controls(command):
    # A ticking status screen need not become accessibility-idle. Read a fresh,
    # bounded allowlist through separate self-targeted instrumentation instead.
    output = command(['shell', 'am', 'instrument', '-w', '-r',
                      'ru.tgwatch.testui/.SnapshotInstrumentation'])
    lines = output.splitlines()
    prefix = 'INSTRUMENTATION_RESULT: controls='
    rows = [line[len(prefix):] for line in lines if line.startswith(prefix)]
    codes = [line.strip() for line in lines if line.startswith('INSTRUMENTATION_CODE:')]
    if len(rows) != 1 or codes != ['INSTRUMENTATION_CODE: -1'] or len(rows[0]) > 4096:
        raise ValueError('No successful fresh control snapshot')
    root = ET.fromstring(rows[0])
    if root.tag != 'hierarchy':
        raise ValueError('Invalid control snapshot')
    return root


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
    while clock() < deadline:
        if 'isForeground=true' in command(['shell', 'dumpsys', 'activity', 'services',
                                           'ru.tgwatch/.MonitorService']):
            return
        try:
            root = controls(command)
            nodes = {n.get('resource-id'): n for n in root.iter('node') if n.get('package') == 'ru.tgwatch'}
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
                            command(['shell', 'input', 'swipe', x, str(top + (bottom - top) * 3 // 4),
                                     x, str(top + (bottom - top) // 4), '300'])
        except (subprocess.SubprocessError, ET.ParseError, ValueError):
            pass
        pause(.5)
    raise ValueError('Predecessor did not enter foreground monitoring through its UI')


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
        print('Predecessor UI preparation failed: ' + type(error).__name__, file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
