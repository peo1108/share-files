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

    /// Fetches into [partial]. Returns the ETag so a later attempt can use If-Range.
    static func download(
        api: ShareAPI, path: String, size: Int64, etag: String?, partial: URL, progress: ProgressBox, onEtag: @escaping (String) -> Void
    ) async throws {
        let stored = Self.length(of: partial)
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

    static func length(of url: URL) -> Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }
}
