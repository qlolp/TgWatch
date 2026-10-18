#!/usr/bin/env python3
"""Prepare file-encryption lifecycle tests on an explicitly disposable emulator."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import time


spec = importlib.util.spec_from_file_location('adb_readiness', Path(__file__).with_name('adb-readiness.py'))
readiness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(readiness)


class PreparationError(Exception):
    pass


def prepare(command, timeout, command_timeout, interval, disposable):
    deadline = time.monotonic() + timeout

    def remaining():
        value = deadline - time.monotonic()
        if value <= 0:
            raise PreparationError('Direct Boot preparation deadline expired')
        return value

    def invoke(arguments, *, probe=False):
        try:
            return subprocess.run(command + arguments, capture_output=True, text=True,
                                  timeout=min(command_timeout, remaining()))
        except subprocess.TimeoutExpired:
            if probe:
                return None
            raise PreparationError('ADB command deadline expired') from None
        except OSError:
            raise PreparationError('ADB could not be started') from None

    def value(arguments):
        result = invoke(arguments)
        if result.returncode:
            raise PreparationError('Unable to read emulator storage state')
        return result.stdout.strip()

    def mode():
        # New Android versions need no legacy sm command or emulation.
        if value(['shell', 'getprop', 'ro.crypto.type']) == 'file':
            return 'native'
        return value(['shell', 'sm', 'get-fbe-mode'])

    if value(['shell', 'getprop', 'ro.kernel.qemu']) != '1':
        raise PreparationError('Direct Boot preparation requires an emulator, never a physical device')
    readiness.ensure_ready(command, remaining(), command_timeout, interval)
    current = mode()
    if current in ('native', 'emulated'):
        return current
    if current != 'none':
        raise PreparationError('Unsupported emulator file-encryption mode')
    if not disposable:
        raise PreparationError('FBE emulation requires explicit --disposable opt-in; it reboots the emulator')

    before = value(['shell', 'cat', '/proc/sys/kernel/random/boot_id'])
    if not before:
        raise PreparationError('Cannot establish the original emulator boot')
    changed = invoke(['shell', 'sm', 'set-emulate-fbe', 'true'])
    # Android8 can close the binder before sm returns because the setting reboots it.
    reboot_errors = ('DeadObjectException', 'device offline', 'error: closed')
    if changed.returncode and not any(item in changed.stderr + changed.stdout for item in reboot_errors):
        raise PreparationError('Unable to enable emulator FBE')

    while True:
        probe = invoke(['shell', 'cat', '/proc/sys/kernel/random/boot_id'], probe=True)
        if probe is not None and probe.returncode == 0:
            after = probe.stdout.strip()
            if after and after != before:
                break
        time.sleep(min(interval, remaining()))

    readiness.ensure_ready(command, remaining(), command_timeout, interval)
    current = mode()
    if current not in ('native', 'emulated'):
        raise PreparationError('FBE was not enabled after the verified reboot')
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial')
    parser.add_argument('--timeout', type=readiness.positive_number, default=300)
    parser.add_argument('--command-timeout', type=readiness.positive_number, default=25)
    parser.add_argument('--interval', type=readiness.positive_number, default=1)
    parser.add_argument('--disposable', action='store_true',
                        help='Allow enabling FBE emulation and rebooting a disposable emulator')
    args = parser.parse_args()
    command = [args.adb] + (['-s', args.serial] if args.serial else [])
    try:
        current = prepare(command, args.timeout, args.command_timeout, args.interval, args.disposable)
    except (PreparationError, readiness.ReadinessError) as error:
        print(f'Direct Boot preparation failed: {error}', file=sys.stderr)
        return 1
    print(f'Direct Boot test storage ready: {current}')
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print(f'::notice title=Direct Boot storage::Verified FBE mode: {current}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
