import SwiftUI
import UIKit

@main
struct ShareFilesApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.store)
                .preferredColorScheme(.dark)
                .onOpenURL { url in model.pair(text: url.absoluteString) }
        }
        .onChange(of: scenePhase) { _, phase in
            model.scenePhaseChanged(phase)
        }
    }
}

private struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var store: TransferStore

    var body: some View {
        ZStack {
            AuroraBackground()
            if model.paired == nil {
                PairView()
            } else {
                MainView()
            }
        }
        // A locked screen would suspend the app and pause the transfer.
        .onChange(of: store.hasActive, initial: true) { _, active in
            UIApplication.shared.isIdleTimerDisabled = active
        }
    }
}
