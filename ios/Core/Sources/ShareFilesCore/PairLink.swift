import Foundation

/// The contents of the pairing QR code. See protocol/PROTOCOL.md.
struct PairLink: Equatable {
    let serverId: String
    let serverName: String
    let fingerprint: String
    let secret: String
    let port: Int
    let addresses: [String]

    static func parse(_ text: String) -> PairLink? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: value),
              components.scheme == "sharefiles",
              components.host == "pair",
              let items = components.queryItems else { return nil }

        func field(_ name: String) -> String? {
            guard let found = items.first(where: { $0.name == name })?.value, !found.isEmpty else { return nil }
            return found
        }

        guard let id = field("id"),
              let fingerprint = field("fp"), isFingerprint(fingerprint),
              let secret = field("secret"),
              let portText = field("port"), let port = Int(portText), (1...65535).contains(port)
        else { return nil }

        let addresses = (field("addrs") ?? "")
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        return PairLink(
            serverId: id,
            serverName: field("name") ?? "PC",
            fingerprint: fingerprint,
            secret: secret,
            port: port,
            addresses: addresses
        )
    }

    private static func isFingerprint(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}
