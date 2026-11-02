import Foundation
import UserNotifications
import TgWatchCore

final class MonitorNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MonitorNotifications()
    override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }
    func authorize() async throws -> Bool {
        try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }
    func send(target: String, status: ProbeStatus, sound: Bool) async throws {
        try await send(target: target, message: NSLocalizedString(status == .ok ? "serviceRecovered" : "status.\(status.rawValue)", comment: ""), sound: sound)
    }
    func sendSlowResponse(target: String, sound: Bool) async throws {
        try await send(target: target, message: NSLocalizedString("slowResponseMessage", comment: ""), sound: sound)
    }
    private func send(target: String, message: String, sound: Bool) async throws {
        let content = UNMutableNotificationContent()
        content.title = target
        content.body = message
        if sound { content.sound = .default }
        try await UNUserNotificationCenter.current().add(.init(identifier: UUID().uuidString, content: content, trigger: nil))
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
