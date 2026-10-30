#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != Darwin ]] || ! command -v xcodebuild >/dev/null || ! command -v xcrun >/dev/null; then
  echo 'iOS tests require macOS with full Xcode and an installed Simulator runtime.' >&2
  exit 2
fi
if [[ $# -ne 2 ]]; then
  echo 'Usage: scripts/test-ios.sh SIMULATOR_UDID NEW_RESULT_DIRECTORY' >&2
  exit 2
fi
simulator_id="$1"
result_directory="$2"
if [[ -e "$result_directory" ]]; then
  echo 'Result directory already exists; choose a new path to preserve evidence.' >&2
  exit 2
fi
xcrun simctl list devices available -j | python3 -c '
import json,sys
wanted=sys.argv[1]
if not any(d["udid"] == wanted for devices in json.load(sys.stdin)["devices"].values() for d in devices):
    sys.exit("Requested simulator is not installed/available; no runtime will be downloaded.")
' "$simulator_id"
mkdir -p "$result_directory"
xcodebuild -version | tee "$result_directory/toolchain.txt"
xcodebuild -showsdks >> "$result_directory/toolchain.txt"
git -C "$repo_root" rev-parse HEAD >> "$result_directory/toolchain.txt"
swift test --package-path "$repo_root/ios/TgWatchCore" 2>&1 | tee "$result_directory/core-tests.log"
xcodebuild test -project "$repo_root/ios/TgWatchIOS.xcodeproj" -scheme TgWatchIOS \
  -destination "id=$simulator_id" -parallel-testing-enabled NO \
  -derivedDataPath "$result_directory/DerivedData" CODE_SIGNING_ALLOWED=NO \
  -resultBundlePath "$result_directory/tests.xcresult" 2>&1 | tee "$result_directory/simulator-tests.log"
xcodebuild build -configuration Release -project "$repo_root/ios/TgWatchIOS.xcodeproj" -scheme TgWatchIOS \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO \
  -derivedDataPath "$result_directory/ReleaseDerivedData" 2>&1 | tee "$result_directory/release-build.log"
