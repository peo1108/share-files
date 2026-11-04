import SwiftUI

struct BrowseView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var store: TransferStore

    @State private var roots: [RootFolder] = []
    @State private var rootId: String?
    @State private var path = ""
    @State private var entries: [BrowseEntry] = []
    @State private var loading = false
    @State private var error: String?
    @State private var selected: Set<String> = []

    private var isOnline: Bool {
        if case .online = model.connection { return true }
        return false
    }

    var body: some View {
        VStack(spacing: 12) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(roots) { root in
                        Button {
                            rootId = root.id
                            path = ""
                        } label: {
                            Text(root.name)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 9)
                                .background {
                                    if root.id == rootId {
                                        RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.accent)
                                    } else {
                                        RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.card)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Button(action: goUp) {
                        Image(systemName: "chevron.left")
                            .foregroundStyle(path.isEmpty ? Palette.faint : Color.white)
                            .frame(width: 34, height: 34)
                    }
                    .disabled(path.isEmpty)

                    Text(path.isEmpty ? (roots.first { $0.id == rootId }?.name ?? "Thư mục trên PC") : path)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.head)
                    Spacer()
                }
                .padding(.horizontal, 8)
                .padding(.top, 8)

                content
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .glass()

            if !selected.isEmpty {
                let chosen = entries.filter { selected.contains($0.name) }
                let total = chosen.reduce(Int64(0)) { $0 + $1.size }
                Button(action: { download(chosen) }) {
                    Label("Tải \(chosen.count) file về máy · \(ByteFormat.bytes(total))", systemImage: "arrow.down.circle.fill")
                }
                .buttonStyle(GradientButtonStyle())
            }
        }
        .padding(16)
        .task(id: isOnline) { await loadRoots() }
        .task(id: "\(rootId ?? "")/\(path)/\(isOnline)") { await loadEntries() }
    }

    @ViewBuilder
    private var content: some View {
        if !isOnline {
            message("Đang chờ kết nối với PC")
        } else if loading && entries.isEmpty {
            Spacer()
            ProgressView().tint(Palette.pink).controlSize(.large)
            Spacer()
        } else if let error {
            message(error)
        } else if entries.isEmpty {
            message("Thư mục trống")
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(entries) { entry in
                        Button { tap(entry) } label: { row(entry) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(8)
            }
        }
    }

    private func message(_ text: String) -> some View {
        VStack {
            Spacer()
            Text(text)
                .font(.system(size: 14))
                .foregroundStyle(Palette.subtle)
                .multilineTextAlignment(.center)
                .padding(24)
            Spacer()
        }
    }

    private func row(_ entry: BrowseEntry) -> some View {
        HStack(spacing: 13) {
            Image(systemName: entry.isDirectory ? "folder.fill" : "doc.fill")
                .font(.system(size: 21))
                .foregroundStyle(entry.isDirectory ? Palette.amber : Palette.cyan)
                .frame(width: 27)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                    .font(.system(size: 14))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if !entry.isDirectory {
                    Text(ByteFormat.bytes(entry.size))
                        .font(.system(size: 12))
                        .foregroundStyle(Palette.faint)
                }
            }
            Spacer()
            if !entry.isDirectory {
                Image(systemName: selected.contains(entry.name) ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(selected.contains(entry.name) ? Palette.pink : Palette.faint)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    private func tap(_ entry: BrowseEntry) {
        if entry.isDirectory {
            path = path.isEmpty ? entry.name : "\(path)/\(entry.name)"
        } else if selected.contains(entry.name) {
            selected.remove(entry.name)
        } else {
            selected.insert(entry.name)
        }
    }

    private func goUp() {
        guard let slash = path.lastIndex(of: "/") else {
            path = ""
            return
        }
        path = String(path[..<slash])
    }

    private func download(_ chosen: [BrowseEntry]) {
        guard let rootId else { return }
        for entry in chosen {
            store.addDownload(
                root: rootId,
                path: path.isEmpty ? entry.name : "\(path)/\(entry.name)",
                name: entry.name,
                size: entry.size
            )
        }
        selected.removeAll()
    }

    private func loadRoots() async {
        guard isOnline, let api = model.api else { return }
        do {
            roots = try await api.roots()
            if rootId == nil || !roots.contains(where: { $0.id == rootId }) {
                rootId = roots.first?.id
            }
        } catch is CancellationError {
            return
        } catch {
            self.error = "Không tải được danh sách thư mục"
        }
    }

    private func loadEntries() async {
        guard isOnline, let rootId, let api = model.api else { return }
        loading = true
        error = nil
        selected.removeAll()
        defer { loading = false }
        do {
            entries = try await api.browse(root: rootId, path: path).entries
        } catch is CancellationError {
            return
        } catch let failure as ApiFailure {
            if failure.status == 404, !path.isEmpty { path = "" }
            error = failure.message
        } catch let urlError as URLError where urlError.code == .cancelled {
            return
        } catch {
            self.error = "Mất kết nối với PC"
        }
    }
}
