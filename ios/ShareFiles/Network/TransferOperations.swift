import Foundation

/// Thread-safe byte counter shared between URLSession callbacks and the UI.
final class ProgressBox: @unchecked Sendable {
    private let lock = NSLock()
    private var bytes: Int64

    init(_ value: Int64 = 0) {
        bytes = value
    }

    var value: Int64 {
        get {
            lock.lock()
            defer { lock.unlock() }
            return bytes
        }
        set {
            lock.lock()
            bytes = newValue
            lock.unlock()
        }
    }
}

/// One upload attempt that sends a file from an offset. Offset zero uses the efficient file path.
private final class UploadOperation: NSObject, URLSessionDataDelegate {
    private let file: URL
    private let offset: Int64
    private let progress: ProgressBox
    private var body = Data()
    private var status = 0
    private var continuation: CheckedContinuation<(Int, Data), Error>?

    init(file: URL, offset: Int64, progress: ProgressBox) {
        self.file = file
        self.offset = offset
        self.progress = progress
    }

    func run(session: URLSession, request: URLRequest) async throws -> (Int, Data) {
        let task: URLSessionUploadTask
        if offset == 0 {
            task = session.uploadTask(with: request, fromFile: file)
        } else {
            task = session.uploadTask(withStreamedRequest: request)
        }
        task.delegate = self

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        needNewBodyStream completionHandler: @escaping (InputStream?) -> Void
    ) {
        guard let stream = InputStream(url: file) else {
            completionHandler(nil)
            return
        }
        stream.setProperty(offset as NSNumber, forKey: .fileCurrentOffsetKey)
        completionHandler(stream)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        progress.value = offset + totalBytesSent
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        status = (response as? HTTPURLResponse)?.statusCode ?? 0
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        body.append(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            continuation?.resume(throwing: error)
        } else {
            continuation?.resume(returning: (status, body))
        }
        continuation = nil
    }
}

/// One download attempt that appends to a partial file.
private final class DownloadOperation: NSObject, URLSessionDataDelegate {
    private let partial: URL
    private let stored: Int64
    private let progress: ProgressBox
    private var handle: FileHandle?
    private var written: Int64 = 0
    private var status = 0
    private var errorBody = Data()
    private var etag: String?
    private var writeError: Error?
    private var continuation: CheckedContinuation<(Int, String?, Int64, Data), Error>?

    init(partial: URL, stored: Int64, progress: ProgressBox) {
        self.partial = partial
        self.stored = stored
        self.progress = progress
    }

    func run(session: URLSession, request: URLRequest) async throws -> (status: Int, etag: String?, total: Int64, errorBody: Data) {
        let task = session.dataTask(with: request)
        task.delegate = self

        let result = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<(Int, String?, Int64, Data), Error>) in
                self.continuation = continuation
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
        return (result.0, result.1, result.2, result.3)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let http = response as? HTTPURLResponse
        status = http?.statusCode ?? 0
        etag = http?.value(forHTTPHeaderField: "ETag")

        guard (200..<300).contains(status) else {
            completionHandler(.allow)
            return
        }

        do {
            let manager = FileManager.default
            if !TransferPlan.keepsStoredBytes(status: status) || !manager.fileExists(atPath: partial.path) {
                try? manager.removeItem(at: partial)
                manager.createFile(atPath: partial.path, contents: nil)
                written = 0
            } else {
                written = stored
            }
            let file = try FileHandle(forWritingTo: partial)
            try file.seekToEnd()
            handle = file
            progress.value = written
            completionHandler(.allow)
        } catch {
            writeError = error
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let handle else {
            errorBody.append(data)
            return
        }
        do {
            try handle.write(contentsOf: data)
            written += Int64(data.count)
            progress.value = written
        } catch {
            writeError = error
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? handle?.close()
        handle = nil
        if let writeError {
            continuation?.resume(throwing: writeError)
        } else if let error {
            continuation?.resume(throwing: error)
        } else {
            continuation?.resume(returning: (status, etag, written, errorBody))
        }
        continuation = nil
    }
}

/// Bytes held of each range of a download fetched in parts.
final class PartsBox: @unchecked Sendable {
    private let lock = NSLock()
    private var held: [Int64]

    init(_ held: [Int64]) {
        self.held = held
    }

    var snapshot: [Int64] {
        lock.lock()
        defer { lock.unlock() }
        return held
    }

    var total: Int64 { snapshot.reduce(0, +) }

    func add(_ index: Int, _ bytes: Int64) {
        lock.lock()
        held[index] += bytes
        lock.unlock()
    }

    func reset() {
        lock.lock()
        held = held.map { _ in 0 }
        lock.unlock()
    }
}

/// The file on the PC is no longer the one the bytes already held came from.
private struct PartsSourceChanged: Error {
    let etag: String?
    let length: Int64?
}

/// Fetches one range of a file and writes it in place. Returns the ETag the server sent.
private final class PartOperation: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let file: URL
    private let start: Int64
    private let end: Int64
    private let size: Int64
    private let index: Int
    private let box: PartsBox
    private let progress: ProgressBox
    private let onHeaders: (String?) -> Void
    private var handle: FileHandle?
    private var position: Int64
    private var status = 0
    private var etag: String?
    private var failure: Error?
    private var errorBody = Data()
    private var continuation: CheckedContinuation<String?, Error>?

    init(
        file: URL, start: Int64, end: Int64, size: Int64, index: Int, box: PartsBox, progress: ProgressBox,
        onHeaders: @escaping (String?) -> Void
    ) {
        self.file = file
        self.start = start
        self.end = end
        self.size = size
        self.index = index
        self.box = box
        self.progress = progress
        self.onHeaders = onHeaders
        self.position = start
    }

    func run(session: URLSession, request: URLRequest) async throws -> String? {
        let task = session.dataTask(with: request)
        task.delegate = self
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String?, Error>) in
                self.continuation = continuation
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let http = response as? HTTPURLResponse
        status = http?.statusCode ?? 0
        etag = http?.value(forHTTPHeaderField: "ETag")

        if status == 416 {
            // The file shrank below this range.
            failure = PartsSourceChanged(etag: nil, length: nil)
            completionHandler(.cancel)
            return
        }
        guard (200..<300).contains(status) else {
            completionHandler(.allow)
            return
        }
        guard status == 206 else {
            let length = http?.expectedContentLength ?? -1
            failure = PartsSourceChanged(etag: etag, length: length >= 0 ? length : nil)
            completionHandler(.cancel)
            return
        }
        let contentRange = http?.value(forHTTPHeaderField: "Content-Range")
        guard contentRange == "bytes \(start)-\(end)/\(size)" else {
            let total = contentRange?.split(separator: "/").last.flatMap { Int64($0) }
            failure = PartsSourceChanged(etag: etag, length: total)
            completionHandler(.cancel)
            return
        }
        onHeaders(etag)

        do {
            let file = try FileHandle(forWritingTo: self.file)
            try file.seek(toOffset: UInt64(start))
            handle = file
            completionHandler(.allow)
        } catch {
            failure = error
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let handle else {
            errorBody.append(data)
            return
        }
        let room = end + 1 - position
        let chunk = Int64(data.count) > room ? data.prefix(Int(room)) : data
        do {
            try handle.write(contentsOf: chunk)
            position += Int64(chunk.count)
            box.add(index, Int64(chunk.count))
            progress.value = box.total
        } catch {
            failure = error
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? handle?.close()
        handle = nil
        if let failure {
            continuation?.resume(throwing: failure)
        } else if let error {
            continuation?.resume(throwing: error)
        } else if !(200..<300).contains(status) {
            continuation?.resume(throwing: ShareAPI.failure(status: status, body: errorBody))
        } else if position != end + 1 {
            continuation?.resume(throwing: URLError(.networkConnectionLost))
        } else {
            continuation?.resume(returning: etag)
        }
        continuation = nil
    }
}

/// Transfer attempts. A dropped connection surfaces as an error and the caller simply runs the same
/// call again: the offset always comes from the side that holds the data.
enum TransferRunner {
    /// Returns the name the file was saved under on the PC.
    static func upload(
        api: ShareAPI, fileKey: String, file: URL, name: String, size: Int64, modifiedAt: String?, progress: ProgressBox
    ) async throws -> String {
        var status = try await api.beginUpload(fileKey: fileKey, name: name, size: size, modifiedAt: modifiedAt)
        progress.value = status.offset

        while !status.completed {
            var request = api.makeRequest("/v1/uploads/\(status.id)")
            request.httpMethod = "PATCH"
            request.setValue(String(status.offset), forHTTPHeaderField: "Upload-Offset")
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.setValue(String(size - status.offset), forHTTPHeaderField: "Content-Length")
            // The upload as a whole has no deadline, only the gap between bytes does.
            request.timeoutInterval = 60

            let operation = UploadOperation(file: file, offset: status.offset, progress: progress)
            let (code, body) = try await operation.run(session: api.session, request: request)

            if (200..<300).contains(code) {
                status = try JSONDecoder().decode(UploadStatus.self, from: body)
                if !status.completed, status.offset >= size {
                    throw URLError(.networkConnectionLost)
                }
                continue
            }

            let failure = ShareAPI.failure(status: code, body: body)
            guard failure.code == "offset_mismatch", let correct = failure.offset else { throw failure }
            progress.value = correct
            status = UploadStatus(id: status.id, offset: correct, completed: false, savedName: nil)
        }

        progress.value = size
        return status.savedName ?? name
    }

    /// Fetches into [partial]. Reports the ETag so a later attempt can use If-Range. Large files are
    /// fetched in parts at once; [parts] and [onParts] carry how far each part got.
    static func download(
        api: ShareAPI,
        path: String,
        size: Int64,
        etag: String?,
        parts: [Int64]?,
        partial: URL,
        progress: ProgressBox,
        onEtag: @escaping (String) -> Void,
        onParts: @escaping ([Int64]) -> Void
    ) async throws {
        let stored = Self.length(of: partial)
        if TransferPlan.fetchesInParts(size: size, stored: stored, parts: parts) {
            try await downloadParts(
                api: api, path: path, size: size, etag: etag, parts: parts, partial: partial,
                progress: progress, onEtag: onEtag, onParts: onParts
            )
            return
        }
        progress.value = stored

        switch TransferPlan.download(stored: stored, size: size, etag: etag) {
        case .alreadyComplete:
            return
        case .fetch(let headers):
            var request = api.makeRequest(rawPath: path)
            request.timeoutInterval = 60
            headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }

            let operation = DownloadOperation(partial: partial, stored: stored, progress: progress)
            let result = try await operation.run(session: api.session, request: request)

            if let value = result.etag { onEtag(value) }
            guard (200..<300).contains(result.status) else {
                throw ShareAPI.failure(status: result.status, body: result.errorBody)
            }
            if size > 0 || result.total > 0, result.total != size {
                throw URLError(.networkConnectionLost)
            }
            // An empty file gets no body, so make sure it exists.
            if !FileManager.default.fileExists(atPath: partial.path) {
                FileManager.default.createFile(atPath: partial.path, contents: nil)
            }
        }
    }

    private static func downloadParts(
        api: ShareAPI,
        path: String,
        size: Int64,
        etag: String?,
        parts: [Int64]?,
        partial: URL,
        progress: ProgressBox,
        onEtag: @escaping (String) -> Void,
        onParts: @escaping ([Int64]) -> Void
    ) async throws {
        let ranges = TransferPlan.splitRanges(size: size, count: TransferPlan.downloadParts)
        let manager = FileManager.default
        var held = Array(repeating: Int64(0), count: ranges.count)
        // Without a validator there is no telling whether held bytes still match the PC's file.
        if etag != nil, let parts, parts.count == ranges.count, manager.fileExists(atPath: partial.path) {
            held = zip(parts, ranges).map { min(max($0, 0), Int64($1.count)) }
        } else {
            try? manager.removeItem(at: partial)
        }
        if !manager.fileExists(atPath: partial.path) {
            manager.createFile(atPath: partial.path, contents: nil)
        }

        let box = PartsBox(held)
        progress.value = box.total
        let saver = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled { break }
                onParts(box.snapshot)
            }
        }
        defer { saver.cancel() }

        do {
            let tags = try await withThrowingTaskGroup(of: String?.self) { group -> [String?] in
                for index in ranges.indices where held[index] < Int64(ranges[index].count) {
                    let start = ranges[index].lowerBound + held[index]
                    let end = ranges[index].upperBound
                    var request = api.makeRequest(rawPath: path)
                    request.timeoutInterval = 60
                    request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
                    if let etag { request.setValue(etag, forHTTPHeaderField: "If-Range") }
                    let operation = PartOperation(
                        file: partial, start: start, end: end, size: size, index: index, box: box, progress: progress
                    ) { tag in
                        if etag == nil, let tag { onEtag(tag) }
                    }
                    let ready = request
                    group.addTask { try await operation.run(session: api.session, request: ready) }
                }
                var tags: [String?] = []
                for try await tag in group { tags.append(tag) }
                return tags
            }
            // Without If-Range the parts must at least all come from the same version of the file.
            let versions = Set(tags.compactMap { $0 })
            if etag == nil, versions.count > 1 {
                throw PartsSourceChanged(etag: tags.compactMap { $0 }.last, length: size)
            }
            onParts(box.snapshot)
        } catch let changed as PartsSourceChanged {
            box.reset()
            try? manager.removeItem(at: partial)
            progress.value = 0
            onParts(box.snapshot)
            if let length = changed.length, length != size {
                throw ApiFailure(status: 409, code: "source_changed", message: "File trên PC đã đổi kích thước, hãy tải lại", offset: nil)
            }
            if let tag = changed.etag { onEtag(tag) }
            throw URLError(.networkConnectionLost)
        } catch {
            onParts(box.snapshot)
            throw error
        }
    }

    static func length(of url: URL) -> Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }
}
