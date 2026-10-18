"""Exercise emulator preparation through a real fake-adb subprocess and durable state."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'prepare-direct-boot.py'
FAKE = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p=Path(os.environ['TGWATCH_FBE_STATE'])
s=json.loads(p.read_text())
a=sys.argv[1:]
s['calls'].append(a)
code=0
out=''
err=''
if a==['shell','getprop','ro.kernel.qemu']: out=s['qemu']
elif a==['shell','getprop','ro.crypto.type']: out='file' if s['mode']=='native' else 'block'
elif a==['root']: out='adbd is already running as root'
elif a==['wait-for-device']: pass
elif a==['shell','id','-u']: out='0'
elif a==['shell','getprop','sys.boot_completed']: out='1'
elif a==['shell','sm','get-fbe-mode']: out=s['mode']
elif a==['shell','sm','set-emulate-fbe','true']:
    if s.get('denied'): code=1;err='Permission denied'
    else:
        s['pending']=not s.get('no_reboot',False)
        if s.get('dead_object'): code=1;err='Error: android.os.DeadObjectException'
elif a==['shell','cat','/proc/sys/kernel/random/boot_id']:
    if s.get('pending'):
        s['pending']=False
        s['boot']='new-boot'
        if not s.get('lost_setting'): s['mode']='emulated'
        code=1;err='error: device offline'
    else: out=s['boot']
else: code=7;err='Unexpected fixture command'
p.write_text(json.dumps(s))
if out: print(out)
if err: print(err,file=sys.stderr)
sys.exit(code)
'''


class DirectBootPreparationTests(unittest.TestCase):
    def run_fixture(self, mode, *, disposable=False, timeout=3, **values):
        with tempfile.TemporaryDirectory(prefix='tgwatch-fbe-') as temp:
            root = Path(temp)
            state = root / 'state.json'
            initial = dict(mode=mode, boot='old-boot', qemu='1', calls=[])
            initial.update(values)
            state.write_text(json.dumps(initial))
            adb = root / 'adb'
            adb.write_text(FAKE)
            adb.chmod(0o700)
            args = [sys.executable, str(SCRIPT), '--adb', str(adb), '--timeout', str(timeout),
                    '--command-timeout', '.5', '--interval', '.01']
            if disposable: args.append('--disposable')
            result = subprocess.run(args, capture_output=True, text=True,
                                    env=dict(os.environ, TGWATCH_FBE_STATE=str(state)), timeout=timeout + 5)
            return result, json.loads(state.read_text())

    @staticmethod
    def changed(state):
        return any(a[:3] == ['shell', 'sm', 'set-emulate-fbe'] for a in state['calls'])

    def test_native_fbe_is_reused_without_reboot_or_settings_change(self):
        result, state = self.run_fixture('native')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('old-boot', state['boot'])
        self.assertFalse(self.changed(state))

    def test_existing_emulation_is_reused_without_reboot(self):
        result, state = self.run_fixture('emulated')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('old-boot', state['boot'])
        self.assertFalse(self.changed(state))

    def test_fde_requires_explicit_disposable_opt_in_before_mutation(self):
        result, state = self.run_fixture('none')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('disposable', result.stderr)
        self.assertFalse(self.changed(state))

    def test_fde_emulation_requires_a_fresh_boot_and_verified_mode(self):
        result, state = self.run_fixture('none', disposable=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('new-boot', state['boot'])
        self.assertEqual('emulated', state['mode'])

    def test_dead_object_from_the_rebooting_setter_is_verified_after_reboot(self):
        result, state = self.run_fixture('none', disposable=True, dead_object=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('new-boot', state['boot'])
        self.assertEqual('emulated', state['mode'])

    def test_physical_devices_are_refused_before_root_or_mutation(self):
        result, state = self.run_fixture('none', disposable=True, qemu='0')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([['shell', 'getprop', 'ro.kernel.qemu']], state['calls'])
        self.assertFalse(self.changed(state))

    def test_unknown_modes_are_not_treated_as_fbe(self):
        result, state = self.run_fixture('unexpected', disposable=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.changed(state))

    def test_permission_denial_does_not_report_success_or_wait_for_a_reboot(self):
        result, state = self.run_fixture('none', disposable=True, denied=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('old-boot', state['boot'])
        self.assertEqual('none', state['mode'])

    def test_unchanged_boot_cannot_satisfy_preparation(self):
        result, state = self.run_fixture('none', disposable=True, no_reboot=True, timeout=.8)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('old-boot', state['boot'])

    def test_lost_setting_after_reboot_cannot_satisfy_preparation(self):
        result, state = self.run_fixture('none', disposable=True, lost_setting=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('new-boot', state['boot'])
        self.assertEqual('none', state['mode'])


if __name__ == '__main__':
    unittest.main()
