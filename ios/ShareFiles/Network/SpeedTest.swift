import Foundation

/// Measures the network path with data held in memory, so storage speed plays no part. Several
/// streams run at once, the way large downloads do, because one TCP stream does not fill fast Wi-Fi.
final class SpeedTest {
    private static let duration = 7.0
    private static let rampUp = 1.0
    private static let streams = TransferPlan.downloadParts
    private static let maxBytes: Int64 = 4 * 1024 * 1024 * 1024
    private static let uploadChunk = 128 * 1024 * 1024

    private let api: ShareAPI

    init(api: ShareAPI) {
        self.api = api
    }

    /// Returns bytes per second. [onSpeed] gets live readings on an arbitrary thread.
    func download(onSpeed: @escaping (Double) -> Void) async throws -> Double {
        let clock = SpeedClock(limit: Self.duration, rampUp: Self.rampUp, onSpeed: onSpeed)
        let api = self.api
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<Self.streams {
                let request = api.makeRequest("/v1/speedtest/download", query: ["bytes": String(Self.maxBytes)])
                group.addTask {
                    try await Probe(clock: clock).run(api.session.dataTask(with: request))
                }
            }
            try await group.waitForAll()
        }
        return clock.average
    }

    func upload(onSpeed: @escaping (Double) -> Void) async throws -> Double {
        let clock = SpeedClock(limit: Self.duration, rampUp: Self.rampUp, onSpeed: onSpeed)
        let api = self.api
        let payload = Data(count: Self.uploadChunk)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<Self.streams {
                group.addTask {
                    while !clock.finished {
                        try Task.checkCancellation()
                        var request = api.makeRequest("/v1/speedtest/upload")
                        request.httpMethod = "POST"
                        try await Probe(clock: clock).run(api.session.uploadTask(with: request, from: payload))
                    }
                }
            }
            try await group.waitForAll()
        }
        return clock.average
    }
}

/// Shared by all streams of one measurement. Skips the ramp-up when averaging.
private final class SpeedClock: @unchecked Sendable {
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

    func add(_ bytes: Int64) {
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
}

/// One stream: runs a task, feeds the shared clock, and stops the task when time is up.
private final class Probe: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let clock: SpeedClock
    private var continuation: CheckedContinuation<Void, Error>?
    private var stoppedByUs = false

    init(clock: SpeedClock) {
        self.clock = clock
    }

    func run(_ task: URLSessionTask) async throws {
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

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard dataTask.originalRequest?.httpMethod == "GET" else { return }
        clock.add(Int64(data.count))
        if clock.finished {
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
        clock.add(bytesSent)
        if clock.finished {
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
