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
import time


ROOT = Path(__file__).resolve().parent
SNAPSHOT_PATH = ROOT / 'target-snapshot.py'
RAW_DUMPS = (
    'batterystats', 'alarm', 'deviceidle', 'connectivity', 'jobscheduler',
    'activity-services',
)
MAX_CAPTURE_BYTES = 64 * 1024 * 1024
MAX_EVIDENCE_BYTES = 32 * 1024 * 1024


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


def command(adb, serial, arguments, timeout=30):
    invocation = [adb] + (['-s', serial] if serial else []) + arguments
    try:
        result = subprocess.run(invocation, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError('ADB evidence command failed') from error
    if result.returncode:
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


def parse_target_csv(raw):
    if len(raw) > 32 * 1024 * 1024:
        raise ValueError('Target CSV exceeded the local import limit')
    lines = raw.decode('utf-8', 'strict').splitlines()
    header = ('from_utc,until_utc,status,duration_ms,latency_ms,clock_epoch,'
              'in_current_statistics,target_id,endpoint_revision')
    if not lines or lines[0] != header or len(lines) > 100000:
        raise ValueError('Expected a bounded TgWatch target CSV export')
    module = load_snapshot_module()
    parsed = {}
    source_rows = {}
    for line in lines[1:]:
        fields = line.split(',')
        if len(fields) != 9 or len(line) > 4096:
            raise ValueError('Invalid target CSV row')
        start = datetime.strptime(fields[0], '%Y-%m-%dT%H:%M:%S.%fZ').replace(
            tzinfo=timezone.utc)
        end = datetime.strptime(fields[1], '%Y-%m-%dT%H:%M:%S.%fZ').replace(
            tzinfo=timezone.utc)
        at_ms = int(start.timestamp() * 1000)
        until_ms = int(end.timestamp() * 1000)
        if fields[6] not in ('true', 'false') or fields[7] not in module.IDS:
            raise ValueError('Invalid target CSV identity or current-history flag')
        target = fields[7]
        latency = fields[4] if fields[4] else '-1'
        row_text = ','.join((str(at_ms), str(until_ms), fields[2], latency,
                             fields[5], fields[8])) + '\n'
        parsed.setdefault(target, []).extend(module.rows(row_text.encode(), target))
        source_rows.setdefault(target, []).append((fields[6] == 'true', fields[8], fields[5]))
        if fields[3] != str(until_ms - at_ms):
            raise ValueError('Target CSV duration does not match timestamps')
    for target, rows in parsed.items():
        current_revision = max(row['endpoint_revision'] for row in rows)
        current_epoch = max(row['clock_epoch'] for row in rows
                            if row['endpoint_revision'] == current_revision)
        for active, revision_text, epoch_text in source_rows[target]:
            expected = int(revision_text) == current_revision and int(epoch_text) == current_epoch
            if active != expected:
                raise ValueError('Target CSV current-history marker is inconsistent')
    return [row for rows in parsed.values() for row in rows], sorted(parsed)


def capture(args):
    directory = secure_directory(args.out)
    phase = args.phase
    metadata_path = directory / f'{phase}.json'
    if metadata_path.exists():
        raise ValueError(f'{phase} evidence already exists; choose a fresh run directory')
    other_phase = 'after' if phase == 'before' else 'before'
    if (directory / f'{other_phase}.json').exists() and phase == 'before':
        raise ValueError('Cannot capture before evidence after an after snapshot')
    if phase == 'after' and not (directory / 'before.json').is_file():
        raise ValueError('Capture before evidence before the after phase')
    started_ms = int(time.time() * 1000)
    device = {
        'manufacturer': property(args.adb, args.serial, 'ro.product.manufacturer'),
        'model': property(args.adb, args.serial, 'ro.product.model'),
        'android_release': property(args.adb, args.serial, 'ro.build.version.release'),
        'android_sdk': property(args.adb, args.serial, 'ro.build.version.sdk'),
    }
    if any(not value for value in device.values()):
        raise ValueError('ADB did not return a complete device identity')
    battery_raw = command(args.adb, args.serial, ['shell', 'dumpsys', 'battery'])
    battery_sampled_at_ms = int(time.time() * 1000)
    observations = []
    target_ids = []
    for source in args.targets_csv:
        raw_csv = source.read_bytes()
        rows, ids = parse_target_csv(raw_csv)
        if set(ids) & set(target_ids):
            raise ValueError('Provide only one history export per target')
        target_ids.extend(ids)
        observations.extend(rows)
        for target in ids:
            write_private(directory / f'{phase}-history-{target}.csv', raw_csv)
    if not target_ids:
        raise ValueError('At least one target CSV must contain a target identity')
    target_captured_at_ms = int(time.time() * 1000)
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
        'targets': {
            'schema_version': 1,
            'captured_at_ms': target_captured_at_ms,
            'target_ids': sorted(target_ids),
            'observations': observations,
        },
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
        coverage_rows = [row for row in own if row['until_ms'] > start and row['at_ms'] < end]
        cursor = start
        gaps = []
        for row in sorted(coverage_rows, key=lambda item: (item['at_ms'], item['until_ms'])):
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
    raw = Path(path).read_bytes()
    if len(raw) > MAX_EVIDENCE_BYTES:
        raise ValueError('Oversized device evidence')
    module = load_snapshot_module()
    value = json.loads(raw.decode('utf-8', 'strict'), object_pairs_hook=module.unique_object)
    if (not isinstance(value, dict) or set(value) != {
            'schema_version', 'phase', 'captured_at_ms', 'capture_started_at_ms',
            'battery_sampled_at_ms', 'device', 'battery', 'targets'}
            or type(value.get('schema_version')) is not int
            or value.get('schema_version') != 1
            or value.get('phase') != expected_phase):
        raise ValueError(f'Invalid {expected_phase} evidence')
    for key in ('captured_at_ms', 'capture_started_at_ms', 'battery_sampled_at_ms'):
        if type(value.get(key)) is not int or not 0 <= value[key] <= module.MAX_INT:
            raise ValueError('Invalid evidence timestamp')
    device = value.get('device')
    battery = value.get('battery')
    if (not isinstance(device, dict) or set(device) != {
            'manufacturer', 'model', 'android_release', 'android_sdk'}
            or any(not isinstance(item, str) or not item for item in device.values())
            or not isinstance(battery, dict) or set(battery) != {
                'level_percent', 'ac_powered', 'usb_powered', 'wireless_powered'}):
        raise ValueError('Invalid evidence metadata')
    if (battery['level_percent'] is not None and
            (type(battery['level_percent']) is not int or not 0 <= battery['level_percent'] <= 100)
            or any(item not in (None, 'true', 'false') for item in (
                battery['ac_powered'], battery['usb_powered'], battery['wireless_powered']))):
        raise ValueError('Invalid battery evidence')
    targets = value.get('targets')
    if (not isinstance(targets, dict) or set(targets) != {
            'schema_version', 'captured_at_ms', 'target_ids', 'observations'}
            or type(targets.get('schema_version')) is not int or targets.get('schema_version') != 1
            or type(targets.get('captured_at_ms')) is not int
            or not 0 <= targets['captured_at_ms'] <= module.MAX_INT
            or not isinstance(targets.get('target_ids'), list)
            or not isinstance(targets.get('observations'), list)):
        raise ValueError('Invalid target history evidence')
    if not value['capture_started_at_ms'] <= value['battery_sampled_at_ms'] <= value['captured_at_ms'] \
            or targets['captured_at_ms'] > value['captured_at_ms']:
        raise ValueError('Inconsistent evidence timestamps')
    if (any(not isinstance(target, str) or target not in module.IDS for target in targets['target_ids'])
            or targets['target_ids'] != sorted(set(targets['target_ids']))):
        raise ValueError('Invalid target history identities')
    rows_by_target = {}
    for row in targets['observations']:
        if not isinstance(row, dict) or set(row) != {
                'target_id', 'at_ms', 'until_ms', 'status', 'latency_ms',
                'clock_epoch', 'endpoint_revision'}:
            raise ValueError('Invalid target observation in evidence')
        target = row['target_id']
        if target not in module.IDS:
            raise ValueError('Invalid target observation identity')
        fields = ('at_ms', 'until_ms', 'status', 'latency_ms', 'clock_epoch', 'endpoint_revision')
        rows_by_target.setdefault(target, []).append(','.join(str(row[key]) for key in fields))
    for target, rows in rows_by_target.items():
        module.rows(('\n'.join(rows) + '\n').encode(), target)
    if sorted(rows_by_target) != targets['target_ids']:
        raise ValueError('Target history IDs do not match their observations')
    return value


def summarize(args):
    before = read_capture(args.before, 'before')
    after = read_capture(args.after, 'after')
    if before['device'] != after['device']:
        raise ValueError('Before/after device identity does not match')
    if before['targets']['target_ids'] != after['targets']['target_ids']:
        raise ValueError('Before/after target CSV sets do not match')
    target_elapsed = (after['targets']['captured_at_ms'] -
                      before['targets']['captured_at_ms'])
    battery_elapsed = after['battery_sampled_at_ms'] - before['battery_sampled_at_ms']
    if target_elapsed <= 0 or battery_elapsed <= 0 or not 1 <= args.max_gap_seconds <= 86400:
        raise ValueError('Invalid evidence duration or freshness limit')
    battery_before = before['battery']
    battery_after = after['battery']
    power_states = (
        battery_before.get('ac_powered'), battery_before.get('usb_powered'),
        battery_before.get('wireless_powered'), battery_after.get('ac_powered'),
        battery_after.get('usb_powered'), battery_after.get('wireless_powered'),
    )
    powered = any(value == 'true' for value in power_states)
    power_state_unknown = any(value is None for value in power_states)
    start_level, end_level = battery_before.get('level_percent'), battery_after.get('level_percent')
    delta = (start_level - end_level) if type(start_level) is int and type(end_level) is int else None
    daily = (delta * 86400000 / battery_elapsed
             if delta is not None and battery_elapsed >= 23 * 60 * 60 * 1000
             and not powered and not power_state_unknown else None)
    report = {
        'schema_version': 1,
        'device': after['device'],
        'window_hours': round(battery_elapsed / 3600000, 3),
        'battery_start_percent': start_level,
        'battery_end_percent': end_level,
        'battery_drop_percentage_points': delta,
        'battery_percentage_points_per_24h': round(daily, 2) if daily is not None else None,
        'battery_measurement_note': (
            'Daily rate withheld: device was powered at capture time.' if powered else
            'Daily rate withheld: power state was not confirmed at both captures.' if power_state_unknown else
            'A 24-hour rate is reported only for an unpowered window of at least 23 hours.'
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
    take.add_argument('--targets-csv', type=Path, action='append', required=True,
                      help='User-exported TgWatch target CSV; repeat once for each target')
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
