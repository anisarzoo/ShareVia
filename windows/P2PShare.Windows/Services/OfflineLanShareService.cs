using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace ShareVia.Windows.Services;

public sealed class OfflineLanShareService : IDisposable
{
    private const int DiscoveryPort = 48670;
    private const int TransferPort = 48671;
    private static readonly TimeSpan BroadcastInterval = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan PeerExpiry = TimeSpan.FromSeconds(8);

    private readonly string _deviceId = Guid.NewGuid().ToString("N");
    private readonly ConcurrentDictionary<string, PeerSnapshot> _peers = new();
    private readonly JsonSerializerOptions _jsonOptions = new(JsonSerializerDefaults.Web);

    private CancellationTokenSource? _cts;
    private UdpClient? _udpSender;
    private UdpClient? _udpReceiver;
    private TcpListener? _tcpListener;
    private string _displayName = Environment.MachineName;

    public event Action<IReadOnlyList<PeerSnapshot>>? PeersChanged;
    public event Action<string>? StatusChanged;
    public event Action<TransferSnapshot>? TransferUpdated;

    public bool IsRunning => _cts is { IsCancellationRequested: false };

    public void Start(string displayName)
    {
        Stop();

        _displayName = SanitizeName(displayName);
        _cts = new CancellationTokenSource();
        _udpSender = new UdpClient { EnableBroadcast = true };
        _udpReceiver = new UdpClient(DiscoveryPort);
        _tcpListener = new TcpListener(IPAddress.Any, TransferPort);
        _tcpListener.Start();

        var token = _cts.Token;
        _ = Task.Run(() => BroadcastLoopAsync(token), token);
        _ = Task.Run(() => DiscoveryLoopAsync(token), token);
        _ = Task.Run(() => PeerCleanupLoopAsync(token), token);
        _ = Task.Run(() => IncomingTransferLoopAsync(token), token);

        StatusChanged?.Invoke("Nearby session started (offline LAN + direct transfer).");
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;

        _udpSender?.Dispose();
        _udpSender = null;

        _udpReceiver?.Dispose();
        _udpReceiver = null;

        _tcpListener?.Stop();
        _tcpListener = null;

        _peers.Clear();
        RaisePeersChanged();
        StatusChanged?.Invoke("Nearby session stopped.");
    }

    public async Task SendFileAsync(string peerId, string filePath, CancellationToken cancellationToken = default)
    {
        if (!_peers.TryGetValue(peerId, out var peer))
        {
            StatusChanged?.Invoke("Peer is not available.");
            return;
        }
        if (!File.Exists(filePath))
        {
            StatusChanged?.Invoke("Selected file does not exist.");
            return;
        }

        var transferId = Guid.NewGuid().ToString("N");
        var fileInfo = new FileInfo(filePath);
        var transfer =
            new TransferSnapshot(
                transferId,
                peer.DisplayName,
                fileInfo.Name,
                "Sent",
                0,
                "Queued",
                DateTime.Now
            );
        TransferUpdated?.Invoke(transfer);

        try
        {
            using var client = new TcpClient();
            using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            await client.ConnectAsync(IPAddress.Parse(peer.Address), peer.TransferPort, linkedCts.Token);
            await using var network = client.GetStream();
            await using var source = File.OpenRead(filePath);

            var header =
                new TransferHeader(
                    fileInfo.Name,
                    fileInfo.Length,
                    _displayName,
                    _deviceId
                );
            var headerBytes = JsonSerializer.SerializeToUtf8Bytes(header, _jsonOptions);
            var headerLength = BitConverter.GetBytes(headerBytes.Length);

            await network.WriteAsync(headerLength, linkedCts.Token);
            await network.WriteAsync(headerBytes, linkedCts.Token);

            var buffer = new byte[64 * 1024];
            long sent = 0;
            int read;
            while ((read = await source.ReadAsync(buffer, linkedCts.Token)) > 0)
            {
                await network.WriteAsync(buffer.AsMemory(0, read), linkedCts.Token);
                sent += read;
                TransferUpdated?.Invoke(
                    transfer with
                    {
                        Status = "In progress",
                        Progress = fileInfo.Length == 0 ? 1 : (double)sent / fileInfo.Length,
                    }
                );
            }

            TransferUpdated?.Invoke(transfer with { Status = "Completed", Progress = 1 });
            StatusChanged?.Invoke($"Sent {fileInfo.Name} to {peer.DisplayName}.");
        }
        catch (Exception ex)
        {
            TransferUpdated?.Invoke(transfer with { Status = "Failed", Progress = 1 });
            StatusChanged?.Invoke($"Send failed: {ex.Message}");
        }
    }

    private async Task BroadcastLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                var payload =
                    JsonSerializer.SerializeToUtf8Bytes(
                        new DiscoveryMessage(_deviceId, _displayName, TransferPort, DateTime.UtcNow),
                        _jsonOptions
                    );
                var endpoint = new IPEndPoint(IPAddress.Broadcast, DiscoveryPort);
                if (_udpSender != null)
                {
                    await _udpSender.SendAsync(payload, payload.Length, endpoint);
                }
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Discovery broadcast issue: {ex.Message}");
            }

            try
            {
                await Task.Delay(BroadcastInterval, token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private async Task DiscoveryLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            if (_udpReceiver == null)
            {
                return;
            }

            try
            {
                var packet = await _udpReceiver.ReceiveAsync(token);
                var discovery = JsonSerializer.Deserialize<DiscoveryMessage>(packet.Buffer, _jsonOptions);
                if (discovery is null || discovery.DeviceId == _deviceId)
                {
                    continue;
                }

                var peer =
                    new PeerSnapshot(
                        discovery.DeviceId,
                        SanitizeName(discovery.DisplayName),
                        packet.RemoteEndPoint.Address.ToString(),
                        discovery.TransferPort,
                        DateTime.UtcNow
                    );
                _peers.AddOrUpdate(peer.Id, peer, (_, _) => peer);
                RaisePeersChanged();
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (Exception ex)
            {
                StatusChanged?.Invoke($"Discovery receive issue: {ex.Message}");
            }
        }
    }

    private async Task PeerCleanupLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            var threshold = DateTime.UtcNow - PeerExpiry;
            var removed = false;
            foreach (var pair in _peers.ToArray())
            {
                if (pair.Value.LastSeenUtc < threshold)
                {
                    _peers.TryRemove(pair.Key, out _);
                    removed = true;
                }
            }

            if (removed)
            {
                RaisePeersChanged();
            }

            try
            {
                await Task.Delay(TimeSpan.FromSeconds(1), token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private async Task IncomingTransferLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            if (_tcpListener == null)
            {
                return;
            }

            TcpClient? client = null;
            try
            {
                client = await _tcpListener.AcceptTcpClientAsync(token);
                _ = Task.Run(() => HandleIncomingClientAsync(client, token), token);
            }
            catch (OperationCanceledException)
            {
                client?.Dispose();
                return;
            }
            catch (ObjectDisposedException)
            {
                client?.Dispose();
                return;
            }
            catch (Exception ex)
            {
                client?.Dispose();
                StatusChanged?.Invoke($"Incoming transfer issue: {ex.Message}");
            }
        }
    }

    private async Task HandleIncomingClientAsync(TcpClient client, CancellationToken token)
    {
        using var _ = client;
        try
        {
            await using var stream = client.GetStream();

            var headerLengthBytes = await ReadExactAsync(stream, sizeof(int), token);
            var headerLength = BitConverter.ToInt32(headerLengthBytes, 0);
            if (headerLength <= 0 || headerLength > 64 * 1024)
            {
                return;
            }

            var headerBytes = await ReadExactAsync(stream, headerLength, token);
            var header = JsonSerializer.Deserialize<TransferHeader>(headerBytes, _jsonOptions);
            if (header is null)
            {
                return;
            }

            var transferId = Guid.NewGuid().ToString("N");
            var baseTransfer =
                new TransferSnapshot(
                    transferId,
                    SanitizeName(header.SenderName),
                    header.FileName,
                    "Received",
                    0,
                    "Queued",
                    DateTime.Now
                );
            TransferUpdated?.Invoke(baseTransfer);

            var receivedDir =
                Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                    "ShareVia",
                    "Received"
                );
            Directory.CreateDirectory(receivedDir);
            var destinationPath = GetUniquePath(receivedDir, header.FileName);

            await using var target = File.Create(destinationPath);
            var buffer = new byte[64 * 1024];
            long totalRead = 0;

            while (totalRead < header.FileSize)
            {
                var remaining = (int)Math.Min(buffer.Length, header.FileSize - totalRead);
                var read = await stream.ReadAsync(buffer.AsMemory(0, remaining), token);
                if (read <= 0)
                {
                    throw new IOException("Stream ended before transfer finished.");
                }

                await target.WriteAsync(buffer.AsMemory(0, read), token);
                totalRead += read;
                TransferUpdated?.Invoke(
                    baseTransfer with
                    {
                        Status = "In progress",
                        Progress = header.FileSize == 0 ? 1 : (double)totalRead / header.FileSize,
                    }
                );
            }

            TransferUpdated?.Invoke(baseTransfer with { Status = "Completed", Progress = 1 });
            StatusChanged?.Invoke($"Received {header.FileName} from {header.SenderName}.");
        }
        catch (Exception ex)
        {
            StatusChanged?.Invoke($"Incoming transfer failed: {ex.Message}");
        }
    }

    private void RaisePeersChanged()
    {
        var snapshot =
            _peers.Values
                .OrderByDescending(p => p.LastSeenUtc)
                .ThenBy(p => p.DisplayName)
                .ToList();
        PeersChanged?.Invoke(snapshot);
    }

    private static async Task<byte[]> ReadExactAsync(Stream stream, int bytesNeeded, CancellationToken token)
    {
        var buffer = new byte[bytesNeeded];
        var offset = 0;
        while (offset < bytesNeeded)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(offset, bytesNeeded - offset), token);
            if (read <= 0)
            {
                throw new IOException("Unexpected end of stream.");
            }
            offset += read;
        }
        return buffer;
    }

    private static string GetUniquePath(string folder, string fileName)
    {
        var cleanName = string.IsNullOrWhiteSpace(fileName) ? "received.bin" : fileName;
        var candidate = Path.Combine(folder, cleanName);
        if (!File.Exists(candidate))
        {
            return candidate;
        }

        var stem = Path.GetFileNameWithoutExtension(cleanName);
        var ext = Path.GetExtension(cleanName);
        var index = 1;
        while (true)
        {
            candidate = Path.Combine(folder, $"{stem} ({index}){ext}");
            if (!File.Exists(candidate))
            {
                return candidate;
            }
            index += 1;
        }
    }

    private static string SanitizeName(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "Desktop Device";
        }
        var filtered = new string(value.Where(ch => !char.IsControl(ch)).ToArray());
        return filtered.Trim().Length > 30 ? filtered.Trim()[..30] : filtered.Trim();
    }

    public void Dispose()
    {
        Stop();
    }

    private sealed record DiscoveryMessage(
        string DeviceId,
        string DisplayName,
        int TransferPort,
        DateTime TimestampUtc
    );

    private sealed record TransferHeader(
        string FileName,
        long FileSize,
        string SenderName,
        string SenderId
    );
}

public sealed record PeerSnapshot(
    string Id,
    string DisplayName,
    string Address,
    int TransferPort,
    DateTime LastSeenUtc
);

public sealed record TransferSnapshot(
    string TransferId,
    string PeerName,
    string FileName,
    string Direction,
    double Progress,
    string Status,
    DateTime Timestamp
);
