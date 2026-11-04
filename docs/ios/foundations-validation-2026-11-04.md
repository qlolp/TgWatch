# Monitoring foundations validation

PR: https://github.com/qlolp/TgWatch/pull/107

Implementation: `ff0bc34`, `01062d3`, `cd02f81`, based on main `62eec3e`.

- Core: 109 passing tests (25 XCTest and 84 Swift Testing). Includes FIFO admission,
  queued cancellation, active cleanup, error release, route replay, shared history roots,
  backup capture and independent companion country expiry with legacy decoding.
- BuildProject including test targets: passed on iPhone 17 simulator / iOS 27.2.
- Local UI run: stopped after it did not return a result. No local UI pass claimed.
- GitHub iPhone simulator workflow: PASS for commit `374bfd6`, including core tests,
  simulator UI tests and unsigned Release build. Evidence:
  https://github.com/qlolp/TgWatch/actions/runs/41795806146
- Mac workflow also passed. Android workflow failed while installing the pinned Trivy
  scanner; its tests/build steps passed. No Android source was changed in this PR.
- Independent review: no remaining Critical/Important findings. Country expiry finding
  was fixed with regression coverage; the reviewer did not re-read that final fix.
- A deterministic cancellation-versus-permit-grant race test remains a minor follow-up;
  existing tests cover cancellation before grant and during active transport cleanup.
- Physical iPhone installation/launch remains unverified: earlier device connection
  attempts failed despite unlocking. A fresh signed device build passed on November 4;
  direct installation failed with CoreDevice `Failed to allocate RSD device` / `0xE8000003`.
  Awaiting cable reconnection. No device data was deleted or pairing reset.
- Energy performance was not measured; two concurrent service measurements is provisional.

This completes the implementation of the first bounded milestone, not the full roadmap.
Physical-device validation remains an outstanding release check; iOS CI passed. The PR stays draft
until those outstanding checks are resolved.
