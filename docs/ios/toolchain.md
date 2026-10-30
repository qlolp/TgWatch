# Toolchain and verification status

2026-10-30: implementation workspace is Linux Debian 13 x86_64, not the owner's MacBook Air.
Swift 6.2 RELEASE (official Ubuntu 24.04 archive from download.swift.org) is installed only under
`/workspace/iphone-tools` for portable tests; no system toolchain/account configuration was changed.
The package uses Swift tools 5.9 / Swift language 5 to support the declared iOS 17 baseline.

Portable test results are recorded in the PR. Apple-only transport and UI tests require macOS/Xcode.
Source parse, plist/project validation and Linux XCTest success do not count as Simulator/device execution.
Xcode project is authored declaratively because Xcode GUI is unavailable; its first actual Xcode build
is an explicit outstanding integration gate.

Proposed CI toolchain: macos-26, `/Applications/Xcode_26.6.app`, an available installed iPhone Simulator.
Inventory source: https://github.com/actions/runner-images/blob/main/images/macos/macos-26-arm64-Readme.md
Runner inventory may change; missing pinned Xcode/runtime must fail clearly, not download or silently select another.

Owner reports iPhone 15 / iOS 27.2, no paid Developer Program. The OS version and compatible installed
Xcode/device support have not been independently verified. No physical install, signing, certificate,
profile creation, Apple login, enrollment or payment has occurred.

Before a device test, collect locally: `sw_vers`, `xcode-select -p`, `xcodebuild -version`,
`xcodebuild -showsdks`, `xcrun simctl list devices available`, `xcrun devicectl list devices`, `df -h`.
Keep UDIDs, account identities and team identifiers out of public logs. If license acceptance,
Xcode/runtime installation or account provisioning is required, obtain approval for that specific action.
