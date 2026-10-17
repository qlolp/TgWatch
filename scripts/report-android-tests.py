#!/usr/bin/env python3
"""Summarize connected JUnit XML without printing failure bodies or messages.

This always-successful diagnostic step never replaces the emulator/test step's
exit status. Only bounded identifiers and the test's own source location become
GitHub annotations.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 20
MAX_REPORT_FILES = 100
MAX_REPORT_BYTES = 5 * 1024 * 1024
MAX_TOTAL_BYTES = 20 * 1024 * 1024
DEFAULT_REPORTS = 'app/build/outputs/androidTest-results/connected'
IDENTIFIER = re.compile(r'[A-Za-z_$][A-Za-z0-9_.$]{0,159}\Z')


class ReportUnavailable(Exception):
    pass


def workflow_escape(value):
    return value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def safe_identifier(value, fallback):
    # Do not emit arbitrary parameter values, command text or exception messages.
    return value if value and IDENTIFIER.fullmatch(value) else fallback


def case_source_location(case, exception):
    """Expose only the failing test's own source filename/line, never raw text."""
    name = case.get('classname', '')
    if not name.startswith('ru.tgwatch.') or not IDENTIFIER.fullmatch(name):
        return ''
    source = name.rsplit('.', 1)[-1].split('$', 1)[0]
    frame = re.compile(r'^\s*at ' + re.escape(name) + r'\.[A-Za-z_$][A-Za-z0-9_$]*\('
                       + re.escape(source) + r'\.(kt|java):([1-9][0-9]{0,5})\)\s*$', re.M)
    match = frame.search(exception.text or '')
    return f' [{source}.{match[1]}:{match[2]}]' if match else ''


def case_outcome(case):
    outcomes = [kind for kind in ('failure', 'error', 'skipped') if case.find(kind) is not None]
    if len(outcomes) > 1:
        raise ReportUnavailable('ambiguous testcase outcome')
    return outcomes[0] if outcomes else 'passed'


def counts(cases):
    result = {'tests': len(cases), 'passed': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    names = {'passed': 'passed', 'failure': 'failures', 'error': 'errors', 'skipped': 'skipped'}
    for case in cases:
        result[names[case_outcome(case)]] += 1
    return result


def parse_report(data):
    if b'<!DOCTYPE' in data or b'<!ENTITY' in data:
        raise ReportUnavailable('XML declarations are unsupported')
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        raise ReportUnavailable('malformed XML') from None
    if root.tag not in ('testsuite', 'testsuites'):
        raise ReportUnavailable('unexpected XML root')
    for suite in root.iter('testsuite'):
        actual = counts(list(suite.iter('testcase')))
        for name in ('tests', 'failures', 'errors', 'skipped'):
            declared = suite.get(name)
            if declared is not None and (not declared.isdecimal() or int(declared) != actual[name]):
                raise ReportUnavailable('testcase counts disagree with XML summary')
    return list(root.iter('testcase'))


def report(directory, output=sys.stdout):
    try:
        files = sorted(Path(directory).rglob('TEST-*.xml'))
        if not files:
            raise ReportUnavailable('no connected XML reports')
        if len(files) > MAX_REPORT_FILES:
            raise ReportUnavailable('report file limit exceeded')
        cases = []
        total_bytes = 0
        for path in files:
            if path.stat().st_size > MAX_REPORT_BYTES:
                raise ReportUnavailable('report size limit exceeded')
            data = path.read_bytes()
            total_bytes += len(data)
            if len(data) > MAX_REPORT_BYTES or total_bytes > MAX_TOTAL_BYTES:
                raise ReportUnavailable('report size limit exceeded')
            cases.extend(parse_report(data))
        if not cases:
            raise ReportUnavailable('no testcase results')
        result = counts(cases)
        print('Connected Android tests: ' + ' '.join(f'{key}={value}' for key, value in result.items()), file=output)
        annotations = 0
        for case in cases:
            outcome = case_outcome(case)
            if outcome not in ('failure', 'error') or annotations >= MAX_ANNOTATIONS:
                continue
            exception = case.find(outcome)
            label = safe_identifier(case.get('classname'), 'unknown-class') + '.' + safe_identifier(case.get('name'), 'unknown-test')
            kind = safe_identifier(exception.get('type'), 'unknown-exception')
            message = workflow_escape(f'{label} ({kind})' + case_source_location(case, exception))
            print(f'::error title=Connected Android test {outcome}::{message}', file=output)
            annotations += 1
        if result['failures'] + result['errors'] > MAX_ANNOTATIONS:
            print('::warning::Android test annotation limit reached; remaining cases are in the XML artifacts.', file=output)
    except ReportUnavailable as error:
        print(f'::warning::Connected Android tests unavailable: {workflow_escape(str(error))}.', file=output)
    except Exception:
        # Never echo filesystem/parser exceptions: paths/messages can contain data.
        print('::warning::Connected Android tests unavailable: reporting could not complete.', file=output)
    return 0


def main(arguments=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    if len(arguments) > 1:
        print('::warning::Connected Android tests unavailable: expected one report directory.')
        return 0
    return report(arguments[0] if arguments else DEFAULT_REPORTS)


if __name__ == '__main__':
    sys.exit(main())
