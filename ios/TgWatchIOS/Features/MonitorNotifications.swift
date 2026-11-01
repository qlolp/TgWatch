import Foundation
import UserNotifications
import TgWatchCore

struct AlertSettings: Codable, Equatable {
    var down = false
    var partial = false
    var recovery = false
    var offline = false
    var sound = false
    var quietStart = 23 * 60
    var quietEnd = 8 * 60
    var quietEnabled = false
    // A temporary mute is intentionally not persisted or transferred.
    var mutedUntil: Date?

    func enabled(_ status: ProbeStatus) -> Bool {
        switch status {
        case .down: down
        case .partial: partial
        case .ok: recovery
        case .noNetwork: offline
        case .unknown: false
        }
    }
    func audible(at date: Date) -> Bool {
        guard sound, mutedUntil.map({ $0 <= date }) ?? true else { return false }
        guard quietEnabled, quietStart != quietEnd else { return true }
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        let quiet = quietStart < quietEnd ? (quietStart..<quietEnd).contains(minute) : minute >= quietStart || minute < quietEnd
        return !quiet
    }
    enum CodingKeys: String, CodingKey { case down, partial, recovery, offline, sound, quietStart, quietEnd, quietEnabled }
}

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
        let content = UNMutableNotificationContent()
        content.title = target
        content.body = NSLocalizedString(status == .ok ? "serviceRecovered" : "status.\(status.rawValue)", comment: "")
        if sound { content.sound = .default }
        try await UNUserNotificationCenter.current().add(.init(identifier: UUID().uuidString, content: content, trigger: nil))
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
