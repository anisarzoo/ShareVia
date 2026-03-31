# Android App (Production Starter)

This folder contains a native Android WebView wrapper for the upgraded P2PShare web client.

## Why this structure
- Native permissions for Bluetooth, NFC, camera, and location.
- JavaScript bridge (`NativeP2PBridge`) so web UI can trigger native pairing helpers.
- Local asset mode (`file:///android_asset/index.html`) for offline startup.

## Build
1. Copy `../web/*` assets into `app/src/main/assets/`.
2. Open this folder in Android Studio (`android/`).
3. Sync Gradle and run on Android 7.0+ (API 24+).

## Native bridge actions
- `startBluetoothPairing`
- `startNfcPairing`
- `startLocationPairing`

The current `NativeBridge.kt` ships safe starter logic and callback plumbing.
Replace pairing internals with production BLE/Nearby/NFC flows per your hardware requirements.

## Security checklist
- Keep release build minification enabled.
- Pin your signaling endpoint and TLS cert for online mode.
- Restrict JS bridge methods to required actions only.
- Add runtime permission UX for Android 12+ Bluetooth permissions.
