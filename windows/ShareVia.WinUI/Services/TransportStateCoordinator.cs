namespace ShareVia.WinUI.Services;

public enum ShareMode
{
    Offline,
    Online,
}

public sealed record TransportState(
    ShareMode Mode,
    bool OfflineActive,
    bool OnlineActive,
    string OfflineStatus,
    string OnlineStatus,
    string? RoomId,
    int ConnectedPeers,
    bool SupportsNfc
);

public sealed class TransportStateCoordinator
{
    public TransportState Current { get; private set; } =
        new(
            ShareMode.Offline,
            OfflineActive: false,
            OnlineActive: false,
            OfflineStatus: "Offline mode ready.",
            OnlineStatus: "Online mode ready.",
            RoomId: null,
            ConnectedPeers: 0,
            SupportsNfc: false
        );

    public event Action<TransportState>? StateChanged;

    public void SetMode(ShareMode mode)
    {
        Current = Current with { Mode = mode };
        StateChanged?.Invoke(Current);
    }

    public void UpdateOffline(bool active, string status)
    {
        Current = Current with { OfflineActive = active, OfflineStatus = status };
        StateChanged?.Invoke(Current);
    }

    public void UpdateOnline(bool active, string status, string? roomId, int peerCount)
    {
        Current =
            Current with
            {
                OnlineActive = active,
                OnlineStatus = status,
                RoomId = roomId,
                ConnectedPeers = peerCount,
            };
        StateChanged?.Invoke(Current);
    }

    public void SetNfcSupport(bool supported)
    {
        Current = Current with { SupportsNfc = supported };
        StateChanged?.Invoke(Current);
    }
}
