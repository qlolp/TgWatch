import SwiftUI
@main struct TgWatchApp: App {
    @StateObject private var controller = MonitorController()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            MonitorView(controller: controller)
                .onAppear { controller.session.setActive(phase == .active) }
                .onChange(of: phase) { _, value in controller.session.setActive(value == .active) }
        }
    }
}
