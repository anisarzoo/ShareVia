# Windows App (Production Starter)

This folder contains a WPF + WebView2 desktop app wrapper for ShareVia.

## Included
- `ShareVia.Windows.csproj` (.NET 8)
- `MainWindow.xaml` + `MainWindow.xaml.cs`
- `Services/NativeBridgeService.cs`
- `Assets/Web/` for the web bundle

## Build
1. Copy files from `../web` into `ShareVia.Windows/Assets/Web/`.
2. Run:
   - `dotnet restore windows/ShareVia.Windows/ShareVia.Windows.csproj`
   - `dotnet build windows/ShareVia.Windows/ShareVia.Windows.csproj -c Release`

## Native bridge actions
- `startBluetoothPairing`
- `startNfcPairing`
- `startLocationPairing`

Current service returns pairing codes so the web flow works immediately.
Replace service internals with WinRT Bluetooth/NFC/location integrations for production hardware workflows.
