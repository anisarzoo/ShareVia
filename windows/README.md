# Windows App (Production Starter)

This folder contains a WPF + WebView2 desktop app wrapper for P2PShare.

## Included
- `P2PShare.Windows.csproj` (.NET 8)
- `MainWindow.xaml` + `MainWindow.xaml.cs`
- `Services/NativeBridgeService.cs`
- `Assets/Web/` for the web bundle

## Build
1. Copy files from `../web` into `P2PShare.Windows/Assets/Web/`.
2. Run:
   - `dotnet restore windows/P2PShare.Windows/P2PShare.Windows.csproj`
   - `dotnet build windows/P2PShare.Windows/P2PShare.Windows.csproj -c Release`

## Native bridge actions
- `startBluetoothPairing`
- `startNfcPairing`
- `startLocationPairing`

Current service returns pairing codes so the web flow works immediately.
Replace service internals with WinRT Bluetooth/NFC/location integrations for production hardware workflows.
