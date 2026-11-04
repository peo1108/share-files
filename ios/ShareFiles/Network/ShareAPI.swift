import CryptoKit
import Foundation
import Security

/// Trusts exactly one certificate. There is no CA or hostname check because the fingerprint from
/// the pairing QR code is a stronger proof of identity on a home network.
final class PinnedSessionDelegate: NSObject, URLSessionDelegate {
    private let fingerprint: String

    init(fingerprint: String) {
        self.fingerprint = fingerprint
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        let der = SecCertificateCopyData(leaf) as Data
        let actual = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        if actual == fingerprint {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            AppLog.shared.add("Chứng chỉ không khớp với PC đã ghép đôi")
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

enum PinnedSession {
    static func make(fingerprint: String) -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.allowsCellularAccess = false
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 7 * 24 * 60 * 60
        configuration.httpMaximumConnectionsPerHost = 8
        return URLSession(
            configuration: configuration,
            delegate: PinnedSessionDelegate(fingerprint: fingerprint),
            delegateQueue: nil
        )
    }
}

final class ShareAPI {
    let session: URLSession
    let baseURL: URL
    private let token: String?

    init(session: URLSession, host: String, port: Int, token: String?) {
        self.session = session
        self.token = token
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.port = port
        // The address comes from the QR code or from mDNS. An unusable one is a connection failure.
        self.baseURL = components.url ?? URL(string: "https://invalid.invalid")!
    }

    func info(timeout: TimeInterval = 4) async throws -> ServerInfo {
        var request = makeRequest("/v1/info")
        request.timeoutInterval = timeout
        return try await sendJSON(request)
    }

    func pair(secret: String, deviceId: String, deviceName: String) async throws -> PairResponse {
        struct Body: Encodable {
            let secret: String
            let deviceId: String
            let deviceName: String
            let platform: String
        }
        return try await post("/v1/pair", Body(secret: secret, deviceId: deviceId, deviceName: deviceName, platform: "ios"))
    }

    func roots() async throws -> [RootFolder] {
        try await sendJSON(makeRequest("/v1/roots"))
    }

    func browse(root: String, path: String) async throws -> BrowseResponse {
        try await sendJSON(makeRequest("/v1/browse", query: ["root": root, "path": path]))
    }

    func outbox() async throws -> [OutboxEntry] {
        var request = makeRequest("/v1/outbox")
        request.timeoutInterval = 8
        return try await sendJSON(request)
    }

    func ackOutbox(_ id: String) async throws {
        var request = makeRequest("/v1/outbox/\(id)/ack")
        request.httpMethod = "POST"
        try await sendEmpty(request)
    }

    func rejectOutbox(_ id: String) async throws {
        var request = makeRequest("/v1/outbox/\(id)")
        request.httpMethod = "DELETE"
        try await sendEmpty(request)
    }

    func beginUpload(fileKey: String, name: String, size: Int64, modifiedAt: String?) async throws -> UploadStatus {
        struct Body: Encodable {
            let fileKey: String
            let name: String
            let size: Int64
            let modifiedAt: String?
        }
        return try await post("/v1/uploads", Body(fileKey: fileKey, name: name, size: size, modifiedAt: modifiedAt))
    }

    func deleteUpload(_ id: String) async throws {
        var request = makeRequest("/v1/uploads/\(id)")
        request.httpMethod = "DELETE"
        try await sendEmpty(request)
    }

    func makeRequest(_ path: String, query: [String: String] = [:]) -> URLRequest {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)!
        components.path = path
        if !query.isEmpty {
            components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        var request = URLRequest(url: components.url!)
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return request
    }

    /// Builds a request from a path that already carries its query string, as stored by a transfer.
    func makeRequest(rawPath: String) -> URLRequest {
        var request = URLRequest(url: URL(string: rawPath, relativeTo: baseURL)!.absoluteURL)
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return request
    }

    static func failure(status: Int, body: Data) -> ApiFailure {
        let decoded = try? JSONDecoder().decode(ApiErrorBody.self, from: body)
        return ApiFailure(
            status: status,
            code: decoded?.code ?? "",
            message: decoded?.message ?? "Lỗi \(status)",
            offset: decoded?.offset
        )
    }

    private func post<B: Encodable, T: Decodable>(_ path: String, _ body: B) async throws -> T {
        var request = makeRequest(path)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        return try await sendJSON(request)
    }

    private func sendJSON<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await sendChecked(request)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func sendEmpty(_ request: URLRequest) async throws {
        _ = try await sendChecked(request)
    }

    private func sendChecked(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw Self.failure(status: status, body: data) }
        return data
    }
}
