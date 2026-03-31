# iOS App (Production Starter)

This folder provides a SwiftUI + WKWebView native wrapper around the shared P2PShare web client.

## Included
- `P2PShareApp.swift`: app entry point.
- `WebContainerView.swift`: WKWebView host with JS bridge injection.
- `Bridge/NativeBridgeCoordinator.swift`: handles native pairing callbacks.
- `Info.plist`: camera, bluetooth, NFC, and location usage descriptions.

## Build notes
1. Create an Xcode iOS app project and add files from `ios/P2PShare/`.
2. Add the web bundle into app resources.
3. Ensure `NativeP2PBridge` script message handler is enabled.

## Bridge actions implemented
- `startBluetoothPairing`
- `startNfcPairing`
- `startLocationPairing`

Current methods return safe pairing-code stubs so UI flow works now.
Replace internals with production CoreBluetooth/CoreNFC/CoreLocation logic.
