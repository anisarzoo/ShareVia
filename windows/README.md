# Windows App (Native Offline-First)

The desktop client is now native WPF (no WebView2 dependency) with offline LAN discovery and direct file transfer.

## V2 WinUI migration scaffold
- New project path: `windows/ShareVia.WinUI/`.
- This is the migration target for the V2 shell:
  - branded Home + sidebar module structure,
  - unified offline/online mode model,
  - transport state coordinator for parity with Android/iOS architecture.
- Existing WPF project (`windows/P2PShare.Windows/`) remains the operational baseline while WinUI implementation is built out.

## Included
- Native app shell with hamburger menu (`Home`, `Profile`, `History`).
- `OfflineLanShareService`:
  - UDP broadcast discovery on local network,
  - direct TCP file transfer between peers.
- Profile editor with display name + display picture.
- In-app transfer history section.

## Build
1. Install .NET 8 SDK.
2. Run:
   - `dotnet restore windows/P2PShare.Windows/P2PShare.Windows.csproj`
   - `dotnet build windows/P2PShare.Windows/P2PShare.Windows.csproj -c Release`

## Product role
- Native desktop app: primary offline/online sharing client.
- Website: web sharing and product marketing channel.
