#!/usr/bin/env bash
# Phone and Wear deliberately compile against separate pinned Android SDKs.
set -euo pipefail
sdkmanager --install \
  'platforms;android-35' 'platforms;android-36' \
  'build-tools;35.0.0' 'build-tools;36.0.0'
