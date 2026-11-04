import Foundation
import PhotosUI

struct StagedFile: Sendable {
    let folder: String
    let name: String
    let size: Int64
    let modifiedAt: String?
}

/// Copies what the user picked into the app's own storage. Clones on the same volume are instant,
/// and a transfer that resumes later can still read the file.
enum Staging {
    private static let iso8601 = ISO8601DateFormatter()

    static func stage(copying source: URL, named name: String? = nil) throws -> StagedFile {
        let folder = UUID().uuidString
        let directory = Storage.outbox.appendingPathComponent(folder, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let fileName = TransferPlan.safeFileName(name ?? source.lastPathComponent)
        let target = directory.appendingPathComponent(fileName)

        do {
            try coordinatedCopy(from: source, to: target)
            let attributes = try FileManager.default.attributesOfItem(atPath: target.path)
            let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
            let modified = (attributes[.modificationDate] as? Date).map { iso8601.string(from: $0) }
            return StagedFile(folder: folder, name: fileName, size: size, modifiedAt: modified)
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
    }

    /// Goes through a file coordinator so files in iCloud Drive are downloaded before being read.
    private static func coordinatedCopy(from source: URL, to target: URL) throws {
        var coordinationError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: source, options: .forUploading, error: &coordinationError) { url in
            do {
                try FileManager.default.copyItem(at: url, to: target)
            } catch {
                copyError = error
            }
        }
        if let error = coordinationError ?? copyError { throw error }
    }
}
