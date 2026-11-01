# TgWatch app icon

Asset: `ios/TgWatchIOS/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png`.
Opaque 1024 × 1024 PNG generated with the built-in imagegen tool and resized with sips.

Design prompt: White paper airplane centered inside a cyan monitoring ring with a mint-green
availability dot, on a deep navy background. Minimal, high contrast, no text, full bleed square.

The Resources folder was added through Xcode. To activate the icon, select the TgWatchIOS
target, General → App Icons and Launch Screen, and set App Icon to `AppIcon`.
The automation tool currently rejects `ASSETCATALOG_COMPILER_APPICON_NAME` as unknown;
this setting needs to be selected in Xcode before the next device build.
