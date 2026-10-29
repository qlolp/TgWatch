"""Exercise the readiness CLI through a real, disposable fake adb process."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "adb-readiness.py"
FAKE = r'''#!/usr/bin/env python3
import json, os, sys, time
from pathlib import Path
p=Path(os.environ['TGWATCH_TEST_ADB_STATE'])
s=json.loads(p.read_text())
raw=sys.argv[1:]
s['calls'].append(raw)
a=raw
if a[:1]==['-s']:
    if len(a)<3 or a[1]!='fixture-29': sys.exit(7)
    a=a[2:]
elif s.get('serial_required'): sys.exit(7)
n=sum((x[2:] if x[:1]==['-s'] else x)==['root'] for x in s['calls'])
# Timeout kills can interrupt the fixture itself; keep the previous journal readable.
pending=p.with_name(p.name + '.' + str(os.getpid()) + '.tmp')
pending.write_text(json.dumps(s))
os.replace(pending,p)
mode=s['mode']
if a==['root']:
    if mode=='denied': print('adbd cannot run as root in production builds',file=sys.stderr);sys.exit(1)
    if mode=='unknown': print('unexpected failure',file=sys.stderr);sys.exit(1)
    if mode=='unknown_closed': print('filesystem resource is closed',file=sys.stderr);sys.exit(1)
    if mode=='hang': time.sleep(10)
    if mode=='unauthorized' and n==1 or mode=='always_unauthorized':
        print('error: device unauthorized.',file=sys.stderr);sys.exit(1)
    if mode=='device_not_found' and n==1:
        print("adb: device 'fixture-29' not found",file=sys.stderr);sys.exit(1)
    if mode=='closed' and n==1 or mode=='always_closed':
        print('adb: unable to connect for root: closed',file=sys.stderr);sys.exit(1)
    print('restarting adbd as root')
elif a==['wait-for-device']:
    if mode=='always_closed': time.sleep(10)
elif a==['shell','id','-u']:
    if mode=='missing_shell': print('id: not found',file=sys.stderr);sys.exit(127)
    print('2000' if mode=='late_uid' and n==1 else '0')
elif a==['shell','getprop','sys.boot_completed']:
    print('0' if mode=='late_boot' and n==1 else '1')
elif a==['shell','cat','/proc/sys/kernel/random/boot_id']:
    reads=sum((x[2:] if x[:1]==['-s'] else x)==a for x in s['calls'])
    if mode=='unstable_boot_id': print('first-new-boot' if reads==1 else 'second-new-boot')
    else: print('' if mode=='empty_boot_id' else 'new-boot' if mode=='boot_id_changes' and n>1 else 'old-boot')
else: sys.exit(8)
'''


class AdbReadinessTests(unittest.TestCase):
    def run_fixture(self, mode, *, timeout=3, command_timeout=.4, serial=False, after_boot=None):
        with tempfile.TemporaryDirectory(prefix="tgwatch-adb-") as temp:
            root = Path(temp)
            state = root / "state.json"
            state.write_text(json.dumps({"mode": mode, "calls": [], "serial_required": serial}))
            adb = root / "adb"
            adb.write_text(FAKE)
            adb.chmod(0o700)
            args = [sys.executable, str(SCRIPT), "--adb", str(adb),
                    "--timeout", str(timeout), "--command-timeout", str(command_timeout),
                    "--interval", ".02"]
            if serial:
                args.extend(["--serial", "fixture-29"])
            if after_boot is not None:
                args.extend(["--after-boot-id", after_boot])
            env = dict(os.environ, TGWATCH_TEST_ADB_STATE=str(state))
            env.pop("ANDROID_SERIAL", None)
            started = time.monotonic()
            result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=6)
            return result, json.loads(state.read_text())["calls"], time.monotonic() - started

    def test_closed_root_reconnects_and_verifies_root_and_boot(self):
        result, calls, _ = self.run_fixture("closed")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(calls.count(["root"]), 2)
        self.assertIn(["wait-for-device"], calls)
        self.assertIn(["shell", "id", "-u"], calls)
        self.assertIn(["shell", "getprop", "sys.boot_completed"], calls)

    def test_selected_device_not_found_reconnects(self):
        result, calls, _ = self.run_fixture("device_not_found", serial=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(calls.count(["-s", "fixture-29", "root"]), 2)

    def test_zero_root_exit_does_not_replace_actual_uid_verification(self):
        result, calls, _ = self.run_fixture("late_uid")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(calls.count(["shell", "id", "-u"]), 2)

    def test_root_uid_does_not_replace_boot_readiness(self):
        result, calls, _ = self.run_fixture("late_boot")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(calls.count(["shell", "getprop", "sys.boot_completed"]), 2)

    def test_reboot_requires_a_different_boot_even_when_old_system_is_ready(self):
        result, calls, _ = self.run_fixture('boot_id_changes', after_boot='old-boot')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(calls.count(['root']), 2)
        self.assertGreaterEqual(calls.count(['shell', 'cat', '/proc/sys/kernel/random/boot_id']), 2)

    def test_same_ready_boot_is_rejected_through_the_real_cli(self):
        result, _, _ = self.run_fixture('ready', after_boot='old-boot', timeout=2)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('ADB readiness failed', result.stderr)

    def test_root_and_completion_checks_must_belong_to_the_same_new_boot(self):
        result, calls, _ = self.run_fixture('unstable_boot_id', after_boot='old-boot')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(calls.count(['root']), 2)

    def test_empty_requested_old_boot_is_rejected_before_adb(self):
        for identity in ('', '   '):
            result, calls, _ = self.run_fixture('ready', after_boot=identity)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], calls)

    def test_permanent_root_denial_fails_without_retry(self):
        result, calls, elapsed = self.run_fixture("denied")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, calls.count(["root"]))
        self.assertLess(elapsed, 2)

    def test_transient_unauthorized_device_is_retried(self):
        result, calls, _ = self.run_fixture("unauthorized")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(calls.count(["root"]), 2)

    def test_lasting_unauthorized_device_fails_at_the_deadline_with_its_cause(self):
        # Allow two real Python subprocess starts even on a loaded developer Mac.
        # The cause, retry count and two-second overall bound remain strict.
        result, calls, elapsed = self.run_fixture("always_unauthorized", timeout=1, command_timeout=.4)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unauthorized", result.stderr)
        self.assertGreaterEqual(calls.count(["root"]), 2)
        self.assertLess(elapsed, 2)

    def test_unknown_failure_is_not_hidden_by_retries(self):
        result, calls, _ = self.run_fixture("unknown")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, calls.count(["root"]))

    def test_unrelated_closed_resource_is_not_a_reconnect_error(self):
        result, calls, _ = self.run_fixture("unknown_closed")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, calls.count(["root"]))

    def test_missing_shell_command_fails_without_reconnect_retry(self):
        result, calls, _ = self.run_fixture("missing_shell")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, calls.count(["root"]))
        self.assertEqual(1, calls.count(["shell", "id", "-u"]))

    def test_disconnect_and_wait_cannot_outlive_overall_deadline(self):
        result, _, elapsed = self.run_fixture("always_closed", timeout=.3, command_timeout=.15)
        self.assertNotEqual(0, result.returncode)
        self.assertLess(elapsed, 2)

    def test_hanging_command_is_killed_within_deadline(self):
        result, _, elapsed = self.run_fixture("hang", timeout=.3, command_timeout=.1)
        self.assertNotEqual(0, result.returncode)
        self.assertLess(elapsed, 2)

    def test_selected_device_is_preserved_for_every_command(self):
        result, calls, _ = self.run_fixture("closed", serial=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(calls)
        self.assertTrue(all(call[:2] == ["-s", "fixture-29"] for call in calls))

    def test_invalid_deadline_never_invokes_adb(self):
        for timeout in (0, -1, "nan", "inf"):
            result, calls, _ = self.run_fixture("ready", timeout=timeout)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], calls)


class ControlledReadinessTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("adb_readiness", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.elapsed = 0.0
        self.calls = []
        self.boots = ["new-boot"]
        self.command_cost = .05
        self.hang = False
        self.exhaust_on = None

    def pause(self, seconds):
        self.elapsed += seconds

    def runner(self, command, *, capture_output, text, timeout):
        self.calls.append((command, timeout, self.elapsed))
        if self.hang or self.command_cost > timeout:
            self.elapsed += timeout
            raise subprocess.TimeoutExpired(command, timeout)
        self.elapsed += self.command_cost
        args = command[1:]
        if args == self.exhaust_on:
            self.elapsed = 1.0
        if args == ["shell", "cat", "/proc/sys/kernel/random/boot_id"]:
            output = self.boots.pop(0) if len(self.boots) > 1 else self.boots[0]
        elif args == ["shell", "id", "-u"]:
            output = "0"
        elif args == ["shell", "getprop", "sys.boot_completed"]:
            output = "1"
        else:
            output = ""
        return subprocess.CompletedProcess(command, 0, output, "")

    def ready(self, *, after_boot="old-boot", timeout=1):
        return self.module.ensure_ready(["adb"], timeout, .2, .1, after_boot,
            clock=lambda: self.elapsed, pause=self.pause, runner=self.runner)

    def test_old_and_empty_boot_cannot_satisfy_reboot(self):
        for identity in ("old-boot", ""):
            with self.subTest(identity=identity):
                self.setUp()
                self.boots = [identity]
                with self.assertRaises(self.module.ReadinessError):
                    self.ready()
                self.assertTrue(any("boot_id" in command[-1] for command, _, _ in self.calls))
                self.assertFalse(any(command[-2:] == ["id", "-u"] for command, _, _ in self.calls))
                self.assertAlmostEqual(1, self.elapsed)

    def test_new_boot_must_be_stable_across_uid_and_completion(self):
        self.boots = ["first-new", "second-new", "second-new", "second-new"]
        self.ready()
        self.assertEqual(2, sum(command == ["adb", "root"] for command, _, _ in self.calls))
        self.assertEqual(4, sum(command[-1].endswith("boot_id") for command, _, _ in self.calls))

    def test_old_boot_may_transition_to_new_boot_before_deadline(self):
        self.boots = ["old-boot", "new-boot", "new-boot"]
        self.ready()
        self.assertEqual(2, sum(command == ["adb", "root"] for command, _, _ in self.calls))

    def test_hanging_commands_share_overall_budget_and_per_command_cap(self):
        self.hang = True
        with self.assertRaises(self.module.ReadinessError):
            self.ready(timeout=.45)
        self.assertAlmostEqual(.45, self.elapsed)
        self.assertTrue(self.calls)
        for _, budget, started in self.calls:
            self.assertLessEqual(budget, .2)
            self.assertLessEqual(started + budget, .45 + 1e-9)

    def test_final_success_at_expired_deadline_is_rejected(self):
        self.exhaust_on = ["shell", "getprop", "sys.boot_completed"]
        with self.assertRaises(self.module.ReadinessError):
            self.ready(after_boot=None)
        self.assertAlmostEqual(1, self.elapsed)


if __name__ == "__main__":
    unittest.main()
