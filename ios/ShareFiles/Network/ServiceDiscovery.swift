import Foundation
import Network

/// Finds PCs on the network over mDNS. [onFound] gets a server id and its IPv4 address.
final class ServiceDiscovery {
    private let queue = DispatchQueue(label: "sharefiles.discovery")
    private var browser: NWBrowser?
    private var resolving: [NWEndpoint: NWConnection] = [:]
    private let onFound: (String, String) -> Void

    init(onFound: @escaping (String, String) -> Void) {
        self.onFound = onFound
    }

    func start() {
        queue.async { [self] in
            guard browser == nil else { return }
            let parameters = NWParameters()
            parameters.includePeerToPeer = false

            let next = NWBrowser(for: .bonjourWithTXTRecord(type: "_sharefiles._tcp", domain: nil), using: parameters)
            next.browseResultsChangedHandler = { [weak self] results, _ in
                results.forEach { self?.resolve($0) }
            }
            next.stateUpdateHandler = { state in
                if case .failed(let error) = state {
                    AppLog.shared.add("Không tìm được PC qua mDNS: \(error)")
                }
            }
            next.start(queue: queue)
            browser = next
        }
    }

    func stop() {
        queue.async { [self] in
            browser?.cancel()
            browser = nil
            resolving.values.forEach { $0.cancel() }
            resolving.removeAll()
        }
    }

    /// Bonjour gives a service name. Opening a short connection is how it turns into an address.
    private func resolve(_ result: NWBrowser.Result) {
        guard case .bonjour(let txt) = result.metadata, let id = txt.dictionary["id"], resolving[result.endpoint] == nil else {
            return
        }

        let parameters = NWParameters.tcp
        if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        let endpoint = result.endpoint
        let connection = NWConnection(to: endpoint, using: parameters)
        resolving[endpoint] = connection

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                if case .hostPort(let host, _)? = connection.currentPath?.remoteEndpoint,
                   case .ipv4(let address) = host {
                    let text = "\(address)".components(separatedBy: "%")[0]
                    self?.onFound(id, text)
                }
                connection.cancel()
            case .failed, .cancelled:
                self?.queue.async { self?.resolving[endpoint] = nil }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
}
