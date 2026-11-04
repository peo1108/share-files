import Foundation

/// Measures the network path with data held in memory, so storage speed plays no part.
final class SpeedTest {
    private static let duration = 7.0
    private static let rampUp = 1.0
    private static let maxBytes: Int64 = 4 * 1024 * 1024 * 1024
    private static let uploadChunk = 128 * 1024 * 1024

    private let api: ShareAPI

    init(api: ShareAPI) {
        self.api = api
    }

    /// Returns bytes per second. [onSpeed] gets live readings on an arbitrary thread.
    func download(onSpeed: @escaping (Double) -> Void) async throws -> Double {
        let request = api.makeRequest("/v1/speedtest/download", query: ["bytes": String(Self.maxBytes)])
        let probe = Probe(limit: Self.duration, rampUp: Self.rampUp, onSpeed: onSpeed)
        try await probe.runDownload(session: api.session, request: request)
        return probe.average
    }

    func upload(onSpeed: @escaping (Double) -> Void) async throws -> Double {
        let probe = Probe(limit: Self.duration, rampUp: Self.rampUp, onSpeed: onSpeed)
        let payload = Data(count: Self.uploadChunk)
        while !probe.finished {
            try Task.checkCancellation()
            var request = api.makeRequest("/v1/speedtest/upload")
            request.httpMethod = "POST"
            try await probe.runUpload(session: api.session, request: request, payload: payload)
        }
        return probe.average
    }
}

private final class Probe: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let limit: Double
    private let rampUp: Double
    private let onSpeed: (Double) -> Void
    private let lock = NSLock()
    private let started = Date()
    private var meter = SpeedMeter()
    private var total: Int64 = 0
    private var measuredBytes: Int64 = 0
    private var measuredFrom = -1.0
    private var lastReport = 0.0
    private var lastSent: Int64 = 0
    private var continuation: CheckedContinuation<Void, Error>?
    private var stoppedByUs = false

    init(limit: Double, rampUp: Double, onSpeed: @escaping (Double) -> Void) {
        self.limit = limit
        self.rampUp = rampUp
        self.onSpeed = onSpeed
    }

    private var elapsed: Double { Date().timeIntervalSince(started) }

    var finished: Bool { elapsed >= limit }

    var average: Double {
        lock.lock()
        defer { lock.unlock() }
        let seconds = elapsed - measuredFrom
        return measuredFrom < 0 || seconds <= 0 ? 0 : Double(measuredBytes) / seconds
    }

    func runDownload(session: URLSession, request: URLRequest) async throws {
        let task = session.dataTask(with: request)
        try await run(task)
    }

    func runUpload(session: URLSession, request: URLRequest, payload: Data) async throws {
        lastSent = 0
        let task = session.uploadTask(with: request, from: payload)
        try await run(task)
    }

    private func run(_ task: URLSessionTask) async throws {
        task.delegate = self
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                self.continuation = continuation
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    private func add(_ bytes: Int64) {
        lock.lock()
        total += bytes
        let now = elapsed
        if now >= rampUp {
            if measuredFrom < 0 { measuredFrom = now } else { measuredBytes += bytes }
        }
        var report: Double?
        if now - lastReport >= 0.2 {
            lastReport = now
            report = meter.sample(bytes: total, seconds: now)
        }
        lock.unlock()
        if let report { onSpeed(report) }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard dataTask.originalRequest?.httpMethod == "GET" else { return }
        add(Int64(data.count))
        if finished {
            stoppedByUs = true
            dataTask.cancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        add(bytesSent)
        if finished {
            stoppedByUs = true
            task.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error, !stoppedByUs {
            continuation?.resume(throwing: error)
        } else {
            continuation?.resume()
        }
        continuation = nil
    }
}
