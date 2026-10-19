#!/usr/bin/env python3
"""Fail when an APK exceeds its pinned byte budget; print APK and dex sizes for the summary."""
import argparse
import glob
import os
import sys
import zipfile


def apk_sizes(path):
    """Return (apk bytes, sum of uncompressed dex bytes); a corrupt archive is an error."""
    with zipfile.ZipFile(path) as archive:
        corrupt = archive.testzip()
        if corrupt is not None:
            raise ValueError(f"corrupt archive entry {corrupt}")
        dex = sum(entry.file_size for entry in archive.infolist() if entry.filename.endswith(".dex"))
    return os.path.getsize(path), dex


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pattern", nargs="+", help="APK path or glob")
    parser.add_argument("--budget-bytes", type=int, required=True)
    args = parser.parse_args(argv)
    if args.budget_bytes <= 0:
        parser.error("--budget-bytes must be positive")
    paths = sorted({path for pattern in args.pattern for path in glob.glob(pattern)})
    if not paths:
        print("::error title=APK size unreadable::No APK matched the given pattern", flush=True)
        return 1
    failed = False
    for path in paths:
        try:
            total, dex = apk_sizes(path)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            print(f"::error title=APK size unreadable::{path}: {error}", flush=True)
            failed = True
            continue
        share = 100 * total // args.budget_bytes
        print(f"{path}: {total} bytes, dex {dex} bytes, {share}% of budget {args.budget_bytes}", flush=True)
        if total > args.budget_bytes:
            print(f"::error title=APK size budget exceeded::{path} is {total} bytes, "
                  f"budget {args.budget_bytes}", flush=True)
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
