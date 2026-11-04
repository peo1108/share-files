import Foundation

/// Recent events, shown on the settings screen. There is no debugger on a sideloaded build, so this
/// is the way to find out what happened on the device.
final class AppLog: ObservableObject, @unchecked Sendable {
    static let shared = AppLog()

    private static let maxLines = 300
    @Published private(set) var lines: [String] = []

    func add(_ message: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let line = "\(formatter.string(from: Date()))  \(message)"
        DispatchQueue.main.async {
            self.lines = Array((self.lines + [line]).suffix(Self.maxLines))
        }
    }
}
