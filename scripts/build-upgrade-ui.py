#!/usr/bin/env python3
"""Build a tiny system-UIAutomator fixture; it never enters either application APK."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def build(destination, sdk, tools):
    platform = sdk / 'platforms/android-35'
    libraries = [platform / 'android.jar', platform / 'uiautomator.jar',
                 platform / 'optional/android.test.base.jar']
    d8 = sdk / 'build-tools' / tools / 'd8'
    if not all(path.is_file() for path in [*libraries, d8]):
        raise ValueError('Pinned Android35 and build tools are required')
    source = Path(__file__).with_name('android') / 'UpgradeUiSnapshot.java'
    with tempfile.TemporaryDirectory(prefix='tgwatch-upgrade-ui-') as directory:
        work = Path(directory)
        classes, dex = work / 'classes', work / 'dex'
        classes.mkdir()
        dex.mkdir()
        subprocess.run(['javac', '--release', '8', '-cp', os.pathsep.join(map(str, libraries)),
                        '-d', str(classes), str(source)], check=True, timeout=60)
        compiled = work / 'classes.jar'
        subprocess.run(['jar', 'cf', str(compiled), '-C', str(classes), '.'], check=True, timeout=30)
        command = [str(d8), '--min-api', '26', '--output', str(dex)]
        for library in libraries:
            command.extend(['--lib', str(library)])
        subprocess.run([*command, str(compiled)], check=True, timeout=60)
        with zipfile.ZipFile(destination, 'w') as archive:
            archive.write(dex / 'classes.dex', 'classes.dex')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
        build(args.out, sdk, os.environ.get('BUILD_TOOLS', '36.0.0'))
        print('Disposable UI snapshot fixture built')
        return 0
    except (OSError, KeyError, ValueError, subprocess.SubprocessError) as error:
        print('UI snapshot fixture build failed: ' + type(error).__name__)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
