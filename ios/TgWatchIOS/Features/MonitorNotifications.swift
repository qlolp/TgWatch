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
    func scheduleDaily(title: String, body: String, minuteOfDay: Int) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        var parts = DateComponents()
        let minute = min(1439, max(0, minuteOfDay))
        parts.hour = minute / 60
        parts.minute = minute % 60
        let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: true)
        try? await UNUserNotificationCenter.current().add(.init(identifier: "ru.tgwatch.ios.daily-summary", content: content, trigger: trigger))
    }
    func cancelDaily() async {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["ru.tgwatch.ios.daily-summary"])
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
