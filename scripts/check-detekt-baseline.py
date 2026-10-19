#!/usr/bin/env python3
"""Fail when the detekt baseline grows: legacy findings may only be removed, never added."""
import argparse
import sys
import xml.etree.ElementTree as ET


def baseline_ids(path):
    """Return every recorded finding ID; an empty or valueless baseline is an error."""
    root = ET.parse(path).getroot()
    ids = [element.text for element in root.iter("ID")]
    if not ids:
        raise ValueError("baseline records no findings")
    if any(not value or not value.strip() for value in ids):
        raise ValueError("baseline contains an empty finding ID")
    return ids


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", help="path to config/detekt/baseline.xml")
    parser.add_argument("--max", type=int, required=True, help="maximum recorded findings")
    args = parser.parse_args(argv)
    if args.max < 0:
        parser.error("--max must not be negative")
    try:
        ids = baseline_ids(args.baseline)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"::error title=Detekt baseline unreadable::{error}", flush=True)
        return 1
    print(f"detekt baseline: {len(ids)} recorded findings (budget {args.max})", flush=True)
    if len(ids) > args.max:
        print("::error title=Detekt baseline grew::"
              f"Recorded findings rose to {len(ids)}; remove findings instead of appending them.", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
