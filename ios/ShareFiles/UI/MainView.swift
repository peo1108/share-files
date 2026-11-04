import SwiftUI

private enum Tab: Int, CaseIterable, Identifiable {
    case transfers, browse, settings

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .transfers: "Chuyển"
        case .browse: "Lấy từ PC"
        case .settings: "Cài đặt"
        }
    }

    var icon: String {
        switch self {
        case .transfers: "arrow.up.arrow.down"
        case .browse: "folder.fill"
        case .settings: "gearshape.fill"
        }
    }
}

struct MainView: View {
    @State private var tab: Tab = .transfers

    var body: some View {
        VStack(spacing: 0) {
            Group {
                switch tab {
                case .transfers: TransfersView()
                case .browse: BrowseView()
                case .settings: SettingsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            HStack(spacing: 6) {
                ForEach(Tab.allCases) { item in
                    Button {
                        tab = item
                    } label: {
                        VStack(spacing: 3) {
                            Image(systemName: item.icon).font(.system(size: 19))
                            Text(item.title).font(.system(size: 12, weight: item == tab ? .semibold : .regular))
                        }
                        .foregroundStyle(item == tab ? Color.white : Palette.subtle)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background {
                            if item == tab {
                                RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Palette.accent)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(6)
            .glass(radius: 24)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
    }
}
