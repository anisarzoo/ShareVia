namespace ShareVia.Windows.Services;

public sealed class NativeBridgeService
{
    public object HandleAction(string? action)
    {
        return action switch
        {
            "startBluetoothPairing" => new
            {
                type = "pairing-code",
                code = Random.Shared.Next(100000, 999999).ToString(),
            },
            "startNfcPairing" => new
            {
                type = "pairing-code",
                code = Random.Shared.Next(100000, 999999).ToString(),
            },
            "startLocationPairing" => new
            {
                type = "pairing-code",
                code = Random.Shared.Next(100000, 999999).ToString(),
            },
            _ => new
            {
                type = "info",
                message = $"Unknown native action: {action ?? "none"}",
            },
        };
    }
}
