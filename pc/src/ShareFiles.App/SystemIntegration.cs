using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using Microsoft.Win32;
using ShareFiles.Server;

namespace ShareFiles.App;

/// <summary>Opens the server port for the local subnet only. The rule is independent of the network profile.</summary>
public static class FirewallSetup
{
    private const string RuleName = "Share Files";

    public static bool IsConfigured()
    {
        var info = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{RuleName}\"")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
        };
        using var process = Process.Start(info)!;
        process.StandardOutput.ReadToEnd();
        process.WaitForExit();
        return process.ExitCode == 0;
    }

    /// <summary>Adds the rule through a UAC prompt. Returns false if the user declines it.</summary>
    public static bool Configure()
    {
        var info = new ProcessStartInfo("netsh",
            $"advfirewall firewall add rule name=\"{RuleName}\" dir=in action=allow protocol=TCP " +
            $"localport={ServerOptions.DefaultPort} remoteip=LocalSubnet profile=any")
        {
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        try
        {
            using var process = Process.Start(info)!;
            process.WaitForExit();
            return process.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            return false;
        }
    }
}

public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "ShareFiles";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is not null;
        }
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value)
            {
                key.SetValue(ValueName, $"\"{Environment.ProcessPath}\" --background");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
    }
}

/// <summary>Small per-install flags that are not part of the server's configuration.</summary>
public sealed class AppPreferences(string dataDirectory)
{
    private sealed record Stored(bool FirstRunDone);

    private readonly string _path = Path.Combine(dataDirectory, "app.json");

    public bool FirstRunDone
    {
        get
        {
            if (!File.Exists(_path)) return false;
            return JsonSerializer.Deserialize<Stored>(File.ReadAllText(_path))?.FirstRunDone ?? false;
        }
        set => File.WriteAllText(_path, JsonSerializer.Serialize(new Stored(value)));
    }
}
