# Production Checklist

## Security
- Use HTTPS for web and signaling in public networks.
- Restrict CORS in `signal-server` for known origins.
- Keep native bridge APIs minimal and action-scoped.
- Validate file metadata and enforce max queue limits in clients.

## Reliability
- Keep `chunkSize` at 64KB for mixed device compatibility.
- Use LAN signaling server for offline Wi-Fi mode.
- Monitor transfer logs and retry interrupted sends.
- Keep background execution permissions configured for mobile.

## Native Pairing
- Android: implement BLE/Nearby discovery in `NativeBridge.kt`.
- iOS: implement CoreBluetooth/CoreNFC handlers in `NativeBridgeCoordinator`.
- Windows: implement Bluetooth/NFC using WinRT APIs in `NativeBridgeService`.

## Release
- Android: build signed `release` APK/AAB.
- iOS: archive with correct entitlements and privacy strings.
- Windows: publish signed installer/MSIX.
- Run end-to-end testing on mixed OS/device matrix.
