using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using ShareFiles.Server;

namespace ShareFiles.App;

/// <summary>The IPv4 address other devices should use: the adapter that has a default gateway.</summary>
public sealed record LanAddress(IPAddress Address, int InterfaceIndex, string InterfaceName)
{
    public static LanAddress? Find() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                        && n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                        && n.GetIPProperties().GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork))
            .OrderByDescending(n => n.Speed)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(a => new LanAddress(a.Address, n.GetIPProperties().GetIPv4Properties().Index, n.Name)))
            .FirstOrDefault();
}

/// <summary>Advertises the service through the mDNS responder built into Windows.</summary>
public sealed class MdnsAdvertiser : IDisposable
{
    private const uint RequestVersion = 1;
    private const uint RequestPending = 9506;

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void RegisterComplete(uint status, IntPtr context, IntPtr instance);

    [StructLayout(LayoutKind.Sequential)]
    private struct RegisterRequest
    {
        public uint Version;
        public uint InterfaceIndex;
        public IntPtr ServiceInstance;
        public IntPtr CompletionCallback;
        public IntPtr QueryContext;
        public IntPtr Credentials;
        [MarshalAs(UnmanagedType.Bool)] public bool UnicastEnabled;
    }

    [DllImport("dnsapi.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DnsServiceConstructInstance(
        string serviceName, string hostName, ref uint ip4, IntPtr ip6,
        ushort port, ushort priority, ushort weight, uint propertyCount,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] keys,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] values);

    [DllImport("dnsapi.dll")]
    private static extern uint DnsServiceRegister(ref RegisterRequest request, IntPtr cancel);

    [DllImport("dnsapi.dll")]
    private static extern uint DnsServiceDeRegister(ref RegisterRequest request, IntPtr cancel);

    // Windows keeps calling this after registering, so it must outlive every request.
    private readonly RegisterComplete _callback = (_, _, _) => { };

    private readonly object _lock = new();
    private RegisterRequest _request;
    private bool _registered;

    public string? Error { get; private set; }

    public void Register(string serverName, string serverId, int port, LanAddress address)
    {
        lock (_lock)
        {
            Unregister();

            var host = Sanitize(Environment.MachineName) + ".local";
            var service = $"{Sanitize(serverName)}.{ServerOptions.ServiceType}.local";
#pragma warning disable CS0618 // Address carries the network-order IPv4 value the API wants.
            var ip4 = (uint)address.Address.Address;
#pragma warning restore CS0618

            var instance = DnsServiceConstructInstance(
                service, host, ref ip4, IntPtr.Zero, (ushort)port, 0, 0, 2,
                ["id", "v"], [serverId, "1"]);
            if (instance == IntPtr.Zero)
            {
                Error = "Không tạo được bản ghi mDNS";
                return;
            }

            _request = new RegisterRequest
            {
                Version = RequestVersion,
                InterfaceIndex = (uint)address.InterfaceIndex,
                ServiceInstance = instance,
                CompletionCallback = Marshal.GetFunctionPointerForDelegate(_callback),
            };

            // The instance is deliberately never freed. DnsServiceDeRegister reads it again later and
            // completes asynchronously, so there is no safe moment to free it. It is a few hundred
            // bytes per network change, which beats a use-after-free in the OS API.
            var result = DnsServiceRegister(ref _request, IntPtr.Zero);
            _registered = result == RequestPending;
            Error = _registered ? null : $"Không quảng bá được mDNS (mã {result})";
        }
    }

    public void Unregister()
    {
        lock (_lock)
        {
            if (!_registered) return;
            _registered = false;
            DnsServiceDeRegister(ref _request, IntPtr.Zero);
        }
    }

    public void Dispose() => Unregister();

    private static string Sanitize(string name)
    {
        var clean = new string(name.Select(c => c is '.' or '\\' ? '-' : c).ToArray());
        return clean.Length > 0 ? clean : "PC";
    }
}
