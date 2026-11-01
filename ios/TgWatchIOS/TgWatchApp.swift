import SwiftUI
@main struct TgWatchApp: App {
    @StateObject private var catalog = ServiceCatalog()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ServicesRootView(catalog: catalog)
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in catalog.clockChanged() }
                .onAppear { catalog.setActive(phase == .active) }
                .onChange(of: phase) { _, value in catalog.setActive(value == .active) }
        }
    }
}
