# iOS App (Native Offline-First)

The iOS client is now native-first (`SwiftUI` + `MultipeerConnectivity`) and no longer depends on a web container.

## Included
- Sidebar/hamburger-friendly app shell (`Home`, `Profile`, `History`).
- Profile identity editor (display name + display picture).
- `OfflineShareService` for offline nearby discovery + direct file transfer.
- Local history persistence for completed transfers.

## Build notes
1. Open the iOS project in Xcode and include files from `ios/P2PShare/`.
2. Ensure `Info.plist` keeps:
   - `NSBluetoothAlwaysUsageDescription`
   - `NSLocalNetworkUsageDescription`
   - `NSBonjourServices`
3. Run on physical iOS devices for nearby transfer validation.

## Product role
- Native app: all-round offline/online sharing client.
- Website: web sharing path + marketing surface.
