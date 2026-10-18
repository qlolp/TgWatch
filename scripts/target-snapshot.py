#!/usr/bin/env python3
"""Read only the committed target generation; capture public observations for upgrade checks."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

IDS = ('telegram', 'matrix', 'whatsapp', 'custom')
KINDS = {'OK', 'DOWN', 'PARTIAL', 'NO_NETWORK', 'UNKNOWN'}
MANIFEST = 'targets-v1/active-generation'
ROOT = '/data/user_de/0/ru.tgwatch/files/'
PREFS = '/data/user_de/0/ru.tgwatch/shared_prefs/tgwatch.xml'
MAX_BYTES = 16 * 1024 * 1024
MAX_INT = 2 ** 63 - 1


def number(value, minimum=0):
    if not isinstance(value, str) or not re.fullmatch(r'-?[0-9]+', value):
        raise ValueError('Invalid journal integer')
    result = int(value)
    if not minimum <= result <= MAX_INT:
        raise ValueError('Journal integer outside bounds')
    return result


def rows(raw, target, legacy=False):
    if len(raw) > MAX_BYTES:
        raise ValueError('Oversized target journal')
    result = []
    limit = (65000 if target == 'telegram' else 11000) + 1024
    for line in raw.decode('utf-8', errors='strict').splitlines():
        if not line.strip():
            continue
        fields = line.split(',')
        if len(fields) != (5 if legacy else 6) or len(line) > 4096 or len(result) >= limit:
            raise ValueError('Invalid target journal shape')
        at, until = number(fields[0]), number(fields[1])
        kind = 'DOWN' if fields[2] == 'TG_DOWN' else fields[2]
        latency, epoch = number(fields[3], -1), number(fields[4])
        revision = 0 if legacy else number(fields[5])
        metadata = kind == 'UNKNOWN' and at == until and latency == -1
        if kind not in KINDS or not (metadata or 0 < until - at <= 600000):
            raise ValueError('Invalid target observation')
        result.append(dict(target_id=target, at_ms=at, until_ms=until, status=kind,
                           latency_ms=latency, clock_epoch=epoch, endpoint_revision=revision))
    return result


def manifest(raw):
    if len(raw) > 64:
        raise ValueError('Invalid target manifest')
    match = re.fullmatch(rb'TGWTARGET1\n([a-f0-9]{32})\n', raw)
    if match is None:
        raise ValueError('Invalid target manifest')
    return match[1].decode('ascii')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate registry key')
        result[key] = value
    return result


def registry(raw):
    if len(raw) > MAX_BYTES:
        raise ValueError('Oversized registry')
    value = json.loads(raw.decode('utf-8', errors='strict'), object_pairs_hook=unique_object,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Invalid JSON constant')))
    if not isinstance(value, dict) or set(value) != {
            'formatVersion', 'createdAt', 'settings', 'targets', 'observations', 'log'}:
        raise ValueError('Invalid registry shape')
    if type(value['formatVersion']) is not int or value['formatVersion'] != 3:
        raise ValueError('Unsupported registry')
    targets = value['targets']
    if not isinstance(targets, list) or not 1 <= len(targets) <= len(IDS):
        raise ValueError('Invalid registry targets')
    if type(value['createdAt']) is not int or not 0 <= value['createdAt'] <= MAX_INT:
        raise ValueError('Invalid registry creation time')
    if not isinstance(value['settings'], dict) or not isinstance(value['log'], list) or len(value['log']) > 150:
        raise ValueError('Invalid registry metadata')
    if any(not isinstance(line, str) or len(line) > 4096 for line in value['log']):
        raise ValueError('Invalid registry log shape')
    seen = set()
    for target in targets:
        if not isinstance(target, dict) or set(target) != {
                'id', 'enabled', 'intervalSec', 'requiresEndpoint', 'alerts'}:
            raise ValueError('Invalid registry target shape')
        name = target['id']
        if name not in IDS or name in seen:
            raise ValueError('Invalid registry target identity')
        seen.add(name)
        low, high = (10, 120) if name == 'telegram' else (60, 300)
        if type(target['intervalSec']) is not int or not low <= target['intervalSec'] <= high:
            raise ValueError('Invalid registry interval')
        if type(target['enabled']) is not bool or type(target['requiresEndpoint']) is not bool:
            raise ValueError('Invalid registry flags')
        if (name in ('telegram', 'whatsapp') and target['requiresEndpoint']) or (
                name == 'custom' and not target['requiresEndpoint']):
            raise ValueError('Invalid registry endpoint mode')
        alerts = target['alerts']
        if not isinstance(alerts, dict) or set(alerts) != {
                'notifyOutage', 'notifyRecovery', 'notifySlow', 'slowThresholdMs'}:
            raise ValueError('Invalid registry alerts')
        threshold = alerts['slowThresholdMs']
        if type(threshold) is not int or not 500 <= threshold <= 5000 or threshold % 100:
            raise ValueError('Invalid slow threshold')
        if any(type(alerts[key]) is not bool for key in ('notifyOutage', 'notifyRecovery', 'notifySlow')):
            raise ValueError('Invalid alert flags')
    if value['observations'] != []:
        raise ValueError('Registry must reference journals rather than carry observations')
    return seen


def capture(read, now_ms=None):
    """read accepts fixed relative names only; a generation switch retries the entire cohort."""
    for _ in range(3):
        try:
            pointer = read(MANIFEST)
        except FileNotFoundError:
            return dict(schema_version=1, captured_at_ms=now_ms if now_ms is not None else int(time.time() * 1000),
                        source='legacy', generation=None, observations=rows(read('history-v2.csv'), 'telegram', True))
        generation = manifest(pointer)  # Validate before constructing any dependent path.
        try:
            declared = registry(read(f'targets-v1/{generation}/registry.json'))
            observations = []
            for target in IDS:
                own = rows(read(f'targets-v1/{generation}/{target}.csv'), target)
                if target not in declared and own:
                    raise ValueError('Undeclared target journal')
                observations.extend(own)
        except (FileNotFoundError, ValueError, UnicodeError) as error:
            if read(MANIFEST) != pointer:
                continue
            raise ValueError('Incomplete or invalid active generation') from error
        if read(MANIFEST) == pointer:
            return dict(schema_version=1, captured_at_ms=now_ms if now_ms is not None else int(time.time() * 1000),
                        source='targets', generation=generation, observations=observations)
    raise ValueError('Target generation changed repeatedly during capture')


def has_observation(data, target, checked_at, status=None, exclude_status=()):
    if target not in IDS:
        raise ValueError('Unknown target')
    own = [row for row in data['observations'] if row['target_id'] == target]
    revision = max((row['endpoint_revision'] for row in own), default=0)
    own = [row for row in own if row['endpoint_revision'] == revision]
    epoch = max((row['clock_epoch'] for row in own), default=0)
    return any(row['clock_epoch'] == epoch and row['at_ms'] == checked_at
               and row['until_ms'] > row['at_ms'] and (status is None or row['status'] == status)
               and row['status'] not in exclude_status for row in own)


def assert_preserved(before, after):
    def identity(row):
        return tuple(row[key] for key in ('target_id', 'endpoint_revision', 'clock_epoch',
                                         'at_ms', 'status', 'latency_ms'))
    endings = {}
    for row in after['observations']:
        key = identity(row)
        endings[key] = max(endings.get(key, -1), row['until_ms'])
    assert before['observations'], 'Baseline has no observations'
    current = {}
    for row in before['observations']:
        key = row['target_id']
        cohort = row['endpoint_revision'], row['clock_epoch']
        current[key] = max(current.get(key, (-1, -1)), cohort)
    for row in before['observations']:
        # Predicted future coverage may be clipped by migration; observed time may not disappear.
        active = (row['endpoint_revision'], row['clock_epoch']) == current[row['target_id']]
        observed_until = max(row['at_ms'], min(row['until_ms'], before['captured_at_ms'])) if active else row['until_ms']
        assert endings.get(identity(row), -1) >= observed_until, 'Existing target observations were lost'


def load_export(path):
    raw = Path(path).read_bytes()
    if len(raw) > 32 * 1024 * 1024:
        raise ValueError('Oversized canonical snapshot')
    if not raw.lstrip().startswith(b'{'):
        observations = rows(raw, 'telegram', legacy=True)
        return dict(schema_version=1, source='legacy', generation=None,
                    captured_at_ms=max((row['until_ms'] for row in observations), default=0), observations=observations)
    data = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
    if not isinstance(data, dict) or set(data) != {
            'schema_version', 'source', 'generation', 'captured_at_ms', 'observations'}:
        raise ValueError('Invalid canonical snapshot')
    if type(data['schema_version']) is not int or data['schema_version'] != 1:
        raise ValueError('Invalid canonical version')
    if type(data['captured_at_ms']) is not int or not 0 <= data['captured_at_ms'] <= MAX_INT:
        raise ValueError('Invalid capture time')
    if data['source'] == 'targets':
        if not isinstance(data['generation'], str) or not re.fullmatch(r'[a-f0-9]{32}', data['generation']):
            raise ValueError('Invalid canonical generation')
    elif data['source'] != 'legacy' or data['generation'] is not None:
        raise ValueError('Invalid canonical source')
    if not isinstance(data['observations'], list) or len(data['observations']) > 98000 + 4 * 1024:
        raise ValueError('Invalid canonical observations')
    counts = dict.fromkeys(IDS, 0)
    for row in data['observations']:
        if not isinstance(row, dict) or set(row) != {'target_id', 'at_ms', 'until_ms', 'status',
                                                   'latency_ms', 'clock_epoch', 'endpoint_revision'}:
            raise ValueError('Invalid canonical observation shape')
        target = row['target_id']
        if target not in IDS:
            raise ValueError('Invalid canonical target')
        counts[target] += 1
        if counts[target] > (65000 if target == 'telegram' else 11000) + 1024:
            raise ValueError('Canonical target exceeds quota')
        fields = ('at_ms', 'until_ms', 'status', 'latency_ms', 'clock_epoch', 'endpoint_revision')
        rows((','.join(str(row[key]) for key in fields) + '\n').encode(), target)
    return data


def adb(*args, missing=False):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=20)
    if result.returncode:
        if missing and b'No such file or directory' in result.stderr:
            raise FileNotFoundError('Missing app file')
        raise ValueError('ADB could not read app state')
    return result.stdout


def remote_read(name):
    # name is generated only by capture() from fixed segments and a validated 32-character ID.
    if name != 'history-v2.csv' and name != MANIFEST and not re.fullmatch(
            r'targets-v1/[a-f0-9]{32}/(registry.json|telegram.csv|matrix.csv|whatsapp.csv|custom.csv)', name):
        raise ValueError('Invalid app file path')
    return adb('shell', '-T', 'cat', ROOT + name, missing=True)


def state():
    raw = adb('shell', '-T', 'cat', PREFS)
    if len(raw) > MAX_BYTES:
        raise ValueError('Oversized preferences')
    # Only allowlisted public fields are returned; never copy arbitrary preferences.
    elements = ET.fromstring(raw)
    checked = next((node.get('value') for node in elements if node.get('name') == 'last_checked'), '0')
    return number(checked)


def save(path, data):
    destination = Path(path)
    with tempfile.NamedTemporaryFile(mode='w', dir=destination.parent, delete=False, encoding='utf-8') as stream:
        temporary = Path(stream.name)
        try:
            json.dump(data, stream, separators=(',', ':'))
            stream.flush()
            os.fsync(stream.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def wait_observation(args):
    deadline = time.monotonic() + args.timeout
    after = args.after
    if args.since_boot:
        started = re.findall(rb'^btime ([1-9][0-9]*)$', adb('shell', 'cat', '/proc/stat').replace(b'\r', b''), re.M)
        if len(started) != 1:
            raise ValueError('Cannot establish current kernel boot time')
        # btime has one-second resolution. Exclude its entire boundary second so
        # a check that raced PIN setup in the previous boot cannot satisfy this.
        after = max(after, (int(started[0]) + 1) * 1000 - 1)
    while time.monotonic() < deadline:
        try:
            checked = state()
            data = capture(remote_read)
            foreground = not args.foreground or b'isForeground=true' in adb(
                'shell', 'dumpsys', 'activity', 'services', 'ru.tgwatch')
            if checked > after and foreground and has_observation(data, 'telegram', checked,
                    args.status, args.exclude_status) and state() == checked:
                print('Fresh committed Telegram observation verified')
                return
        except (OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired):
            pass  # Startup/recovery may not have materialized a complete generation yet.
        time.sleep(2)
    raise ValueError('No matching committed Telegram observation before timeout')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    take = commands.add_parser('capture')
    take.add_argument('--out', required=True)
    take.add_argument('--local-root', type=Path)
    wait = commands.add_parser('await')
    wait.add_argument('--after', type=int, required=True)
    wait.add_argument('--timeout', type=float, default=90)
    wait.add_argument('--foreground', action='store_true')
    wait.add_argument('--since-boot', action='store_true', help='Require an observation from the current kernel boot')
    wait.add_argument('--status', choices=sorted(KINDS))
    wait.add_argument('--exclude-status', choices=sorted(KINDS), action='append', default=[])
    contains = commands.add_parser('contains')
    contains.add_argument('snapshot', type=Path)
    contains.add_argument('--at', type=int, required=True)
    contains.add_argument('--target', choices=IDS, default='telegram')
    args = parser.parse_args()
    try:
        if args.command == 'capture':
            read = (lambda name: (args.local_root / name).read_bytes()) if args.local_root else remote_read
            data = capture(read)
            save(args.out, data)
            print(f"Captured {len(data['observations'])} public observations ({data['source']})")
        elif args.command == 'await':
            if args.after < 0 or not 0 < args.timeout <= 300:
                raise ValueError('Invalid observation deadline')
            wait_observation(args)
        elif not has_observation(load_export(args.snapshot), args.target, args.at):
            raise ValueError('Expected target observation missing')
        return 0
    except (OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as error:
        print(f'Target snapshot failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
