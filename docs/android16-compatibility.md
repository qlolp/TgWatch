# Android 16 compatibility qualification

Phone minimum API 26 and Wear minimum API 30 remain unchanged. Wear targets API 35 independently. Phone target changes require a green integrated native matrix (26/29/35/36) and the signed upgrade gate; merely running on Android 16 does not qualify a new target.

## Applicable behavior

| Area | Applicability and existing handling | Qualification |
|---|---|---|
| Edge to edge | Android 16 removes the target-35 opt-out. TgWatch does not request that opt-out. `ScreenInsets` applies system-bar and display-cutout insets to native screens. | Native screen, recreation and large-font checks; verify usable controls, not manifest text alone. |
| Large screens | Android 16 can override orientation, aspect-ratio and resizability restrictions on large displays. Phone activities declare none of those restrictions. | Native layout/recreation checks; no claim of physical foldable qualification. |
| Foreground/background execution | One foreground service uses `specialUse`, not `dataSync` or `mediaProcessing`. The app does not use JobScheduler or WorkManager. User starts, alarm recovery and locked boot must continue to respect platform admission and credential unlock. | Existing Doze, restart and locked-boot smoke on every supported CI API. |
| Exact alarms | The existing alarm permission/admission and fallback paths remain necessary; a target bump does not authorize unlimited background starts. | Existing denied-permission and scheduling smoke, without widening deadlines. |
| Notifications | Notification permission and channel settings remain user-controlled. Resource resolution must work before MainActivity starts. | Native notification/channel and direct-boot checks. |
| Sharing | Only generated cached PNGs are exposed by FileProvider with temporary read grants. No private directory or arbitrary incoming path is shared. | Native cache, grant, expiry and frozen-locale share tests. |

## Source evidence

The official Android developer website was inaccessible from this environment (HTTP 403). Source inspection used the official AOSP GitHub mirror, Android 16 release `android-16.0.0_r1`:

- [PhoneWindow.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/com/android/internal/policy/PhoneWindow.java): `DISABLE_OPT_OUT_EDGE_TO_EDGE` (377864165), enabled since `BAKLAVA`; preceding enforcement is 309578419.
- [Window.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/view/Window.java): window API contracts.
- [ActivityInfo.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/content/pm/ActivityInfo.java): activity orientation/resizability contracts.

SDK platform 36 and build tools 36.0.0 were installed using official SDK metadata. This document separates source applicability from executed qualification. Final target/version and CI evidence are recorded in the release evidence document; no physical-device or OEM battery validation is implied.

## Integrated runtime result

[Run39236704240](https://github.com/qlolp/TgWatch/actions/runs/39236704240), source
`e2d4992bbcf2bee62744e34126f2ae3b27ac37ed`, passed all phone APIs26/29/35/36,
standalone Wear, JVM/lint/detekt, core-mask reproducibility and both dependency
audits. Phone compiles/targets36 with build-tools36.0.0; Wear remains35.
API35/36 execute the actual platform-inset assertion and language-switch sharing
case. Every phone job also completed legacy upgrade, network recovery, Doze and
PIN-locked Direct Boot. Signed latest/legacy production upgrades remain mandatory
gates of the tag publication workflow; their final results belong to the versioned
release verification record.

Final publication: [run39241608647](https://github.com/qlolp/TgWatch/actions/runs/39241608647),
tagv1.20.1/source4b2aba4d1c87606ed482752cf027c85e5896dece, passed all eight jobs,
including signed legacy1.10.47/latest1.19.1 upgrades. Downloaded phone and Wear APKs
confirm min/target/compile26/36/36 and30/35/35 respectively, with the existing
production certificate. See[versioned evidence](distribution/verification-1.20.1.json).
