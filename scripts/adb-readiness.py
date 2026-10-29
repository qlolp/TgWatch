#!/usr/bin/env python3
"""Verify root/boot readiness and optionally require a stable new kernel boot."""
import argparse
import math
import os
import re
import subprocess
import sys
import time


class ReadinessError(Exception):
    pass


def positive_number(text):
    value = float(text)
    if not math.isfinite(value) or value <= 0:
        raise argparse.ArgumentTypeError("must be a finite positive number")
    return value


def ensure_ready(command, timeout, command_timeout, interval, after_boot_id=None, *,
                 clock=time.monotonic, pause=time.sleep, runner=subprocess.run):
    deadline = clock() + timeout
    reconnect_errors = (
        re.compile(r"\b(?:adb:\s*)?unable to connect\b"),
        re.compile(r"\bno devices(?:/emulators)? found\b"),
        re.compile(r"\bdevice(?:\s+'[^']+'|\s+\"[^\"]+\")?\s+offline\b"),
        re.compile(r"\bconnection reset\b"),
        re.compile(r"\bcannot connect to (?:the )?(?:adb )?(?:daemon|server|device)\b"),
        re.compile(r"\bprotocol fault\b"),
        re.compile(r"\btransport error\b"),
        re.compile(r"\b(?:adb|error): closed\b"),
    )
    device_not_found = re.compile(r"\bdevice\s+(?:'[^']+'|\"[^\"]+\"|[^\s:]+)\s+not found\b")
    refused_errors = ("cannot run as root", "permission denied")
    # A freshly booted emulator can report "unauthorized" until adbd loads the host key.
    unauthorized = re.compile(r"\bunauthorized\b")
    seen = {"unauthorized": False}

    def call(arguments):
        remaining = deadline - clock()
        if remaining <= 0:
            return None
        try:
            result = runner(command + arguments, capture_output=True, text=True,
                                    timeout=min(command_timeout, remaining))
        except subprocess.TimeoutExpired:
            return None
        except OSError as error:
            raise ReadinessError("ADB executable could not be started") from error
        if clock() >= deadline:
            return None
        message = (result.stdout + result.stderr).lower()
        if any(value in message for value in refused_errors):
            raise ReadinessError("ADB refused root access")
        if result.returncode and unauthorized.search(message):
            seen["unauthorized"] = True
            return result
        if result.returncode and not (any(pattern.search(message) for pattern in reconnect_errors) or
                                      device_not_found.search(message)):
            raise ReadinessError(f"ADB command failed without a reconnect error (exit {result.returncode})")
        return result

    while clock() < deadline:
        rooted = call(["root"])
        # root restarts adbd; even its successful exit is not device readiness.
        connected = call(["wait-for-device"])
        if rooted is not None and rooted.returncode == 0 and connected is not None and connected.returncode == 0:
            current_boot = None
            if after_boot_id is not None:
                observed = call(["shell", "cat", "/proc/sys/kernel/random/boot_id"])
                if observed is not None and observed.returncode == 0:
                    current_boot = observed.stdout.strip()
            fresh = after_boot_id is None or bool(current_boot and current_boot != after_boot_id)
            if fresh:
                identity = call(["shell", "id", "-u"])
                if identity is not None and identity.returncode == 0 and identity.stdout.strip() == "0":
                    boot = call(["shell", "getprop", "sys.boot_completed"])
                    if boot is not None and boot.returncode == 0 and boot.stdout.strip() == "1":
                        if after_boot_id is None:
                            return
                        # UID and completion must belong to the same new boot, not cross a reboot.
                        confirmed = call(["shell", "cat", "/proc/sys/kernel/random/boot_id"])
                        if confirmed is not None and confirmed.returncode == 0 and confirmed.stdout.strip() == current_boot:
                            return
        remaining = deadline - clock()
        if remaining > 0:
            pause(min(interval, remaining))
    if seen["unauthorized"]:
        raise ReadinessError("ADB device stayed unauthorized until the deadline")
    raise ReadinessError("ADB root and boot readiness did not complete within the deadline")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--timeout", type=positive_number, default=45)
    parser.add_argument("--command-timeout", type=positive_number, default=5)
    parser.add_argument("--interval", type=positive_number, default=1)
    parser.add_argument("--after-boot-id", help="Require a different, stable kernel boot after reboot")
    args = parser.parse_args()
    if args.after_boot_id is not None:
        args.after_boot_id = args.after_boot_id.strip()
        if not args.after_boot_id:
            parser.error("--after-boot-id must not be empty")
    command = [args.adb] + (["-s", args.serial] if args.serial else [])
    try:
        ensure_ready(command, args.timeout, args.command_timeout, args.interval, args.after_boot_id)
        print("ADB is connected, running as root, and booted")
        if args.after_boot_id is not None and os.environ.get("GITHUB_ACTIONS") == "true":
            print("::notice title=Fresh Android reboot::Root and completion verified in one new kernel boot")
        return 0
    except ReadinessError as error:
        print(f"ADB readiness failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
