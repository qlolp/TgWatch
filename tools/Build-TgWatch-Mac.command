#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
if bash "$root/scripts/build-mac-app.sh"; then
  /usr/bin/open -R "$root/build/macos/TgWatch-mac-local-build.zip"
else
  echo 'Build failed. See docs/macos.md for prerequisites.' >&2
  read -r -p 'Press Return to close…' unused_reply
  exit 1
fi
