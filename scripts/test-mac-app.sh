#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
checks="$root/build/macos/tests"
mkdir -p "$checks/module-cache"
xcrun swiftc -swift-version 5 -module-cache-path "$checks/module-cache" \
  macos/TgWatch/RegionalMenuModel.swift macos/tests/RegionalMenuModelTests.swift -o "$checks/model-tests"
"$checks/model-tests"
xcrun swiftc -swift-version 5 -module-cache-path "$checks/module-cache" \
  macos/TgWatch/RegionalMenuModel.swift macos/TgWatch/RegionalEngineProcess.swift \
  macos/tests/RegionalEngineTests.swift -o "$checks/process-tests"
"$checks/process-tests"
xcrun swiftc -swift-version 5 -module-cache-path "$checks/module-cache" \
  macos/TgWatch/RegionalMenuModel.swift macos/TgWatch/MacPublicResponseWatch.swift \
  macos/tests/MacPublicResponseWatchTests.swift -o "$checks/watch-tests"
"$checks/watch-tests"
# GUI smoke is mandatory here. Headless/local callers may run the separate static test method explicitly.
python3 -m unittest discover -s macos/tests -p test_bundle.py -v
