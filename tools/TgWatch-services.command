#!/bin/bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
exec python3 "$project_dir/scripts/regional-services.py" --watch --notify
