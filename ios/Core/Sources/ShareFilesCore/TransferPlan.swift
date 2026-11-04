import Foundation

/// Smoothed transfer speed, safe to feed a counter that jumps when a transfer resumes.
struct SpeedMeter {
    private var lastBytes: Int64 = -1
    private var lastSeconds = 0.0
    private var speed = 0.0

    mutating func sample(bytes: Int64, seconds: Double) -> Double {
        if lastBytes >= 0, seconds > lastSeconds {
            let current = Double(max(0, bytes - lastBytes)) / (seconds - lastSeconds)
            speed = speed == 0 ? current : speed * 0.6 + current * 0.4
        }
        if bytes < lastBytes { speed = 0 }
        lastBytes = bytes
        lastSeconds = seconds
        return speed
    }
}

/// The decisions behind resuming a transfer, kept free of I/O so they can be tested.
enum TransferPlan {
    enum Download: Equatable {
        /// All bytes arrived in an earlier attempt; only the finishing step was lost.
        case alreadyComplete
        case fetch(headers: [String: String])
    }

    static func download(stored: Int64, size: Int64, etag: String?) -> Download {
        if size > 0, stored == size { return .alreadyComplete }
        guard stored > 0 else { return .fetch(headers: [:]) }

        var headers = ["Range": "bytes=\(stored)-"]
        if let etag { headers["If-Range"] = etag }
        return .fetch(headers: headers)
    }

    /// Any other success status means a fresh copy: the file changed on the PC or Range was ignored.
    static func keepsStoredBytes(status: Int) -> Bool { status == 206 }

    static func uniqueName(_ name: String, exists: (String) -> Bool) -> String {
        guard exists(name) else { return name }
        let file = name as NSString
        let stem = file.deletingPathExtension
        let ext = file.pathExtension
        var attempt = 1
        while true {
            let candidate = ext.isEmpty ? "\(stem) (\(attempt))" : "\(stem) (\(attempt)).\(ext)"
            if !exists(candidate) { return candidate }
            attempt += 1
        }
    }

    /// Encodes a query string. URLComponents leaves "+" alone, but the server reads it as a space,
    /// so a file called "a+b.txt" would come back as not found.
    static func query(_ values: [String: String]) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return values.sorted { $0.key < $1.key }.map { key, value in
            let encoded = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
            return "\(key)=\(encoded)"
        }.joined(separator: "&")
    }

    /// Names come from the PC. They must never be able to point outside the target folder.
    static func safeFileName(_ name: String) -> String {
        let last = name.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        return last.isEmpty || last == "." || last == ".." ? "file" : last
    }
}

enum ByteFormat {
    static func bytes(_ value: Double) -> String {
        let units = ["B", "KB", "MB", "GB", "TB"]
        var number = value
        var unit = 0
        while number >= 1000, unit < units.count - 1 {
            number /= 1000
            unit += 1
        }
        let text: String
        if unit == 0 || number >= 100 {
            text = String(format: "%.0f", number)
        } else {
            text = String(format: "%.1f", number).replacingOccurrences(of: ".", with: ",")
        }
        return "\(text) \(units[unit])"
    }

    static func bytes(_ value: Int64) -> String { bytes(Double(value)) }

    static func speed(_ value: Double) -> String { bytes(value) + "/s" }
}
