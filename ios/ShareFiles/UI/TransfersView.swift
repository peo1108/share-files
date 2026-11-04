import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct TransfersView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var store: TransferStore
    @State private var showPhotos = false
    @State private var showFiles = false

    var body: some View {
        VStack(spacing: 12) {
            statusCard

            HStack(spacing: 10) {
                Button {
                    showPhotos = true
                } label: {
                    Label("Gửi ảnh", systemImage: "photo.fill")
                }
                .buttonStyle(GradientButtonStyle())

                Button {
                    showFiles = true
                } label: {
                    Label("Gửi file", systemImage: "doc.fill")
                }
                .buttonStyle(GhostButtonStyle(expands: true))
            }

            transferList
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 12)
        .sheet(isPresented: $showPhotos) {
            PhotoPicker { providers in
                for provider in providers { enqueue(provider) }
            }
            .ignoresSafeArea()
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            for url in urls {
                model.enqueue {
                    let accessing = url.startAccessingSecurityScopedResource()
                    defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                    return try Staging.stage(copying: url)
                }
            }
        }
    }

    private var statusCard: some View {
        let (color, label): (Color, String) = switch model.connection {
        case .online: (Palette.success, "Đã kết nối")
        case .searching: (Palette.amber, "Đang tìm PC")
        case .idle: (Palette.faint, "Chưa kết nối")
        }

        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 9) {
                Circle().fill(color).frame(width: 9, height: 9)
                Text(label).font(.system(size: 13, weight: .medium)).foregroundStyle(color)
            }
            Text(model.paired?.serverName ?? "PC")
                .font(.system(size: 23, weight: .bold))
                .foregroundStyle(.white)
                .lineLimit(1)
            if case .online(let host) = model.connection {
                Text(host).font(.system(size: 12)).foregroundStyle(Palette.faint)
            }
            if store.totalSpeed > 0 {
                Text(ByteFormat.speed(store.totalSpeed))
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Palette.amber)
                    .padding(.top, 4)
            }
            if model.preparingCount > 0 {
                Text("Đang chuẩn bị \(model.preparingCount) file…")
                    .font(.system(size: 12))
                    .foregroundStyle(Palette.subtle)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glass()
    }

    private var transferList: some View {
        VStack(spacing: 0) {
            HStack {
                SectionTitle(text: "Danh sách chuyển")
                if store.items.contains(where: { $0.state == .done }) {
                    Button("Xoá mục đã xong") { store.clearFinished() }
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.subtle)
                        .fixedSize()
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 14)
            .padding(.bottom, 6)

            if store.items.isEmpty {
                Spacer()
                Image(systemName: "arrow.up.arrow.down")
                    .font(.system(size: 34))
                    .foregroundStyle(Palette.faint)
                Text("Chưa có gì đang chuyển")
                    .font(.system(size: 14))
                    .foregroundStyle(Palette.subtle)
                    .padding(.top, 10)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(ordered) { item in
                            TransferRow(item: item)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 10)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .glass()
    }

    private var ordered: [TransferItem] {
        let newestFirst = Array(store.items.reversed())
        return newestFirst.filter { $0.state != .done } + newestFirst.filter { $0.state == .done }
    }

    private func enqueue(_ provider: NSItemProvider) {
        guard let type = provider.registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .image) == true || UTType($0)?.conforms(to: .movie) == true
        }) else {
            AppLog.shared.add("Không nhận ra định dạng của mục đã chọn")
            return
        }

        // The system deletes this temporary file when the callback returns, so stage it right there.
        model.enqueue {
            var staged: Result<StagedFile, Error> = .failure(CocoaError(.fileReadUnknown))
            let done = DispatchSemaphore(value: 0)
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, error in
                defer { done.signal() }
                if let url {
                    let ext = url.pathExtension
                    let base = provider.suggestedName ?? url.deletingPathExtension().lastPathComponent
                    let name = ext.isEmpty ? base : "\(base).\(ext)"
                    staged = Result { try Staging.stage(copying: url, named: name) }
                } else if let error {
                    staged = .failure(error)
                }
            }
            done.wait()
            return try staged.get()
        }
    }
}

private struct TransferRow: View {
    @EnvironmentObject private var store: TransferStore
    let item: TransferItem
    @State private var savedToPhotos = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.direction == .upload ? "arrow.up" : "arrow.down")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(iconColor)
                .frame(width: 36, height: 36)
                .background(Palette.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text(item.name)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    if item.speed > 0 {
                        Text(ByteFormat.speed(item.speed))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Palette.amber)
                    }
                }
                if item.state != .done {
                    GradientProgress(fraction: item.size > 0 ? Double(item.transferred) / Double(item.size) : 0)
                }
                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(item.state == .failed ? Palette.danger : Palette.faint)
                    .lineLimit(2)
            }

            actions
        }
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var actions: some View {
        if item.state == .failed {
            Button { store.retry(item.id) } label: {
                Image(systemName: "arrow.clockwise").foregroundStyle(Palette.subtle)
            }
        }
        if item.state != .done {
            Button { store.cancel(item.id) } label: {
                Image(systemName: "xmark").foregroundStyle(Palette.subtle)
            }
        } else if let url = savedURL {
            if isMedia(url), !savedToPhotos {
                Button { saveToPhotos(url) } label: {
                    Image(systemName: "photo.badge.plus").foregroundStyle(Palette.subtle)
                }
            }
            ShareLink(item: url) {
                Image(systemName: "square.and.arrow.up").foregroundStyle(Palette.subtle)
            }
        }
    }

    private var iconColor: Color {
        switch item.state {
        case .done: Palette.success
        case .failed: Palette.danger
        default: .white
        }
    }

    private var detail: String {
        switch item.state {
        case .queued: "\(ByteFormat.bytes(item.size)) · đang xếp hàng"
        case .running: "\(ByteFormat.bytes(item.transferred)) / \(ByteFormat.bytes(item.size))"
        case .waiting: "\(ByteFormat.bytes(item.transferred)) / \(ByteFormat.bytes(item.size)) · \(item.error ?? "đang chờ")"
        case .done: savedToPhotos ? "\(ByteFormat.bytes(item.size)) · đã lưu vào Ảnh" : "\(ByteFormat.bytes(item.size)) · xong"
        case .failed: item.error ?? "Lỗi"
        }
    }

    private var savedURL: URL? {
        item.savedName.map { Storage.received.appendingPathComponent($0) }
    }

    private func isMedia(_ url: URL) -> Bool {
        guard let type = UTType(filenameExtension: url.pathExtension) else { return false }
        return type.conforms(to: .image) || type.conforms(to: .movie)
    }

    private func saveToPhotos(_ url: URL) {
        let isVideo = UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie) == true
        PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: isVideo ? .video : .photo, fileURL: url, options: nil)
        } completionHandler: { success, error in
            DispatchQueue.main.async {
                if success {
                    savedToPhotos = true
                } else {
                    AppLog.shared.add("Không lưu được vào Ảnh: \(error?.localizedDescription ?? "")")
                }
            }
        }
    }
}

/// The system photo picker. It keeps the original format and needs no library permission.
private struct PhotoPicker: UIViewControllerRepresentable {
    let onPick: ([NSItemProvider]) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.selectionLimit = 0
        configuration.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPick: ([NSItemProvider]) -> Void

        init(onPick: @escaping ([NSItemProvider]) -> Void) {
            self.onPick = onPick
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            onPick(results.map(\.itemProvider))
        }
    }
}
