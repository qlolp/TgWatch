#!/usr/bin/env python3
"""Collect private, read-only Android evidence and write a redacted aggregate report."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parent
SNAPSHOT_PATH = ROOT / 'target-snapshot.py'
RAW_DUMPS = (
    'batterystats', 'alarm', 'deviceidle', 'connectivity', 'jobscheduler',
    'activity-services',
)
MAX_CAPTURE_BYTES = 64 * 1024 * 1024


def load_snapshot_module():
    spec = importlib.util.spec_from_file_location('target_snapshot', SNAPSHOT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def secure_directory(path):
    path = Path(path)
    if path.exists():
        if path.is_symlink() or not path.is_dir():
            raise ValueError('Evidence path must be a real directory')
        if stat.S_IMODE(path.stat().st_mode) & 0o077:
            raise ValueError('Evidence directory must be private (mode 0700)')
    else:
        path.mkdir(mode=0o700, parents=True)
    return path


def write_private(path, data):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, 'O_NOFOLLOW'):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)


def command(adb, serial, arguments, timeout=30, missing=False):
    invocation = [adb] + (['-s', serial] if serial else []) + arguments
    try:
        result = subprocess.run(invocation, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError('ADB evidence command failed') from error
    if result.returncode:
        if missing and b'No such file or directory' in result.stderr:
            raise FileNotFoundError('App evidence file is missing')
        raise ValueError('ADB evidence command failed')
    if len(result.stdout) > MAX_CAPTURE_BYTES:
        raise ValueError('ADB evidence output exceeded the local capture limit')
    return result.stdout


def property(adb, serial, name):
    value = command(adb, serial, ['shell', 'getprop', name]).decode('utf-8', 'replace').strip()
    return value[:256]


def parse_battery(raw):
    text = raw.decode('utf-8', 'replace')
    def field(name):
        match = re.search(rf'(?m)^\s*{re.escape(name)}:\s*(true|false|[0-9]+)\s*$', text, re.I)
        return match.group(1).lower() if match else None
    level = field('level')
    return {
        'level_percent': int(level) if level is not None and level.isdigit() and int(level) <= 100 else None,
        'ac_powered': field('AC powered'),
        'usb_powered': field('USB powered'),
        'wireless_powered': field('Wireless powered'),
    }


def target_snapshot(adb, serial):
    module = load_snapshot_module()
    def selected_adb(*arguments, missing=False):
        return command(adb, serial, list(arguments), missing=missing)
    module.adb = selected_adb
    return module.capture(module.remote_read)


def capture(args):
    directory = secure_directory(args.out)
    phase = args.phase
    metadata_path = directory / f'{phase}.json'
    if metadata_path.exists():
        raise ValueError(f'{phase} evidence already exists; choose a fresh run directory')
    started_ms = int(time.time() * 1000)
    device = {
        'manufacturer': property(args.adb, args.serial, 'ro.product.manufacturer'),
        'model': property(args.adb, args.serial, 'ro.product.model'),
        'android_release': property(args.adb, args.serial, 'ro.build.version.release'),
        'android_sdk': property(args.adb, args.serial, 'ro.build.version.sdk'),
    }
    battery_raw = command(args.adb, args.serial, ['shell', 'dumpsys', 'battery'])
    battery_sampled_at_ms = int(time.time() * 1000)
    snapshot = target_snapshot(args.adb, args.serial)
    for name in RAW_DUMPS:
        if name == 'activity-services':
            args_for_dump = ['shell', 'dumpsys', 'activity', 'services', 'ru.tgwatch']
        else:
            args_for_dump = ['shell', 'dumpsys', name]
        raw = command(args.adb, args.serial, args_for_dump)
        write_private(directory / f'{phase}-{name}.txt', raw)
    metadata = {
        'schema_version': 1,
        'phase': phase,
        'captured_at_ms': int(time.time() * 1000),
        'capture_started_at_ms': started_ms,
        'battery_sampled_at_ms': battery_sampled_at_ms,
        'device': device,
        'battery': parse_battery(battery_raw),
        'targets': snapshot,
    }
    write_private(metadata_path, json.dumps(metadata, separators=(',', ':')).encode())
    print(f'Captured private {phase} evidence in {directory}')


def percentile(samples, quantile):
    if not samples:
        return None
    ordered = sorted(samples)
    return ordered[math.ceil(quantile * len(ordered)) - 1]


def target_metrics(before, after, max_gap_ms):
    start = before['targets']['captured_at_ms']
    end = after['targets']['captured_at_ms']
    before_rows = before['targets']['observations']
    after_rows = after['targets']['observations']
    names = sorted({row['target_id'] for row in before_rows + after_rows})
    result = []
    for name in names:
        own = [row for row in after_rows if row['target_id'] == name]
        revision = max((row['endpoint_revision'] for row in own), default=0)
        own = [row for row in own if row['endpoint_revision'] == revision]
        epoch = max((row['clock_epoch'] for row in own), default=0)
        own = [row for row in own if row['clock_epoch'] == epoch and row['until_ms'] > row['at_ms']]
        window_rows = [row for row in own if start <= row['at_ms'] < end]
        anchors = [row for row in own if row['at_ms'] < start]
        anchor = max(anchors, key=lambda row: row['at_ms']) if anchors else None
        intervals = sorted(row['at_ms'] for row in ([anchor] if anchor else []) + window_rows)
        check_intervals = [right - left for left, right in zip(intervals, intervals[1:]) if right > left]
        latencies = [row['latency_ms'] for row in window_rows if row['latency_ms'] >= 0]
        cursor = start
        gaps = []
        for row in sorted(window_rows, key=lambda item: (item['at_ms'], item['until_ms'])):
            low, high = max(start, row['at_ms']), min(end, row['until_ms'])
            if low > cursor:
                gaps.append(low - cursor)
            cursor = max(cursor, high)
        if cursor < end:
            gaps.append(end - cursor)
        last_check = max((row['at_ms'] for row in own if row['at_ms'] < end), default=None)
        age = end - last_check if last_check is not None else None
        counts = {}
        for row in window_rows:
            counts[row['status']] = counts.get(row['status'], 0) + 1
        result.append({
            'target': name,
            'observations': len(window_rows),
            'status_counts': counts,
            'probe_latency_ms_p50': percentile(latencies, .50),
            'probe_latency_ms_p95': percentile(latencies, .95),
            'check_interval_ms_p50': percentile(check_intervals, .50),
            'check_interval_ms_p95': percentile(check_intervals, .95),
            'history_gap_count': len(gaps),
            'history_gap_max_ms': max(gaps, default=0),
            'history_gap_over_limit': any(gap > max_gap_ms for gap in gaps),
            'last_check_age_ms': age,
            'stale': age is None or age > max_gap_ms,
        })
    return result


def read_capture(path, expected_phase):
    value = json.loads(Path(path).read_text(encoding='utf-8'))
    if (not isinstance(value, dict) or value.get('schema_version') != 1
            or value.get('phase') != expected_phase):
        raise ValueError(f'Invalid {expected_phase} evidence')
    if not isinstance(value.get('device'), dict) or not isinstance(value.get('battery'), dict):
        raise ValueError('Invalid evidence metadata')
    module = load_snapshot_module()
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8') as stream:
        json.dump(value.get('targets'), stream)
        stream.flush()
        value['targets'] = module.load_export(stream.name)
    if type(value.get('captured_at_ms')) is not int:
        raise ValueError('Invalid evidence timestamp')
    return value


def summarize(args):
    before = read_capture(args.before, 'before')
    after = read_capture(args.after, 'after')
    if before['device'] != after['device']:
        raise ValueError('Before/after device identity does not match')
    target_elapsed = (after['targets']['captured_at_ms'] -
                      before['targets']['captured_at_ms'])
    battery_elapsed = after['battery_sampled_at_ms'] - before['battery_sampled_at_ms']
    if target_elapsed <= 0 or battery_elapsed <= 0 or not 1 <= args.max_gap_seconds <= 86400:
        raise ValueError('Invalid evidence duration or freshness limit')
    battery_before = before['battery']
    battery_after = after['battery']
    powered = any(value == 'true' for value in (
        battery_before.get('ac_powered'), battery_before.get('usb_powered'),
        battery_before.get('wireless_powered'), battery_after.get('ac_powered'),
        battery_after.get('usb_powered'), battery_after.get('wireless_powered'),
    ))
    start_level, end_level = battery_before.get('level_percent'), battery_after.get('level_percent')
    delta = (start_level - end_level) if type(start_level) is int and type(end_level) is int else None
    daily = (delta * 86400000 / battery_elapsed
             if delta is not None and battery_elapsed >= 23 * 60 * 60 * 1000 and not powered else None)
    report = {
        'schema_version': 1,
        'device': after['device'],
        'window_hours': round(battery_elapsed / 3600000, 3),
        'battery_start_percent': start_level,
        'battery_end_percent': end_level,
        'battery_drop_percentage_points': delta,
        'battery_percentage_points_per_24h': round(daily, 2) if daily is not None else None,
        'battery_measurement_note': (
            'Daily rate withheld: device was powered at capture time.'
            if powered else 'A 24-hour rate is reported only for an unpowered window of at least 23 hours.'
        ),
        'freshness_limit_seconds': args.max_gap_seconds,
        'targets': target_metrics(before, after, args.max_gap_seconds * 1000),
    }
    content = json.dumps(report, indent=2, sort_keys=True) + '\n'
    destination = Path(args.out)
    if destination.exists():
        raise ValueError('Aggregate report output already exists')
    write_private(destination, content.encode())
    print(f'Wrote redacted aggregate report to {destination}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    take = commands.add_parser('capture')
    take.add_argument('--phase', choices=('before', 'after'), required=True)
    take.add_argument('--out', type=Path, required=True, help='Private local run directory, mode 0700')
    take.add_argument('--adb', default='adb')
    take.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    report = commands.add_parser('summarize')
    report.add_argument('--before', required=True, type=Path)
    report.add_argument('--after', required=True, type=Path)
    report.add_argument('--max-gap-seconds', type=int, required=True,
                        help='Profile-specific maximum acceptable stale/history gap')
    report.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    try:
        capture(args) if args.command == 'capture' else summarize(args)
        return 0
    except (OSError, ValueError, json.JSONDecodeError, UnicodeError) as error:
        print(f'Android device evidence failed: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
