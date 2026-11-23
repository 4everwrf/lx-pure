import SwiftUI

@main
struct LXPureApp: App {
    @StateObject private var container = AppContainer()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(container)
                .environmentObject(container.settingsStore)
                .environmentObject(container.libraryStore)
                .environmentObject(container.userApiManager)
                .environmentObject(container.localMusicManager)
                .environmentObject(container.playerController)
        }
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .background {
                container.handleBackgroundTransition()
            }
        }
    }
}
