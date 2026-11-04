import Foundation

/// Folder layout inside the app's container. Only relative names are ever stored, because the
/// container's absolute path changes when the app is re-signed and reinstalled.
enum Storage {
    private static var support: URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Files picked for sending, copied here so they stay readable if the transfer has to resume.
    static var outbox: URL { folder(support.appendingPathComponent("Outbox", isDirectory: true)) }

    static var partial: URL { folder(support.appendingPathComponent("Partial", isDirectory: true)) }

    /// Visible in the Files app.
    static var received: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return folder(documents.appendingPathComponent("Received", isDirectory: true))
    }

    static var transfersFile: URL { support.appendingPathComponent("transfers.json") }

    static var pairedFile: URL { support.appendingPathComponent("paired.json") }

    static var deviceIdFile: URL { support.appendingPathComponent("device-id") }

    static func deviceId() -> String {
        if let stored = try? String(contentsOf: deviceIdFile, encoding: .utf8), !stored.isEmpty { return stored }
        let created = UUID().uuidString
        try? created.write(to: deviceIdFile, atomically: true, encoding: .utf8)
        return created
    }

    static func read<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    static func write<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private static func folder(_ url: URL) -> URL {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
