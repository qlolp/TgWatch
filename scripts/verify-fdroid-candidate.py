#!/usr/bin/env python3
"""Validate an existing release tag in isolated F-Droid staging, with normal scanning."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request

import yaml

from apk_verification import PRODUCTION_CERTIFICATE as CERTIFICATE, verify_certificate_report

ROOT = Path(__file__).resolve().parents[1]
CATEGORIES_URL = ('https://gitlab.com/fdroid/fdroiddata/-/raw/'
                  '5d2099db6488d9e2c5f74a9882e32138f5760616/config/categories.yml')
CATEGORIES_SHA = 'edf68d8d70073fe52a52c6a33f490399ce9dc884acff3a761a3a1e34acf9d541'


def run(arguments, *, cwd, timeout=1800):
    subprocess.run(arguments, cwd=cwd, check=True, timeout=timeout)


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def verify(tag, output):
    if not re.fullmatch(r'v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})', tag):
        raise ValueError('Expected a canonical existing release tag')
    output.mkdir(parents=True, exist_ok=False)
    report = {'tag': tag, 'catalogSubmitted': False, 'catalogAccepted': False, 'checks': {}}
    try:
        revision = subprocess.check_output(['git', 'rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}'], cwd=ROOT, text=True).strip()
        report['sourceRevision'] = revision
        with tempfile.TemporaryDirectory(prefix='tgwatch-fdroid-') as temp:
            stage = Path(temp)
            independent = stage / 'independent'
            run(['git', 'clone', '--no-local', str(ROOT), str(independent)], cwd=stage)
            run(['git', 'checkout', '--detach', revision], cwd=independent)
            version = (independent / 'version.properties').read_text().strip()
            if version != 'version=' + tag[1:]:
                raise ValueError('Tag and source version differ')
            parts = [int(x) for x in tag[1:].split('.')]
            code = parts[0] * 1_000_000 + parts[1] * 1000 + parts[2]
            metadata = yaml.safe_load((independent / 'metadata/ru.tgwatch.yml').read_text())
            build = next(x for x in metadata['Builds'] if x['versionCode'] == code)
            if build['commit'] != tag or build['versionName'] != tag[1:] or not build.get('disable'):
                raise ValueError('Expected matching disabled upstream recipe')
            build.pop('disable')  # Only the owned temporary candidate is enabled.
            run(['bash', './gradlew', ':app:assembleRelease', '--no-daemon'], cwd=independent)
            unsigned = output / 'independent-unsigned.apk'
            shutil.copy2(independent / 'app/build/outputs/apk/release/app-release-unsigned.apk', unsigned)
            report['checks']['independentBuild'] = {'status': 'passed', 'sha256': sha256(unsigned)}
            candidates = list((Path(os.environ['GRADLE_USER_HOME']) / 'wrapper/dists/gradle-9.4.1-bin').glob('*/gradle-9.4.1/bin/gradle'))
            if len(candidates) != 1:
                raise ValueError('Expected exactly one verified Gradle9.4.1 distribution')
            fdroid = stage / 'fdroid'
            (fdroid / 'metadata').mkdir(parents=True)
            (fdroid / 'config').mkdir()
            (fdroid / 'metadata/ru.tgwatch.yml').write_text(yaml.safe_dump(metadata, sort_keys=False))
            with urllib.request.urlopen(CATEGORIES_URL, timeout=30) as response:
                categories = response.read()
            if hashlib.sha256(categories).hexdigest() != CATEGORIES_SHA:
                raise ValueError('Official category definition checksum mismatch')
            (fdroid / 'config/categories.yml').write_bytes(categories)
            for entry in yaml.safe_load(categories).values():
                icon = entry.get('icon')
                if icon is None:
                    continue
                if not re.fullmatch(r'category_[a-z_]+\.png', icon):
                    raise ValueError('Unexpected official category icon path')
                with urllib.request.urlopen(CATEGORIES_URL.rsplit('/', 1)[0] + '/' + icon, timeout=30) as response:
                    (fdroid / 'config' / icon).write_bytes(response.read())
            config = {'sdk_path': os.environ['ANDROID_HOME'], 'gradle': str(candidates[0]),
                      'java_paths': {'17': os.environ['JAVA_HOME']}}
            (fdroid / 'config.yml').write_text(yaml.safe_dump(config))
            for operation in ('readmeta', 'lint'):
                run(['fdroid', operation] + (['ru.tgwatch'] if operation == 'lint' else []), cwd=fdroid)
                report['checks'][operation] = {'status': 'passed'}
            # fdroidserver reads SOURCE_DATE_EPOCH before its normal checkout; use its supported VCS API.
            run([os.sys.executable, '-c',
                 'from fdroidserver import common; common.read_config(); '
                 'common.getvcs("git", "https://github.com/qlolp/TgWatch.git", '
                 '"build/ru.tgwatch").gotorevision(' + repr(tag) + ')'], cwd=fdroid)
            run(['fdroid', 'build', '--test', '--stop', f'ru.tgwatch:{code}'], cwd=fdroid)
            recipe_apk = output / 'recipe-unsigned.apk'
            shutil.copy2(fdroid / f'tmp/ru.tgwatch_{code}.apk', recipe_apk)
            report['checks']['normalRecipeBuild'] = {'status': 'passed', 'scannerBypassed': False,
                                                     'sha256': sha256(recipe_apk)}
            if sha256(unsigned) != sha256(recipe_apk):
                raise ValueError('Independent and recipe APK bytes differ')
            report['checks']['unsignedComparison'] = {'status': 'passed'}
            run(['gh', 'release', 'download', tag, '--repo', 'qlolp/TgWatch', '--dir', str(output),
                 '--pattern', 'TgWatch.apk', '--pattern', 'TgWatch.apk.sha256'], cwd=stage)
            published = output / 'TgWatch.apk'
            checksum = (output / 'TgWatch.apk.sha256').read_text().split()
            if len(checksum) != 2 or checksum[1] != 'TgWatch.apk' or checksum[0] != sha256(published):
                raise ValueError('Published APK checksum mismatch')
            signer = subprocess.check_output(['apksigner', 'verify', '--print-certs', str(published)], text=True)
            verify_certificate_report(signer)
            for apk in (unsigned, recipe_apk):
                run(['apksigcopier', 'compare', '--unsigned', str(published), str(apk)], cwd=stage)
            report['checks']['productionPayloadComparison'] = {'status': 'passed', 'publishedSha256': sha256(published)}
            report['status'] = 'passed'
    except Exception as error:
        report['status'] = 'failed'
        report['failureType'] = type(error).__name__
        raise
    finally:
        (output / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    verify(args.tag, args.out.resolve())


if __name__ == '__main__':
    main()
