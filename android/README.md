# Android App (Native Offline-First)

This module is now a native Compose app, not a WebView wrapper.

## What it includes
- Native UI with hamburger drawer (`Home`, `Profile`, `History`).
- Profile identity (display name + display picture) shown during nearby discovery.
- Nearby Connections as one consistent stack for:
  - device discovery,
  - pairing,
  - file payload transfer.
- Activity history stored on-device.

## Build
1. Open `android/` in Android Studio.
2. Sync Gradle.
3. Run:
   - `./gradlew :app:compileDebugKotlin`
   - `./gradlew :app:assembleDebug`

## Offline behavior
- Internet is optional.
- Discovery + transfer are local nearby operations.
- In flight mode, sharing can still work if Bluetooth is manually enabled.

## Notes
- Online-mode expansion can be layered on top without routing core discovery through web code.
- Website assets are no longer required to run the native Android app.
