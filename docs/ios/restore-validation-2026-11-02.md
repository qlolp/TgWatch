# Backup and companion safety corrections — 2026-11-02

The review of local head `1ff47ad` found that a malformed but authenticated backup could replace valid data before its contents were checked. The private manifest writer had also lost its size/privacy safeguards, and Widget/Watch snapshots could look fresh after a clock rollback. This correction addresses those paths without changing project settings, signing, capabilities, or device settings.

## Resulting behavior

- Export reads every existing managed file with a bound and fails on read errors, corruption, unknown files, or symlinks. Legitimately absent settings/history are represented explicitly with defaults. Each current target and each included archived revision has a complete file set.
- Seal, open, preview and restore share manifest/content validation. Required files, service IDs/revisions, favorites, history schema/counts, saved settings, preview counts and byte limits must agree. Authentication alone is insufficient. Preview names come from the validated definitions.
- Invalid or incomplete input fails before current data or monitor state is changed. A confirmed valid restore stops monitors, writes and rereads a sibling staging directory, atomically exchanges directories, and verifies installed bytes. Failed verification swaps the old directory back. If rollback itself fails, the old directory is retained and further writes are blocked for recovery.
- The directory exchange uses Apple's documented [`RENAME_SWAP`](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/man/man2/rename.2). There is no non-atomic two-move fallback on unsupported filesystems.
- Private manifests/webhook settings use bounded writes, complete file protection on iOS, and exclusion from system backups. Imported private files receive those attributes before replacement. The manifest read/write limit remains 65,536 bytes.
- Restore discards navigation destinations retaining old controllers. Monitoring remains stopped; manual checks remain available while the app is active. Key derivation runs away from the UI actor, and stale asynchronous import previews are discarded.
- All three companion models reject future observation/generation timestamps, invalid expiry intervals and non-finite times. The bridge uses the monitor's actual lifetime and publishes route/lifecycle/clock invalidations. Widget timelines include favorite expirations; Watch decoding happens on its main actor.

## Verification ledger

Implementation was prepared in a separate copy while the original Xcode Coding Assistant was active. No user backup was restored and no physical device was contacted.

- Native core suite: **92 tests passed** (25 XCTest + 67 Swift Testing), including 16 new regression tests.
- Regression cases include empty/malformed/incomplete archives, invalid saved settings/history, preview-count mismatch, bounded writers, authenticated invalid payloads, oversized ciphertext, export corruption/symlinks, successful synthetic restore, commit failure, post-commit rollback, retained recovery data after rollback failure, private backup-exclusion attributes, and companion clock rollback.
- Final simulator UI suite: **5 tests passed** on the existing iPhone 17 / iOS 27.2 simulator.
- Final Release Simulator build: **passed**, signing disabled. Source hashes were unchanged throughout the final core/UI/Release sequence. The only diagnostic was the nonfatal App Intents metadata warning for a target without that framework.
- The existing Xcode assistant's `WatchSessionManager.swift` actor fix was preserved verbatim; it differs from the tested copy only in whitespace. Project files, signing and capabilities were not changed by this correction.

Local validation is not a GitHub CI result. Publication and server-side checks are reported separately with the resulting commit/PR.

## Acceptance limits

The filesystem restore tests use synthetic temporary directories on macOS. Complete file-protection class behavior on a physical locked iPhone has not been tested. Existing five UI tests cover monitoring flows, not interactive document-picker/backup restore acceptance. Actual Widget/Watch delivery, notifications/vibration, physical installation/update and preservation of real user data remain unverified. The phone must be checked separately after unlock; no installation is implied by these tests.

Legacy incomplete archives from the unpublished prototype are rejected rather than used to replace current data. Android TGWB import remains unsupported. This work does not claim full Android feature parity or continuous background monitoring.
