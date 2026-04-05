using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using ShareVia.Windows.Services;

namespace ShareVia.Windows;

public partial class MainWindow : Window
{
    private readonly OfflineLanShareService _shareService = new();
    private readonly ProfileSettingsStore _profileStore = new();
    private readonly ObservableCollection<PeerRow> _peers = new();
    private readonly ObservableCollection<TransferRow> _transfers = new();
    private readonly ObservableCollection<HistoryRow> _history = new();
    private readonly Dictionary<string, TransferSnapshot> _transferMap = new();
    private readonly HashSet<string> _historyTransferIds = new();

    private string? _avatarPath;
    private bool _menuOpen = true;

    public MainWindow()
    {
        InitializeComponent();

        PeersListView.ItemsSource = _peers;
        TransfersListView.ItemsSource = _transfers;
        HistoryListView.ItemsSource = _history;

        LoadProfile();
        ShowSection(AppSection.Home);
        SyncSessionButtons();

        _shareService.PeersChanged += peers => Dispatcher.Invoke(() => UpdatePeers(peers));
        _shareService.TransferUpdated += transfer => Dispatcher.Invoke(() => UpdateTransfers(transfer));
        _shareService.StatusChanged += status => Dispatcher.Invoke(() => SetStatus(status));

        Closed += (_, _) => _shareService.Dispose();
    }

    private void HamburgerButton_Click(object sender, RoutedEventArgs e)
    {
        _menuOpen = !_menuOpen;
        MenuColumn.Width = _menuOpen ? new GridLength(220) : new GridLength(0);
    }

    private void HomeMenuButton_Click(object sender, RoutedEventArgs e) => ShowSection(AppSection.Home);

    private void ProfileMenuButton_Click(object sender, RoutedEventArgs e) => ShowSection(AppSection.Profile);

    private void HistoryMenuButton_Click(object sender, RoutedEventArgs e) => ShowSection(AppSection.History);

    private void StartNearbyButton_Click(object sender, RoutedEventArgs e)
    {
        _shareService.Start(GetProfileName());
        SyncSessionButtons();
    }

    private void StopNearbyButton_Click(object sender, RoutedEventArgs e)
    {
        _shareService.Stop();
        SyncSessionButtons();
    }

    private void PairPeerButton_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || button.Tag is not string peerId)
        {
            return;
        }
        var peer = _peers.FirstOrDefault(item => item.Id == peerId);
        if (peer != null)
        {
            SetStatus($"Pairing ready with {peer.DisplayName}. Use Send File to start transfer.");
        }
    }

    private async void SendFileButton_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button button || button.Tag is not string peerId)
        {
            return;
        }
        var peer = _peers.FirstOrDefault(item => item.Id == peerId);
        if (peer == null)
        {
            return;
        }

        var picker = new OpenFileDialog
        {
            Title = "Choose file to send",
            CheckFileExists = true,
            Multiselect = false,
        };
        if (picker.ShowDialog(this) != true)
        {
            return;
        }

        await _shareService.SendFileAsync(peerId, picker.FileName);
    }

    private void ChangeAvatarButton_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog
        {
            Title = "Choose profile picture",
            Filter = "Image Files|*.png;*.jpg;*.jpeg;*.bmp;*.webp",
            CheckFileExists = true,
            Multiselect = false,
        };

        if (picker.ShowDialog(this) == true)
        {
            _avatarPath = picker.FileName;
            RefreshAvatarPreview();
        }
    }

    private void SaveProfileButton_Click(object sender, RoutedEventArgs e)
    {
        var profile = new ProfileSettings(GetProfileName(), _avatarPath);
        _profileStore.Save(profile);
        SetStatus("Profile saved.");

        if (_shareService.IsRunning)
        {
            _shareService.Start(profile.DisplayName);
            SyncSessionButtons();
        }
    }

    private void ClearHistoryButton_Click(object sender, RoutedEventArgs e)
    {
        _history.Clear();
        _historyTransferIds.Clear();
        SetStatus("History cleared.");
    }

    private void ShowSection(AppSection section)
    {
        HomePanel.Visibility = section == AppSection.Home ? Visibility.Visible : Visibility.Collapsed;
        ProfilePanel.Visibility = section == AppSection.Profile ? Visibility.Visible : Visibility.Collapsed;
        HistoryPanel.Visibility = section == AppSection.History ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdatePeers(IReadOnlyList<PeerSnapshot> peers)
    {
        _peers.Clear();
        foreach (var peer in peers)
        {
            _peers.Add(
                new PeerRow(
                    peer.Id,
                    peer.DisplayName,
                    $"{peer.Address}:{peer.TransferPort} - seen {peer.LastSeenUtc.ToLocalTime():HH:mm:ss}"
                )
            );
        }
    }

    private void UpdateTransfers(TransferSnapshot transfer)
    {
        _transferMap[transfer.TransferId] = transfer;

        var ordered = _transferMap.Values.OrderByDescending(item => item.Timestamp).ToList();
        _transfers.Clear();
        foreach (var item in ordered)
        {
            _transfers.Add(
                new TransferRow(
                    item.TransferId,
                    item.FileName,
                    $"{item.PeerName} - {item.Direction} - {item.Status}",
                    item.Progress
                )
            );
        }

        if (string.Equals(transfer.Status, "Completed", StringComparison.OrdinalIgnoreCase) &&
            _historyTransferIds.Add(transfer.TransferId))
        {
            _history.Insert(
                0,
                new HistoryRow(
                    transfer.TransferId,
                    transfer.FileName,
                    $"{transfer.PeerName} - {transfer.Direction}",
                    transfer.Timestamp.ToString("dd MMM yyyy, HH:mm")
                )
            );
        }
    }

    private void LoadProfile()
    {
        var profile = _profileStore.Load();
        ProfileNameTextBox.Text = profile.DisplayName;
        _avatarPath = profile.AvatarPath;
        RefreshAvatarPreview();
    }

    private void RefreshAvatarPreview()
    {
        if (!string.IsNullOrWhiteSpace(_avatarPath) && File.Exists(_avatarPath))
        {
            AvatarImage.Source = new BitmapImage(new Uri(_avatarPath, UriKind.Absolute));
        }
        else
        {
            AvatarImage.Source = null;
        }
    }

    private string GetProfileName()
    {
        var text = ProfileNameTextBox.Text?.Trim();
        if (string.IsNullOrWhiteSpace(text))
        {
            text = Environment.MachineName;
            ProfileNameTextBox.Text = text;
        }
        return text.Length > 30 ? text[..30] : text;
    }

    private void SyncSessionButtons()
    {
        StartNearbyButton.Content = _shareService.IsRunning ? "Restart Nearby" : "Start Nearby";
        StopNearbyButton.IsEnabled = _shareService.IsRunning;
    }

    private void SetStatus(string status)
    {
        TopStatusText.Text = status;
    }

    private enum AppSection
    {
        Home,
        Profile,
        History,
    }

    private sealed record PeerRow(string Id, string DisplayName, string AddressLabel);

    private sealed record TransferRow(string TransferId, string FileName, string Subtitle, double Progress);

    private sealed record HistoryRow(string TransferId, string FileName, string Subtitle, string TimeLabel);
}
