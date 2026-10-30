# First iPhone test — pending, not executed

Target: owner's iPhone 15, reported iOS 27.2. No paid Apple Developer Program.
First path is Personal Team, subject to compatibility and Apple account eligibility on the selected Mac.
Mac Developer ID is not the certificate needed for an iPhone development build.

1. Finish the simulator gates in `README.md`; inspect exact commit and result bundle.
2. Confirm actual phone OS and installed Xcode support on the owner's Mac. Do not infer compatibility
   from a successful older Simulator runtime. Obtain a specific install/update decision if needed.
3. Separately authorize the selected Apple Account/Personal Team and automatic signing operation.
   User performs login/2FA, device trust and Developer Mode locally. Never send credentials through chat.
4. In Xcode select the phone and team. Proposed identifier is `ru.tgwatch.ios`; availability must be checked
   before registration. Do not create App IDs/certificates/profiles without the separate authorization.
5. Build/install/run, recording source SHA and redacted toolchain/model/OS. No TestFlight upload or paid enrollment.
6. Verify Wi-Fi/cellular, airplane mode, captive portal, VPN/network changes during a check, cancellation,
   lock/unlock, force-quit/restart, Low Power Mode, RU/EN, VoiceOver and largest Dynamic Type.
   Inactive/background must stop measurements and show gaps, never a fabricated healthy status.
7. Verify history persistence and update without data loss, explicit delete confirmation and offline/failed
   checks. Record real outcomes, including unexpected failures, without declaring live network failure a test pass.

Personal Team profiles have limited lifetime (Apple currently documents seven days); reprovisioning may
be needed. TestFlight/distribution is a separate decision requiring the appropriate membership and signing.
References: https://developer.apple.com/help/account/basics/about-your-developer-account/
and https://developer.apple.com/support/xcode/ .
