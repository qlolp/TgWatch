#!/usr/bin/env bash
# Run once on a trusted machine with JDK 17+, gh and authenticated qlolp access.
set -euo pipefail
umask 077
signing_dir=${1:?Usage: ./scripts/configure-signing.sh /secure/backup-directory}
# Never create a replacement key or a plaintext password sidecar.
python3 "$(dirname "$0")/signing-keychain.py" configure "$signing_dir"
