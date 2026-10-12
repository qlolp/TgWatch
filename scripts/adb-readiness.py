#!/usr/bin/env python3
"""Verify root and boot readiness, retrying bounded ADB transport reconnections."""
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


def ensure_ready(command, timeout, command_timeout, interval):
    deadline = time.monotonic() + timeout
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
    refused_errors = ("cannot run as root", "permission denied", "unauthorized")

    def call(arguments):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        try:
            result = subprocess.run(command + arguments, capture_output=True, text=True,
                                    timeout=min(command_timeout, remaining))
        except subprocess.TimeoutExpired:
            return None
        except OSError as error:
            raise ReadinessError("ADB executable could not be started") from error
        message = (result.stdout + result.stderr).lower()
        if any(value in message for value in refused_errors):
            raise ReadinessError("ADB refused root access or device authorization")
        if result.returncode and not (any(pattern.search(message) for pattern in reconnect_errors) or
                                      device_not_found.search(message)):
            raise ReadinessError(f"ADB command failed without a reconnect error (exit {result.returncode})")
        return result

    while time.monotonic() < deadline:
        rooted = call(["root"])
        # root restarts adbd; even its successful exit is not device readiness.
        connected = call(["wait-for-device"])
        if rooted is not None and rooted.returncode == 0 and connected is not None and connected.returncode == 0:
            identity = call(["shell", "id", "-u"])
            if identity is not None and identity.returncode == 0 and identity.stdout.strip() == "0":
                boot = call(["shell", "getprop", "sys.boot_completed"])
                if boot is not None and boot.returncode == 0 and boot.stdout.strip() == "1":
                    return
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(interval, remaining))
    raise ReadinessError("ADB root and boot readiness did not complete within the deadline")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--timeout", type=positive_number, default=45)
    parser.add_argument("--command-timeout", type=positive_number, default=5)
    parser.add_argument("--interval", type=positive_number, default=1)
    args = parser.parse_args()
    command = [args.adb] + (["-s", args.serial] if args.serial else [])
    try:
        ensure_ready(command, args.timeout, args.command_timeout, args.interval)
        print("ADB is connected, running as root, and booted")
        return 0
    except ReadinessError as error:
        print(f"ADB readiness failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
