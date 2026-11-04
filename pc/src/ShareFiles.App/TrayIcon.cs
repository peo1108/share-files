using System.Reflection;

namespace ShareFiles.App;

public sealed class TrayIcon : IDisposable
{
    private readonly System.Windows.Forms.NotifyIcon _icon;
    private readonly System.Drawing.Icon _image;

    public TrayIcon(Action show, Action exit)
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("app.ico")!;
        _image = new System.Drawing.Icon(stream);

        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Mở Share Files", null, (_, _) => show());
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Thoát", null, (_, _) => exit());

        _icon = new System.Windows.Forms.NotifyIcon
        {
            Icon = _image,
            Text = "Share Files",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => show();
        _icon.MouseClick += (_, e) =>
        {
            if (e.Button == System.Windows.Forms.MouseButtons.Left) show();
        };
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
        _image.Dispose();
    }
}
