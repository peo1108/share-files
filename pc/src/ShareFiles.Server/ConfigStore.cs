namespace ShareFiles.Server;

public sealed record RootFolder(string Id, string Name, string Path);

public sealed record ServerConfig(
    string ServerId,
    string ServerName,
    string InboxDirectory,
    IReadOnlyList<RootFolder> Roots);

public sealed class ConfigStore
{
    private readonly string _path;
    private readonly object _lock = new();
    private ServerConfig _current;

    public event Action? Changed;

    public ConfigStore(ServerOptions options)
    {
        _path = Path.Combine(options.DataDirectory, "config.json");
        var loaded = JsonFile.Read<ServerConfig>(_path);
        if (loaded is null)
        {
            loaded = new ServerConfig(
                Guid.NewGuid().ToString("N"),
                Environment.MachineName,
                options.DefaultInbox,
                [NewRoot(options.DefaultRoot)]);
            JsonFile.Write(_path, loaded);
        }
        _current = loaded;
    }

    public ServerConfig Current
    {
        get { lock (_lock) return _current; }
    }

    public void Update(Func<ServerConfig, ServerConfig> change)
    {
        lock (_lock)
        {
            _current = change(_current);
            JsonFile.Write(_path, _current);
        }
        Changed?.Invoke();
    }

    public static RootFolder NewRoot(string path)
    {
        var name = Path.GetFileName(Path.TrimEndingDirectorySeparator(path));
        return new RootFolder(Guid.NewGuid().ToString("N"), name.Length > 0 ? name : path, path);
    }
}
