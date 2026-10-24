#!/usr/bin/env python3
"""Fail when the detekt baseline grows: legacy findings may only be removed, never added."""
import argparse
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def baseline_ids(path):
    """A fixed baseline may be empty; malformed or duplicate IDs are not valid debt."""
    return parse_ids(ET.parse(path).getroot())


def parse_ids(root):
    if root.tag != "SmellBaseline":
        raise ValueError("unexpected baseline root")
    ids = [element.text for element in root.iter("ID")]
    if any(not value or not value.strip() for value in ids):
        raise ValueError("baseline contains an empty finding ID")
    if len(ids) != len(set(ids)):
        raise ValueError("baseline contains duplicate finding IDs")
    return ids


def reference_ids(args):
    if args.reference:
        return baseline_ids(args.reference)
    # GitHub uses an all-zero before SHA for a new branch/tag. Its first parent is
    # the only previous tree then; PR runs always supply the explicit base SHA.
    base = args.base_ref if args.base_ref != "0" * 40 else "HEAD^"
    commit = subprocess.run(["git", "rev-parse", "--verify", "--end-of-options", base + "^{commit}"],
                            text=True, capture_output=True, check=True).stdout.strip()
    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], text=True,
                          capture_output=True, check=True).stdout.strip()
    relative = Path(args.baseline).resolve().relative_to(Path(root).resolve()).as_posix()
    result = subprocess.run(["git", "show", commit + ":" + relative], text=True,
                            capture_output=True, check=True)
    return parse_ids(ET.fromstring(result.stdout))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", help="path to config/detekt/baseline.xml")
    parser.add_argument("--max", type=int, required=True, help="maximum recorded findings")
    reference = parser.add_mutually_exclusive_group()
    reference.add_argument("--reference", help="previous baseline XML")
    reference.add_argument("--base-ref", default="HEAD^", help="previous Git commit (PR base or push before SHA)")
    args = parser.parse_args(argv)
    if args.max < 0:
        parser.error("--max must not be negative")
    try:
        ids = baseline_ids(args.baseline)
        previous = reference_ids(args)
    except (OSError, ET.ParseError, ValueError, subprocess.CalledProcessError) as error:
        print(f"::error title=Detekt baseline unreadable::{error}", flush=True)
        return 1
    print(f"detekt baseline: {len(ids)} recorded findings (budget {args.max})", flush=True)
    added = sorted(set(ids) - set(previous))
    if added:
        print("::error title=New detekt baseline findings::" + repr(added), flush=True)
        return 1
    if len(ids) > args.max:
        print("::error title=Detekt baseline grew::"
              f"Recorded findings rose to {len(ids)}; remove findings instead of appending them.", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
