#!/usr/bin/env bash
# Trivy's nonzero scan/gate status is preserved; unavailable scans never mean "clean".
set -euo pipefail
sbom=${1:?Usage: scan-sbom.sh input.cdx.json report.json}
report=${2:?Usage: scan-sbom.sh input.cdx.json report.json}
mkdir -p "$(dirname "$report")"
status="${report%.json}.status.json"
log="${report%.json}.log"
# Discard only outputs of a previous invocation, so stale evidence cannot pass the gate.
rm -f "$report" "$status" "$log"
set +e
trivy sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1 \
  --list-all-pkgs --format json --output "$report" "$sbom" > "$log" 2>&1
result=$?
set -e
python3 - "$report" "$status" "$result" "$(dirname "$0")" "$sbom" <<'PY'
import json, pathlib, sys
sys.path.insert(0, sys.argv[4])
from scan_report import verify_report
from jsonschema.exceptions import ValidationError
report, status = map(pathlib.Path, sys.argv[1:3])
code = int(sys.argv[3])
state = 'unavailable'
try:
    scan = json.loads(report.read_text())
    high = verify_report(scan, sys.argv[5], json.loads(pathlib.Path(sys.argv[5]).read_text()))
    state = 'vulnerabilities' if high else 'passed' if code == 0 else 'failed'
except (OSError, ValueError, TypeError, KeyError, ValidationError):
    pass
status.write_text(json.dumps({'status': state, 'scannerExitCode': code}) + '\n')
print('Trivy SBOM gate: ' + state)
if state != 'passed': sys.exit(code if code else 1)
PY
