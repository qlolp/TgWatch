#!/usr/bin/env bash
# Existing production backup only; macOS login Keychain stores its password.
set -euo pipefail
umask 077
[[ $# == 1 ]] || { echo 'Usage: configure-signing.sh /secure/existing-backup-directory' >&2; exit 1; }
[[ "$1" != init && "$1" != restore ]] || {
  echo 'Use the existing backup directory directly. This script never creates or rotates keys.' >&2
  exit 1
}
python3 "$(dirname "$0")/signing-keychain.py" configure "$1" \
  --expected-certificate AB74727A44F59684045E7DB4C820BE309F886433A18A3C06F3439417F3ACBEF1
