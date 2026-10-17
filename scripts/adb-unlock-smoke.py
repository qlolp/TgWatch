#!/usr/bin/env python3
"""Unlock only the disposable smoke emulator's fixed test PIN after its UI is ready."""
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def unlock(command, timeout=180, clock=time.monotonic, pause=time.sleep):
    deadline = clock() + timeout
    attempts = 0
    while clock() < deadline:
        try:
            if 'RUNNING_UNLOCKED' in command(['shell', 'dumpsys', 'user']):
                return
            command(['shell', 'input', 'keyevent', 'KEYCODE_WAKEUP', 'KEYCODE_MENU'])
            command(['shell', 'uiautomator', 'dump', '--compressed', '/data/local/tmp/tgwatch-unlock-ui.xml'])
            raw = command(['shell', '-T', 'cat', '/data/local/tmp/tgwatch-unlock-ui.xml'])
            ready = any(node.get('resource-id') == 'com.android.systemui:id/pinEntry'
                        for node in ET.fromstring(raw).iter('node'))
            if ready and attempts < 3:
                command(['shell', 'input', 'keyevent'] + ['KEYCODE_DEL'] * 8)
                command(['shell', 'input', 'keyevent', 'KEYCODE_1', 'KEYCODE_2', 'KEYCODE_3', 'KEYCODE_4', 'KEYCODE_ENTER'])
                attempts += 1
        except (subprocess.SubprocessError, ET.ParseError):
            pass
        pause(2)
    raise ValueError('Test user did not unlock through the PIN screen before the deadline')


def main():
    command = ['adb'] + (['-s', os.environ['ANDROID_SERIAL']] if os.environ.get('ANDROID_SERIAL') else [])
    deadline = time.monotonic() + 180
    def call(args):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('PIN UI readiness deadline expired')
        return subprocess.run(command + args, check=True, capture_output=True, text=True,
                              timeout=min(30, remaining)).stdout
    try:
        unlock(call)
        print('Disposable test user unlocked through PIN UI')
        return 0
    except (OSError, ValueError) as error:
        print(f'Smoke unlock failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
