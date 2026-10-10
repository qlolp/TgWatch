#!/usr/bin/env python3
"""Audit exact resolved Maven coordinates against OSV; errors never mean clean."""
import concurrent.futures
import datetime
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request


def query_osv(query):
    request = urllib.request.Request('https://api.osv.dev/v1/query',
        data=json.dumps(query).encode(), headers={'Content-Type': 'application/json'}, method='POST')
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def audit(packages, query=query_osv):
    if not packages:
        raise ValueError('No resolved dependencies: refusing an empty audit')
    def check(package):
        group, name, version = (package.get(key) for key in ('group', 'name', 'version'))
        if not all(isinstance(value, str) and value for value in (group, name, version)):
            raise ValueError('Invalid resolved coordinate')
        request = {'package': {'ecosystem': 'Maven', 'name': f'{group}:{name}'}, 'version': version}
        vulnerabilities = set()
        tokens = set()
        for page in range(100):
            response = query(request)
            if not isinstance(response, dict) or 'error' in response:
                raise ValueError('Invalid OSV response')
            records = response.get('vulns', [])
            if not isinstance(records, list):
                raise ValueError('Incomplete OSV response')
            for record in records:
                if not isinstance(record, dict) or not isinstance(record.get('id'), str) or not record['id']:
                    raise ValueError('OSV record missing its ID')
                if not record.get('withdrawn'):
                    vulnerabilities.add(record['id'])
            token = response.get('next_page_token')
            if not token:
                return {**package, 'vulnerabilities': sorted(vulnerabilities)}
            if not isinstance(token, str) or token in tokens:
                raise ValueError('Invalid OSV pagination')
            tokens.add(token)
            request = {**request, 'page_token': token}
        raise ValueError('OSV pagination limit exceeded')
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        return list(pool.map(check, packages))


def main():
    source, destination = map(pathlib.Path, sys.argv[1:3])
    try:
        inventory = json.loads(source.read_text())
        packages = inventory['packages']
        if not any('releaseRuntimeClasspath' in p.get('scopes', []) for p in packages):
            raise ValueError('Release runtime graph missing')
        report = {'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'source': 'https://api.osv.dev/v1/query', 'packages': audit(packages)}
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        findings = [(p['group'] + ':' + p['name'] + ':' + p['version'], p['vulnerabilities'])
            for p in report['packages'] if p['vulnerabilities']]
        for coordinate, ids in findings:
            print(f'{coordinate}: {", ".join(ids)}')
        print(f'Checked {len(packages)} exact resolved Maven versions; findings: {len(findings)}')
        return 1 if findings else 0
    except Exception as error:
        destination.write_text(json.dumps({'error': str(error), 'status': 'audit_failed'}, indent=2) + '\n')
        print(f'Dependency audit failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
