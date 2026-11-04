import Foundation

struct ServerInfo: Codable, Equatable {
    let id: String
    let name: String
    let apiVersion: Int
}

struct PairResponse: Codable, Equatable {
    let token: String
    let serverId: String
    let serverName: String
}

struct RootFolder: Codable, Equatable, Identifiable {
    let id: String
    let name: String
}

struct BrowseEntry: Codable, Equatable, Identifiable {
    let name: String
    let isDirectory: Bool
    let size: Int64
    let modifiedAt: String

    var id: String { name }
}

struct BrowseResponse: Codable, Equatable {
    let root: String
    let path: String
    let entries: [BrowseEntry]
}

struct UploadStatus: Codable, Equatable {
    let id: String
    let offset: Int64
    let completed: Bool
    let savedName: String?
}

struct OutboxEntry: Codable, Equatable, Identifiable {
    let id: String
    let name: String
    let size: Int64
    let modifiedAt: String
    let sourceMissing: Bool
}

struct ApiErrorBody: Codable, Equatable {
    let code: String?
    let message: String?
    let offset: Int64?
}

/// A paired PC. Everything needed to find it again and prove who it is.
struct PairedServer: Codable, Equatable {
    let serverId: String
    let serverName: String
    let fingerprint: String
    let token: String
    let port: Int
    let addresses: [String]
    var lastHost: String?
}

/// The server answered with an error from the protocol's error table.
struct ApiFailure: Error, LocalizedError {
    let status: Int
    let code: String
    let message: String
    let offset: Int64?

    var errorDescription: String? { message }
}
