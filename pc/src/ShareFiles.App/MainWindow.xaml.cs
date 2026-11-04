using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using Microsoft.Win32;

namespace ShareFiles.App;

public static class Converters
{
    public static readonly IValueConverter ZeroToVisible = new ZeroToVisibleConverter();

    private sealed class ZeroToVisibleConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture) =>
            value is 0 ? Visibility.Visible : Visibility.Collapsed;

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
            throw new NotSupportedException();
    }
}

public partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel;
    private readonly Brush _dropBorder;

    public MainWindow(MainViewModel viewModel)
    {
        InitializeComponent();
        _viewModel = viewModel;
        DataContext = viewModel;
        _dropBorder = DropZone.BorderBrush;

        DragEnter += OnDragEnter;
        DragOver += OnDragEnter;
        DragLeave += (_, _) => SetDropHighlight(false);
        Drop += OnDrop;
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        // Closing only hides the window; the server keeps running in the tray.
        e.Cancel = true;
        Hide();
    }

    private void OnMinimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void OnHide(object sender, RoutedEventArgs e) => Hide();
    private void OnOpenFirewall(object sender, RoutedEventArgs e) => _viewModel.OpenFirewall();
    private void OnCopyLink(object sender, RoutedEventArgs e) => _viewModel.CopyPairLink();
    private void OnNewCode(object sender, RoutedEventArgs e) => _viewModel.NewPairingCode();

    private void OnRevoke(object sender, RoutedEventArgs e)
    {
        if (ItemOf<DeviceViewModel>(sender) is not { } device) return;
        var answer = MessageBox.Show(this, $"Huỷ ghép đôi {device.Name}?", "Share Files",
            MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (answer == MessageBoxResult.Yes) _viewModel.Revoke(device);
    }

    private void OnSelectTarget(object sender, RoutedEventArgs e)
    {
        if (ItemOf<DeviceViewModel>(sender) is { } device) _viewModel.SelectTarget(device);
    }

    private void OnPickFiles(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Multiselect = true, Title = "Chọn file để gửi" };
        if (dialog.ShowDialog(this) == true) _viewModel.SendFiles(dialog.FileNames);
    }

    private void OnCancelWaiting(object sender, RoutedEventArgs e)
    {
        if (ItemOf<TransferViewModel>(sender) is { } transfer) _viewModel.CancelWaiting(transfer);
    }

    private void OnOpenInbox(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(_viewModel.InboxPath);
        Process.Start(new ProcessStartInfo(_viewModel.InboxPath) { UseShellExecute = true })?.Dispose();
    }

    private void OnChangeInbox(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "Chọn thư mục nhận file" };
        if (dialog.ShowDialog(this) == true) _viewModel.SetInbox(dialog.FolderName);
    }

    private void OnAddRoot(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "Chọn thư mục cho điện thoại lấy file" };
        if (dialog.ShowDialog(this) == true) _viewModel.AddRoot(dialog.FolderName);
    }

    private void OnRemoveRoot(object sender, RoutedEventArgs e)
    {
        if (ItemOf<RootViewModel>(sender) is { } root) _viewModel.RemoveRoot(root);
    }

    private void OnDragEnter(object sender, DragEventArgs e)
    {
        var hasFiles = e.Data.GetDataPresent(DataFormats.FileDrop);
        e.Effects = hasFiles ? DragDropEffects.Copy : DragDropEffects.None;
        SetDropHighlight(hasFiles);
        e.Handled = true;
    }

    private void OnDrop(object sender, DragEventArgs e)
    {
        SetDropHighlight(false);
        if (e.Data.GetData(DataFormats.FileDrop) is string[] paths) _viewModel.SendFiles(paths);
    }

    private void SetDropHighlight(bool on) =>
        DropZone.BorderBrush = on ? (Brush)FindResource("AccentBrush") : _dropBorder;

    private static T? ItemOf<T>(object sender) where T : class =>
        (sender as FrameworkElement)?.DataContext as T;
}
