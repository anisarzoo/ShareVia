# ShareViaV2 (iOS Native Scaffold)

This folder is the new canonical iOS V2 source root.

## Project generation
Use XcodeGen on macOS to generate the `.xcodeproj`:

1. `brew install xcodegen`
2. `cd ios/ShareViaV2`
3. `xcodegen generate`
4. Open `ShareViaV2.xcodeproj` in Xcode.

## Included
- Native app shell with V2 sections (Home, Devices, Profile, History, Settings, Ecosystem, Diagnostics, Tools)
- Shared mode model (`offline` / `online`)
- Transport abstraction protocols:
  - `OfflineTransport` (target: Nearby Connections Swift package)
  - `OnlineTransport` (target: native WebRTC + relay fallback)
- Realtime event envelopes aligned with `shared/v2/realtime-events.json`
