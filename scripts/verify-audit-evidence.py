#!/usr/bin/env python3
"""Verify both current audit reports before the release job uses signing Secrets."""
import argparse
import json
from pathlib import Path
import sys

from audit_report import verify_osv, verify_runtime_inventory
from scan_report import verify_report
from release_apps import verify_application_evidence, sbom_path, APPS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--application-id', choices=APPS, default='ru.tgwatch')
    for name in ("sbom", "trivy", "status", "inventory", "osv"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    try:
        read = lambda path: json.loads(path.read_text())
        status = read(args.status)
        if status.get("status") != "passed" or status.get("scannerExitCode") != 0:
            raise ValueError("Trivy scanner did not finish successfully")
        bom, inventory = read(args.sbom), read(args.inventory)
        verify_application_evidence(inventory, bom, read(args.osv), args.application_id)
        verify_osv(read(args.osv), inventory)
        verify_runtime_inventory(bom, inventory)
        if verify_report(read(args.trivy), sbom_path(args.application_id), bom):
            raise ValueError("Trivy HIGH/CRITICAL findings block release")
        print("Verified complete resolved runtime/test OSV audit and hashed CycloneDX Trivy coverage")
    except Exception as error:
        print(f"Release audit evidence refused: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
