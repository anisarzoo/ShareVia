# ShareVia

Cross-platform split:
- `web/`: website + web P2P experience + product marketing.
- `android/`, `ios/`, `windows/`: native-first clients focused on offline + online transfer parity.

## Folders
- `web/` - responsive website and web sharing flow.
- `android/` - native Android V2 shell (Compose + Nearby + realtime hub scaffold).
- `ios/` - legacy iOS source plus new `ios/ShareViaV2/` canonical scaffold for V2.
- `windows/` - operational WPF app plus `windows/ShareVia.WinUI/` migration scaffold.
- `signal-server/` - always-on signaling + V2 realtime/identity/config APIs.
- `scripts/` - helper scripts (asset sync).
- `shared/v2/` - cross-surface contracts (design tokens, API/events, transfer protocol).
- `docs/` - production checklist.

## Product direction
1. Keep home flow minimal: offline/online mode cards + fast send/receive actions.
2. Put advanced modules in sidebar (devices, ecosystem, diagnostics, tools).
3. Preserve native-first transport and align all clients to shared V2 contracts.

## Notes on compatibility
- Android: Nearby offline path + online realtime scaffold wired.
- iOS: V2 scaffold prepared; target transport is Nearby Swift + native online transport.
- Windows: WPF remains active baseline while WinUI 3 migration is implemented.
