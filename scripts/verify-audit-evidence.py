#!/usr/bin/env python3
"""Verify both current audit reports before the release job uses signing Secrets."""
import argparse
import json
from pathlib import Path
import sys

from audit_report import verify_osv, verify_runtime_inventory
from scan_report import verify_report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sbom", "trivy", "status", "inventory", "osv"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    try:
        read = lambda path: json.loads(path.read_text())
        status = read(args.status)
        if status.get("status") != "passed" or status.get("scannerExitCode") != 0:
            raise ValueError("Trivy scanner did not finish successfully")
        bom, inventory = read(args.sbom), read(args.inventory)
        verify_osv(read(args.osv), inventory)
        verify_runtime_inventory(bom, inventory)
        if verify_report(read(args.trivy), "app/build/reports/sbom/TgWatch.sbom.cdx.json", bom):
            raise ValueError("Trivy HIGH/CRITICAL findings block release")
        print("Verified complete resolved runtime/test OSV audit and hashed CycloneDX Trivy coverage")
    except Exception as error:
        print(f"Release audit evidence refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
