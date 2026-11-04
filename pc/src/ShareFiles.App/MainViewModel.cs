using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using QRCoder;
using ShareFiles.Server;

namespace ShareFiles.App;

public abstract class Observable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        Raise(name);
        return true;
    }

    protected void Raise(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public static class Format
{
    private static readonly CultureInfo Vietnamese = new("vi-VN");

    public static string Bytes(double bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        var unit = 0;
        while (bytes >= 1000 && unit < units.Length - 1)
        {
            bytes /= 1000;
            unit++;
        }
        return bytes.ToString(unit == 0 ? "0" : bytes < 100 ? "0.0" : "0", Vietnamese) + " " + units[unit];
    }

    public static string Speed(double bytesPerSecond) => Bytes(bytesPerSecond) + "/s";
}

public sealed class DeviceViewModel(DeviceRecord record) : Observable
{
    private bool _isOnline;
    private bool _isTarget;
    private int _waiting;

    public DeviceRecord Record { get; } = record;
    public string Id => Record.DeviceId;
    public string Name => Record.Name;
    public string Glyph => Record.Platform == "ios" ? "" : "";
    public bool IsOnline { get => _isOnline; set => Set(ref _isOnline, value); }
    public bool IsTarget { get => _isTarget; set => Set(ref _isTarget, value); }
    public int Waiting { get => _waiting; set => Set(ref _waiting, value); }
}

public sealed class RootViewModel(RootFolder root)
{
    public RootFolder Root { get; } = root;
    public string Name => Root.Name;
    public string Path => Root.Path;
}

public sealed class TransferViewModel(string key, string name, string deviceName, bool toPc, long size) : Observable
{
    private double _percent;
    private string _detail = "";
    private string _speed = "";
    private bool _isWaiting;
    private bool _isDone;
    private bool _isInterrupted;

    public string Key { get; } = key;
    public string Name { get; } = name;
    public string Route { get; } = toPc ? $"{deviceName} → PC" : $"PC → {deviceName}";
    public string Glyph { get; } = toPc ? "" : "";
    public long Size { get; } = size;
    public string? OutboxId { get; init; }
    public string? OutboxDeviceId { get; init; }

    public double Percent { get => _percent; set => Set(ref _percent, value); }
    public string Detail { get => _detail; set => Set(ref _detail, value); }
    public string Speed { get => _speed; set => Set(ref _speed, value); }
    public bool IsWaiting { get => _isWaiting; set => Set(ref _isWaiting, value); }
    public bool IsDone { get => _isDone; set => Set(ref _isDone, value); }
    public bool IsInterrupted { get => _isInterrupted; set => Set(ref _isInterrupted, value); }
}

public sealed class MainViewModel : Observable
{
    private readonly ShareServer _server;
    private readonly MdnsAdvertiser _mdns;
    private readonly AppPreferences _preferences;
    private readonly DispatcherTimer _timer;
    private PairingTicket? _ticket;
    private string _pairLink = "";
    private const int QrMaxSize = 256;

    private BitmapImage? _qrImage;
    private int _qrSize;
    private string _qrCountdown = "";
    private string _addressText = "";
    private string _statusText = "";
    private bool _isHealthy;
    private bool _firewallNeeded;
    private string _totalSpeed = "";
    private string _hint = "";

    public ObservableCollection<DeviceViewModel> Devices { get; } = [];
    public ObservableCollection<TransferViewModel> Transfers { get; } = [];
    public ObservableCollection<RootViewModel> Roots { get; } = [];

    public BitmapImage? QrImage { get => _qrImage; private set => Set(ref _qrImage, value); }
    public int QrSize { get => _qrSize; private set => Set(ref _qrSize, value); }
    public string QrCountdown { get => _qrCountdown; private set => Set(ref _qrCountdown, value); }
    public string AddressText { get => _addressText; private set => Set(ref _addressText, value); }
    public string StatusText { get => _statusText; private set => Set(ref _statusText, value); }
    public bool IsHealthy { get => _isHealthy; private set => Set(ref _isHealthy, value); }
    public bool FirewallNeeded { get => _firewallNeeded; private set => Set(ref _firewallNeeded, value); }
    public string TotalSpeed { get => _totalSpeed; private set => Set(ref _totalSpeed, value); }
    public string Hint { get => _hint; private set => Set(ref _hint, value); }
    public string InboxPath => _server.Config.Current.InboxDirectory;

    public bool AutoStartEnabled
    {
        get => AutoStart.IsEnabled;
        set => AutoStart.IsEnabled = value;
    }

    public MainViewModel(ShareServer server, MdnsAdvertiser mdns, AppPreferences preferences)
    {
        _server = server;
        _mdns = mdns;
        _preferences = preferences;

        _server.Devices.Changed += () => Application.Current.Dispatcher.InvokeAsync(OnDevicesChanged);
        RefreshDevices();
        RefreshRoots();
        RefreshAddress();
        NewPairingCode();

        _timer = new DispatcherTimer(TimeSpan.FromMilliseconds(500), DispatcherPriority.Background, (_, _) => Tick(),
            Application.Current.Dispatcher);
    }

    public void RefreshAddress()
    {
        var address = LanAddress.Find();
        if (address is null)
        {
            IsHealthy = false;
            AddressText = "Chưa có mạng";
            StatusText = "Không tìm thấy mạng LAN";
        }
        else
        {
            IsHealthy = _mdns.Error is null;
            AddressText = $"{address.Address} · {address.InterfaceName}";
            StatusText = _mdns.Error ?? "Đang chạy, sẵn sàng nhận file";
        }
        NewPairingCode();
    }

    public void NewPairingCode()
    {
        _ticket = _server.Pairing.CreateTicket();
        var addresses = LanAddress.Find() is { } lan ? new[] { lan.Address.ToString() } : [];
        _pairLink = _server.BuildPairLink(_ticket, addresses);

        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(_pairLink, QRCodeGenerator.ECCLevel.L);

        // A whole number of pixels per module keeps every module the same size on screen. Uneven
        // modules are what makes a camera struggle. The white card around it is the quiet zone.
        var modules = data.ModuleMatrix.Count - 8;
        var pixelsPerModule = Math.Max(2, QrMaxSize / modules);
        QrSize = modules * pixelsPerModule;
        var png = new PngByteQRCode(data).GetGraphic(pixelsPerModule, drawQuietZones: false);
        var image = new BitmapImage();
        using (var stream = new MemoryStream(png))
        {
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
        }
        image.Freeze();
        QrImage = image;
        UpdateCountdown();
    }

    public void CopyPairLink() => Clipboard.SetText(_pairLink);

    public void EnsureFirewallOnFirstRun()
    {
        var isConfigured = FirewallSetup.IsConfigured();
        if (!_preferences.FirstRunDone)
        {
            _preferences.FirstRunDone = true;
#if !DEBUG
            AutoStart.IsEnabled = true;
            Raise(nameof(AutoStartEnabled));
#endif
            if (!isConfigured) isConfigured = FirewallSetup.Configure();
        }
        FirewallNeeded = !isConfigured;
    }

    public void OpenFirewall() => FirewallNeeded = !FirewallSetup.Configure();

    public void SendFiles(IEnumerable<string> paths)
    {
        var target = Devices.FirstOrDefault(d => d.IsTarget);
        if (target is null)
        {
            Hint = "Chưa có thiết bị nào. Quét mã QR trên điện thoại trước đã.";
            return;
        }

        var files = paths.SelectMany(ExpandFiles).ToList();
        foreach (var file in files) _server.Outbox.Enqueue(target.Id, file);
        Hint = files.Count == 0
            ? "Không có file nào để gửi."
            : target.IsOnline
                ? $"Đang gửi {files.Count} file sang {target.Name}."
                : $"Đã xếp {files.Count} file. Mở app trên {target.Name} để nhận.";
        Tick();
    }

    public void CancelWaiting(TransferViewModel transfer)
    {
        if (transfer.OutboxId is not null && transfer.OutboxDeviceId is not null)
        {
            _server.Outbox.Remove(transfer.OutboxDeviceId, transfer.OutboxId);
            Tick();
        }
    }

    public void Revoke(DeviceViewModel device) => _server.RevokeDevice(device.Id);

    public void SelectTarget(DeviceViewModel device)
    {
        foreach (var candidate in Devices) candidate.IsTarget = candidate == device;
    }

    public void SetInbox(string path)
    {
        _server.Config.Update(c => c with { InboxDirectory = path });
        Raise(nameof(InboxPath));
    }

    public void AddRoot(string path)
    {
        _server.Config.Update(c => c.Roots.Any(r => string.Equals(r.Path, path, StringComparison.OrdinalIgnoreCase))
            ? c
            : c with { Roots = [.. c.Roots, ConfigStore.NewRoot(path)] });
        RefreshRoots();
    }

    public void RemoveRoot(RootViewModel root)
    {
        _server.Config.Update(c => c with { Roots = c.Roots.Where(r => r.Id != root.Root.Id).ToList() });
        RefreshRoots();
    }

    private void OnDevicesChanged()
    {
        var hadCount = Devices.Count;
        RefreshDevices();
        // The code on screen was just used, so show a fresh one for the next device.
        if (Devices.Count >= hadCount) NewPairingCode();
    }

    private void RefreshDevices()
    {
        var records = _server.Devices.All;
        var targetId = Devices.FirstOrDefault(d => d.IsTarget)?.Id;
        Devices.Clear();
        foreach (var record in records)
        {
            Devices.Add(new DeviceViewModel(record)
            {
                IsOnline = _server.Devices.IsOnline(record),
                IsTarget = record.DeviceId == targetId,
            });
        }
        if (Devices.Count > 0 && !Devices.Any(d => d.IsTarget))
        {
            (Devices.FirstOrDefault(d => d.IsOnline) ?? Devices[0]).IsTarget = true;
        }
    }

    private void RefreshRoots()
    {
        Roots.Clear();
        foreach (var root in _server.Config.Current.Roots) Roots.Add(new RootViewModel(root));
    }

    private void Tick()
    {
        foreach (var device in Devices)
        {
            device.IsOnline = _server.Devices.IsOnline(device.Record);
            device.Waiting = _server.Outbox.ListFor(device.Id).Count;
        }

        if (_ticket is not null && _ticket.ExpiresAt <= DateTimeOffset.UtcNow) NewPairingCode();
        UpdateCountdown();
        UpdateTransfers();
    }

    private void UpdateCountdown()
    {
        if (_ticket is null) return;
        var left = _ticket.ExpiresAt - DateTimeOffset.UtcNow;
        QrCountdown = left > TimeSpan.Zero ? $"Mã đổi sau {left:m\\:ss}" : "";
    }

    private void UpdateTransfers()
    {
        var entries = _server.Monitor.Snapshot();
        var wanted = new List<TransferViewModel>();
        var active = entries.Where(e => e.State == TransferState.Active).ToList();

        foreach (var device in Devices)
        {
            foreach (var item in _server.Outbox.ListFor(device.Id))
            {
                if (active.Any(e => e.DeviceName == device.Name && e.Name == item.Name)) continue;

                var key = "wait:" + item.Id;
                var row = Transfers.FirstOrDefault(t => t.Key == key)
                          ?? new TransferViewModel(key, item.Name, device.Name, false, item.Size)
                          {
                              OutboxId = item.Id,
                              OutboxDeviceId = device.Id,
                              IsWaiting = true,
                          };
                row.Detail = $"{Format.Bytes(item.Size)} · đang chờ máy nhận";
                wanted.Add(row);
            }
        }

        double total = 0;
        foreach (var entry in entries)
        {
            var row = Transfers.FirstOrDefault(t => t.Key == entry.Key)
                      ?? new TransferViewModel(entry.Key, entry.Name, entry.DeviceName, entry.Direction == TransferDirection.ToPc, entry.Size);
            var transferred = entry.Transferred;
            row.Percent = entry.Size > 0 ? 100.0 * transferred / entry.Size : 100;
            row.IsDone = entry.State == TransferState.Done;
            row.IsInterrupted = entry.State == TransferState.Interrupted;
            row.Speed = entry.State == TransferState.Active ? Format.Speed(entry.BytesPerSecond) : "";
            row.Detail = entry.State switch
            {
                TransferState.Done => $"{Format.Bytes(entry.Size)} · xong",
                TransferState.Interrupted => $"{Format.Bytes(transferred)} / {Format.Bytes(entry.Size)} · tạm dừng",
                _ => $"{Format.Bytes(transferred)} / {Format.Bytes(entry.Size)}",
            };
            if (entry.State == TransferState.Active) total += entry.BytesPerSecond;
            wanted.Add(row);
        }

        TotalSpeed = total > 0 ? Format.Speed(total) : "";
        Sync(wanted);
    }

    /// <summary>Updates the list in place, so rows and their animations are not rebuilt twice a second.</summary>
    private void Sync(List<TransferViewModel> wanted)
    {
        for (var i = Transfers.Count - 1; i >= 0; i--)
        {
            if (!wanted.Contains(Transfers[i])) Transfers.RemoveAt(i);
        }
        for (var i = 0; i < wanted.Count; i++)
        {
            var current = Transfers.IndexOf(wanted[i]);
            if (current < 0) Transfers.Insert(i, wanted[i]);
            else if (current != i) Transfers.Move(current, i);
        }
    }

    private static IEnumerable<string> ExpandFiles(string path)
    {
        if (File.Exists(path)) return [path];
        if (!Directory.Exists(path)) return [];
        return Directory.EnumerateFiles(path, "*", new EnumerationOptions
        {
            RecurseSubdirectories = true,
            IgnoreInaccessible = true,
            AttributesToSkip = FileAttributes.Hidden | FileAttributes.System | FileAttributes.ReparsePoint,
        });
    }

}
