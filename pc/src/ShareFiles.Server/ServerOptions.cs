using System.Net;

namespace ShareFiles.Server;

public sealed class ServerOptions
{
    public const int DefaultPort = 48920;
    public const string ServiceType = "_sharefiles._tcp";

    public required string DataDirectory { get; init; }
    public int Port { get; init; } = DefaultPort;
    public IPAddress BindAddress { get; init; } = IPAddress.Any;
    public string DefaultInbox { get; init; } = Path.Combine(UserDownloads, "ShareFiles");
    public string DefaultRoot { get; init; } = UserDownloads;
    public TimeProvider TimeProvider { get; init; } = TimeProvider.System;

    private static string UserDownloads =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
}
