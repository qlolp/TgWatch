# TgWatch app icon

Asset: `ios/TgWatchIOS/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png`.
Opaque 1024 × 1024 PNG generated with the built-in imagegen tool and resized with sips.

Design prompt: White paper airplane centered inside a cyan monitoring ring with a mint-green
availability dot, on a deep navy background. Minimal, high contrast, no text, full bleed square.

The primary icon is registered through `CFBundleIcons` → `CFBundlePrimaryIcon` in
`ios/TgWatchIOS/Info.plist`, configured with Xcode's AddInfoPlist tool. The Resources
folder contains opaque PNG renditions for 20, 29, 40 and 60 pt at 2x and 3x.
These are derived from the 1024 px master above with `sips -z` and copied into the app.

This uses bundle icon files because the current automation tool rejects
`ASSETCATALOG_COMPILER_APPICON_NAME`. No direct project-file editing is required.
For a future App Store archive, configure the AppIcon asset catalog as the primary
icon in Xcode and validate the archive's marketing icon requirements.
