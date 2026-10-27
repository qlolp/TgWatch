#!/usr/bin/env bash
# Keep previous platforms for UI fixtures; the phone spike compiles against API37.
set -euo pipefail
if command -v sdkmanager >/dev/null 2>&1; then
  manager=$(command -v sdkmanager)
else
  sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  manager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
  if [ -z "$sdk_root" ] || [ ! -x "$manager" ]; then
    echo 'Android sdkmanager unavailable: set ANDROID_HOME or add command-line tools to PATH.' >&2
    exit 1
  fi
fi
"$manager" --install \
  'platforms;android-35' 'platforms;android-36' 'platforms;android-37.0' \
  'build-tools;35.0.0' 'build-tools;36.0.0' 'build-tools;37.0.0'
