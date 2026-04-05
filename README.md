# ShareVia

Cross-platform split:
- `web/`: website + web P2P experience + product marketing.
- `android/`, `ios/`, `windows/`: native-first clients focused on real offline-first nearby sharing.

## Folders
- `web/` - responsive website and web sharing flow.
- `android/` - fully native Android client (Compose + Nearby Connections).
- `ios/` - native SwiftUI + MultipeerConnectivity client.
- `windows/` - native WPF + LAN discovery/transfer client.
- `signal-server/` - always-on signaling service.
- `scripts/` - helper scripts (asset sync).
- `docs/` - production checklist.

## Product direction
1. Native apps are all-rounders: offline nearby transfer first, online expansion second.
2. Website remains the web sharing channel plus marketing surface.
3. Native app identity uses profile name + display picture to make pairing clearer.

## Notes on compatibility
- Android: Nearby Connections handles discovery + transfer in one native stack.
- iOS: MultipeerConnectivity handles discovery + transfer in one native stack.
- Windows: LAN broadcast discovery + direct socket transfer for local/offline sharing.
