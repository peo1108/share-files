import Foundation

enum TransferDirection: String, Codable {
    case upload, download
}

enum TransferState: String, Codable {
    case queued, running, waiting, done, failed
}

struct TransferItem: Codable, Identifiable, Equatable {
    let id: String
    let direction: TransferDirection
    let name: String
    let size: Int64
    var state: TransferState = .queued
    var transferred: Int64 = 0
    var error: String?
    /// Upload: folder name under Storage.outbox holding the staged copy.
    var stagedFolder: String?
    var modifiedAt: String?
    /// Download: path and query on the server.
    var remotePath: String?
    var outboxId: String?
    var etag: String?
    /// Download: file name under Storage.received once complete.
    var savedName: String?
    var speed: Double = 0

    private enum CodingKeys: String, CodingKey {
        case id, direction, name, size, state, transferred, error, stagedFolder, modifiedAt, remotePath, outboxId, etag, savedName
    }

    var isActive: Bool { state == .queued || state == .running || state == .waiting }
}

/// All transfers, their persistence and their workers. A transfer that loses its connection goes to
/// waiting and is retried with the same id, so it continues rather than starts over.
@MainActor
final class TransferStore: ObservableObject {
    private static let maxParallel = 3
    private static let maxKeptFinished = 50
    private static let maxBackoff = 15.0

    private struct Running {
        let task: Task<Void, Never>
        let progress: ProgressBox
        let started: Date
        var meter = SpeedMeter()
    }

    @Published private(set) var items: [TransferItem]

    var apiProvider: () -> ShareAPI? = { nil }
    var onConnectionSuspect: () -> Void = {}

    private var running: [String: Running] = [:]
    private var retryAt: [String: Date] = [:]
    private var attempts: [String: Int] = [:]
    private var ticker: Task<Void, Never>?

    init() {
        let stored = Storage.read([TransferItem].self, from: Storage.transfersFile) ?? []
        items = stored.map { item in
            var copy = item
            if copy.state == .running { copy.state = .queued }
            return copy
        }
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 300_000_000)
                self?.tick()
            }
        }
    }

    var hasActive: Bool { items.contains { $0.isActive } }

    var totalSpeed: Double { items.reduce(0) { $0 + $1.speed } }

    func addUpload(stagedFolder: String, name: String, size: Int64, modifiedAt: String?) {
        add([TransferItem(
            id: UUID().uuidString,
            direction: .upload,
            name: name,
            size: size,
            stagedFolder: stagedFolder,
            modifiedAt: modifiedAt
        )])
    }

    func addDownload(root: String, path: String, name: String, size: Int64) {
        add([TransferItem(
            id: UUID().uuidString,
            direction: .download,
            name: name,
            size: size,
            remotePath: "/v1/download?" + TransferPlan.query(["root": root, "path": path])
        )])
    }

    /// Called from each outbox poll. An entry already in the list is ignored.
    func addOutbox(_ entries: [OutboxEntry]) {
        let known = Set(items.compactMap(\.outboxId))
        let fresh = entries.filter { !$0.sourceMissing && !known.contains($0.id) }.map { entry in
            TransferItem(
                id: "outbox-\(entry.id)",
                direction: .download,
                name: entry.name,
                size: entry.size,
                remotePath: "/v1/outbox/\(entry.id)/content",
                outboxId: entry.id
            )
        }
        add(fresh)
    }

    func retry(_ id: String) {
        attempts[id] = nil
        retryAt[id] = nil
        change(id) {
            $0.state = .queued
            $0.error = nil
        }
        persist()
        pump()
    }

    func cancel(_ id: String) {
        guard let item = items.first(where: { $0.id == id }) else { return }
        running[id]?.task.cancel()
        running[id] = nil
        items.removeAll { $0.id == id }
        persist()
        removeLocalData(of: item)

        guard let api = apiProvider() else { return }
        Task {
            if let outboxId = item.outboxId {
                try? await api.rejectOutbox(outboxId)
            } else if item.direction == .upload {
                if let status = try? await api.beginUpload(fileKey: item.id, name: item.name, size: item.size, modifiedAt: item.modifiedAt),
                   !status.completed {
                    try? await api.deleteUpload(status.id)
                }
            }
        }
    }

    func clearFinished() {
        items.removeAll { $0.state == .done }
        persist()
    }

    /// Starts whatever can run. Safe to call at any time, including when nothing changed.
    func pump() {
        guard let api = apiProvider() else { return }
        let now = Date()
        for item in items {
            guard running.count < Self.maxParallel else { break }
            guard running[item.id] == nil else { continue }
            switch item.state {
            case .queued:
                start(item, api: api)
            case .waiting where (retryAt[item.id] ?? .distantPast) <= now:
                start(item, api: api)
            default:
                continue
            }
        }
    }

    private func add(_ fresh: [TransferItem]) {
        guard !fresh.isEmpty else { return }
        items.append(contentsOf: fresh)
        persist()
        pump()
    }

    private func start(_ item: TransferItem, api: ShareAPI) {
        let progress = ProgressBox(item.transferred)
        change(item.id) {
            $0.state = .running
            $0.error = nil
        }
        let id = item.id
        let task = Task { [weak self] in
            do {
                switch item.direction {
                case .upload:
                    try await Self.runUpload(item, api: api, progress: progress)
                case .download:
                    let saved = try await Self.runDownload(item, api: api, progress: progress) { etag in
                        Task { @MainActor in
                            self?.change(id) { $0.etag = etag }
                            self?.persist()
                        }
                    }
                    self?.change(id) { $0.savedName = saved }
                }
                self?.finish(id)
            } catch is CancellationError {
                self?.released(id)
            } catch let failure as ApiFailure {
                await self?.handle(failure, item: item, api: api)
            } catch let error as URLError where error.code == .cancelled && Task.isCancelled {
                self?.released(id)
            } catch let error as URLError {
                self?.handleNetwork(error, id: id, name: item.name)
            } catch {
                self?.fail(id, name: item.name, message: Self.describe(error))
            }
        }
        running[id] = Running(task: task, progress: progress, started: Date())
    }

    private static func runUpload(_ item: TransferItem, api: ShareAPI, progress: ProgressBox) async throws {
        guard let folder = item.stagedFolder else { throw StagingError.missing }
        let file = Storage.outbox.appendingPathComponent(folder, isDirectory: true).appendingPathComponent(item.name)
        guard FileManager.default.fileExists(atPath: file.path) else { throw StagingError.missing }
        _ = try await TransferRunner.upload(
            api: api, fileKey: item.id, file: file, name: item.name, size: item.size, modifiedAt: item.modifiedAt, progress: progress
        )
    }

    private static func runDownload(
        _ item: TransferItem, api: ShareAPI, progress: ProgressBox, onEtag: @escaping (String) -> Void
    ) async throws -> String {
        let partial = Storage.partial.appendingPathComponent(item.id + ".part")
        try await TransferRunner.download(
            api: api,
            path: item.remotePath ?? "",
            size: item.size,
            etag: item.etag,
            partial: partial,
            progress: progress,
            onEtag: onEtag
        )

        let folder = Storage.received
        let safe = TransferPlan.safeFileName(item.name)
        let name = TransferPlan.uniqueName(safe) { FileManager.default.fileExists(atPath: folder.appendingPathComponent($0).path) }
        try FileManager.default.moveItem(at: partial, to: folder.appendingPathComponent(name))

        if let outboxId = item.outboxId {
            do {
                try await api.ackOutbox(outboxId)
            } catch let failure as ApiFailure where failure.status == 404 {
                // Already gone from the PC's queue. The file itself arrived intact.
            } catch {
                // The file is safe here. If the PC still lists it, ignore that: the id is known.
                AppLog.shared.add("Chưa báo được PC là đã nhận \(item.name)")
            }
        }
        return name
    }

    private func finish(_ id: String) {
        running[id] = nil
        attempts[id] = nil
        guard let item = items.first(where: { $0.id == id }) else { return }
        change(id) {
            $0.state = .done
            $0.transferred = $0.size
            $0.speed = 0
        }
        if item.direction == .upload { removeLocalData(of: item) }
        AppLog.shared.add("Xong \(item.name)")
        trimFinished()
        persist()
        pump()
    }

    private func released(_ id: String) {
        running[id] = nil
        pump()
    }

    private func handle(_ failure: ApiFailure, item: TransferItem, api: ShareAPI) async {
        if failure.status == 401 {
            scheduleRetry(item.id, message: "Đang chờ ghép đôi lại")
            return
        }
        if failure.code == "source_gone", let outboxId = item.outboxId {
            try? await api.rejectOutbox(outboxId)
        }
        fail(item.id, name: item.name, message: failure.message)
    }

    private func handleNetwork(_ error: URLError, id: String, name: String) {
        AppLog.shared.add("Mất kết nối khi chuyển \(name): \(error.localizedDescription)")
        onConnectionSuspect()
        scheduleRetry(id, message: "Đang chờ kết nối lại")
    }

    private func scheduleRetry(_ id: String, message: String) {
        running[id] = nil
        let attempt = (attempts[id] ?? 0) + 1
        attempts[id] = attempt
        retryAt[id] = Date().addingTimeInterval(min(Self.maxBackoff, pow(2, Double(min(attempt - 1, 5)))))
        change(id) {
            $0.state = .waiting
            $0.error = message
            $0.speed = 0
        }
        persist()
    }

    private func fail(_ id: String, name: String, message: String) {
        running[id] = nil
        AppLog.shared.add("Lỗi \(name): \(message)")
        change(id) {
            $0.state = .failed
            $0.error = message
            $0.speed = 0
        }
        persist()
        pump()
    }

    /// Publishes byte counters a few times a second, so the UI is not redrawn for every buffer.
    private func tick() {
        let now = Date()
        for (id, entry) in running {
            let bytes = entry.progress.value
            var meter = entry.meter
            let speed = meter.sample(bytes: bytes, seconds: now.timeIntervalSince(entry.started))
            running[id]?.meter = meter
            change(id) {
                $0.transferred = bytes
                $0.speed = speed
            }
        }
        pump()
    }

    private func change(_ id: String, _ transform: (inout TransferItem) -> Void) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        transform(&items[index])
    }

    private func trimFinished() {
        let finished = items.filter { $0.state == .done }
        guard finished.count > Self.maxKeptFinished else { return }
        let dropped = Set(finished.prefix(finished.count - Self.maxKeptFinished).map(\.id))
        items.removeAll { dropped.contains($0.id) }
    }

    private func persist() {
        Storage.write(items, to: Storage.transfersFile)
    }

    private func removeLocalData(of item: TransferItem) {
        if let folder = item.stagedFolder {
            try? FileManager.default.removeItem(at: Storage.outbox.appendingPathComponent(folder, isDirectory: true))
        }
        if item.direction == .download, item.state != .done {
            try? FileManager.default.removeItem(at: Storage.partial.appendingPathComponent(item.id + ".part"))
        }
    }

    private static func describe(_ error: Error) -> String {
        if let staging = error as? StagingError { return staging.errorDescription ?? "Lỗi" }
        let nsError = error as NSError
        if nsError.domain == NSCocoaErrorDomain, nsError.code == NSFileWriteOutOfSpaceError {
            return "Máy đã hết dung lượng"
        }
        return error.localizedDescription
    }
}

enum StagingError: Error, LocalizedError {
    case missing

    var errorDescription: String? { "File gửi đi không còn trên máy" }
}
