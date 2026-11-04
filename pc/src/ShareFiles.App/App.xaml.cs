using System.IO;
using System.Net.NetworkInformation;
using System.Windows;
using ShareFiles.Server;

namespace ShareFiles.App;

public partial class App : Application
{
    private const string InstanceName = "ShareFiles.SingleInstance.v1";
    private const string ShowEventName = "ShareFiles.ShowWindow.v1";

    private Mutex? _instance;
    private EventWaitHandle? _showEvent;
    private ShareServer? _server;
    private MdnsAdvertiser? _mdns;
    private TrayIcon? _tray;
    private MainWindow? _window;
    private MainViewModel? _viewModel;
    private CancellationTokenSource? _republish;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _instance = new Mutex(true, InstanceName, out var isFirst);
        if (!isFirst)
        {
            // Already running: bring that window up instead.
            if (EventWaitHandle.TryOpenExisting(ShowEventName, out var existing))
            {
                existing.Set();
                existing.Dispose();
            }
            Shutdown();
            return;
        }

        try
        {
            await StartServicesAsync(startHidden: e.Args.Contains("--background"));
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Không khởi động được Share Files.\n\n{ex.Message}", "Share Files",
                MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown();
        }
    }

    private async Task StartServicesAsync(bool startHidden)
    {
        var dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ShareFiles");

        _server = new ShareServer(new ServerOptions { DataDirectory = dataDirectory });
        await _server.StartAsync();

        _mdns = new MdnsAdvertiser();
        Republish();
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;

        _viewModel = new MainViewModel(_server, _mdns, new AppPreferences(dataDirectory));
        _window = new MainWindow(_viewModel);
        _tray = new TrayIcon(ShowWindow, ExitApplication);

        ListenForSecondInstance();
        if (!startHidden) ShowWindow();
        _viewModel.EnsureFirewallOnFirstRun();
    }

    private void OnNetworkChanged(object? sender, EventArgs e)
    {
        // A network change arrives as a burst of events; act once it settles.
        var next = new CancellationTokenSource();
        Interlocked.Exchange(ref _republish, next)?.Cancel();
        _ = Task.Delay(TimeSpan.FromSeconds(2), next.Token).ContinueWith(t =>
        {
            if (t.IsCanceled) return;
            Republish();
            Dispatcher.InvokeAsync(() => _viewModel?.RefreshAddress());
        }, TaskScheduler.Default);
    }

    private void Republish()
    {
        if (_server is null || _mdns is null) return;
        if (LanAddress.Find() is { } address)
        {
            _mdns.Register(_server.Config.Current.ServerName, _server.Config.Current.ServerId, _server.BoundPort, address);
        }
        else
        {
            _mdns.Unregister();
        }
    }

    private void ListenForSecondInstance()
    {
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        var thread = new Thread(() =>
        {
            while (_showEvent.WaitOne())
            {
                Dispatcher.InvokeAsync(ShowWindow);
            }
        })
        { IsBackground = true };
        thread.Start();
    }

    private void ShowWindow()
    {
        if (_window is null) return;
        _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
    }

    private async void ExitApplication()
    {
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        _tray?.Dispose();
        _mdns?.Dispose();
        if (_server is not null) await _server.StopAsync();
        Shutdown();
    }
}
