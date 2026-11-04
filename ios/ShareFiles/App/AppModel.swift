import Foundation
import SwiftUI
import UIKit

enum Connection: Equatable {
    case idle
    case searching
    case online(host: String)
}

enum PairingStatus: Equatable {
    case none
    case working(String)
    case failed(String)
}

/// App-wide state: who we are paired with, how to reach them, and the transfer queue.
@MainActor
final class AppModel: ObservableObject {
    private static let pollInterval: UInt64 = 3_000_000_000
    private static let searchInterval: UInt64 = 2_000_000_000

    @Published private(set) var paired: PairedServer?
    @Published private(set) var connection: Connection = .idle
    @Published private(set) var pairing: PairingStatus = .none
    @Published private(set) var preparingCount = 0

    let store = TransferStore()

    private(set) var api: ShareAPI?
    private var loop: Task<Void, Never>?
    private var loopSession: URLSession?
    private var isVisible = true
    private var suspect = false
    private var discoveredHosts: [String: String] = [:]
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private lazy var discovery = ServiceDiscovery { [weak self] id, host in
        Task { @MainActor in
            AppLog.shared.add("Thấy PC tại \(host)")
            self?.discoveredHosts[id] = host
        }
    }

    init() {
        paired = Storage.read(PairedServer.self, from: Storage.pairedFile)
        store.apiProvider = { [weak self] in self?.api }
        store.onConnectionSuspect = { [weak self] in self?.suspect = true }
        restartLoop()
    }

    // MARK: Lifecycle

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            isVisible = true
            endBackgroundTask()
            restartLoop()
        case .background:
            isVisible = false
            beginBackgroundTaskIfNeeded()
            if !store.hasActive { stopLoop() }
        default:
            break
        }
    }

    /// iOS allows about 30 more seconds. After that, transfers pause and resume on the next launch.
    private func beginBackgroundTaskIfNeeded() {
        guard store.hasActive, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "transfers") { [weak self] in
            Task { @MainActor in
                AppLog.shared.add("iOS tạm dừng chuyển file khi app nằm nền, mở lại để chạy tiếp")
                self?.stopLoop()
                self?.endBackgroundTask()
            }
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    // MARK: Connection

    private func restartLoop() {
        stopLoop()
        guard let server = paired else {
            connection = .idle
            return
        }
        discovery.start()
        let session = PinnedSession.make(fingerprint: server.fingerprint)
        loopSession = session
        loop = Task { [weak self] in
            await self?.connectionLoop(serverId: server.serverId, session: session)
        }
    }

    private func stopLoop() {
        loop?.cancel()
        loop = nil
        discovery.stop()
        api = nil
        connection = .idle
        // Transfers already running on this session are allowed to finish.
        loopSession?.finishTasksAndInvalidate()
        loopSession = nil
    }

    private func connectionLoop(serverId: String, session: URLSession) async {
        while !Task.isCancelled {
            guard let server = paired, server.serverId == serverId else { return }

            if api == nil || suspect {
                suspect = false
                api = nil
                connection = .searching
                guard let host = await findHost(server: server, session: session) else {
                    try? await Task.sleep(nanoseconds: Self.searchInterval)
                    continue
                }
                guard !Task.isCancelled else { return }
                api = ShareAPI(session: session, host: host, port: server.port, token: server.token)
                connection = .online(host: host)
                AppLog.shared.add("Đã kết nối \(server.serverName) tại \(host)")
                store.pump()
                if server.lastHost != host {
                    var updated = server
                    updated.lastHost = host
                    paired = updated
                    Storage.write(updated, to: Storage.pairedFile)
                }
            }

            guard let current = api else { continue }
            do {
                store.addOutbox(try await current.outbox())
                try await Task.sleep(nanoseconds: Self.pollInterval)
            } catch let failure as ApiFailure where failure.status == 401 {
                AppLog.shared.add("PC đã huỷ ghép đôi thiết bị này")
                unpair()
                return
            } catch is CancellationError {
                return
            } catch is ApiFailure {
                try? await Task.sleep(nanoseconds: Self.pollInterval)
            } catch {
                if Task.isCancelled { return }
                AppLog.shared.add("Mất kết nối: \(error.localizedDescription)")
                suspect = true
            }
        }
    }

    private func findHost(server: PairedServer, session: URLSession) async -> String? {
        var candidates: [String] = []
        for host in [discoveredHosts[server.serverId], server.lastHost].compactMap({ $0 }) + server.addresses
        where !candidates.contains(host) {
            candidates.append(host)
        }

        for host in candidates {
            let probe = ShareAPI(session: session, host: host, port: server.port, token: nil)
            if let info = try? await probe.info(), info.id == server.serverId { return host }
        }
        return nil
    }

    // MARK: Pairing

    func pair(text: String) {
        guard let link = PairLink.parse(text) else {
            pairing = .failed("Đây không phải mã ghép đôi của Share Files")
            return
        }
        guard case .working = pairing else {
            pairing = .working(link.serverName)
            discovery.start()
            Task { await runPairing(link) }
            return
        }
    }

    private func runPairing(_ link: PairLink) async {
        let session = PinnedSession.make(fingerprint: link.fingerprint)
        var hosts: [String] = []
        for host in [discoveredHosts[link.serverId]].compactMap({ $0 }) + link.addresses where !hosts.contains(host) {
            hosts.append(host)
        }

        var lastError = "Không kết nối được \(link.serverName). Kiểm tra lại WiFi và app trên PC."
        for host in hosts {
            let probe = ShareAPI(session: session, host: host, port: link.port, token: nil)
            do {
                guard try await probe.info().id == link.serverId else { continue }
                let response = try await probe.pair(
                    secret: link.secret, deviceId: Storage.deviceId(), deviceName: UIDevice.current.name
                )
                let server = PairedServer(
                    serverId: response.serverId,
                    serverName: response.serverName,
                    fingerprint: link.fingerprint,
                    token: response.token,
                    port: link.port,
                    addresses: link.addresses,
                    lastHost: host
                )
                Storage.write(server, to: Storage.pairedFile)
                paired = server
                pairing = .none
                AppLog.shared.add("Đã ghép đôi với \(server.serverName) tại \(host)")
                restartLoop()
                return
            } catch let failure as ApiFailure {
                lastError = failure.message
                break
            } catch {
                AppLog.shared.add("Ghép đôi qua \(host) thất bại: \(error.localizedDescription)")
            }
        }
        pairing = .failed(lastError)
    }

    func dismissPairingError() {
        pairing = .none
    }

    func unpair() {
        try? FileManager.default.removeItem(at: Storage.pairedFile)
        paired = nil
        stopLoop()
    }

    // MARK: Picking files

    /// Copies a picked file into the app's outbox and queues it.
    func enqueue(stage: @escaping @Sendable () throws -> StagedFile) {
        preparingCount += 1
        Task.detached(priority: .userInitiated) { [weak self] in
            let result = Result { try stage() }
            await MainActor.run {
                guard let self else { return }
                self.preparingCount -= 1
                switch result {
                case .success(let file):
                    self.store.addUpload(stagedFolder: file.folder, name: file.name, size: file.size, modifiedAt: file.modifiedAt)
                case .failure(let error):
                    AppLog.shared.add("Không lấy được file: \(error.localizedDescription)")
                }
            }
        }
    }
}
