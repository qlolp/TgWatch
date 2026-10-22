import AppKit
import UserNotifications

func tr(_ key: String) -> String { NSLocalizedString(key, comment: "") }

/// Permission and posting are owned by the native bundle. The Python engine never posts in app mode.
@MainActor final class RegionalNativeNotifications: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private let defaults: UserDefaults
    private let isCurrent: (RegionalMenuReading, UInt64) -> Bool
    var onChange: () -> Void = {}
    var onDenied: () -> Void = {}
    private(set) var authorized = false
    private var pending = Set<RegionalMenuService>()
    private var delivered: [RegionalMenuService: String] = [:]
    private var lastAlert: [RegionalMenuService: Double] = [:]
    var optedIn: Bool { defaults.bool(forKey: "notifications.enabled") }

    init(defaults: UserDefaults, isCurrent: @escaping (RegionalMenuReading, UInt64) -> Bool) {
        self.defaults = defaults
        self.isCurrent = isCurrent
        super.init()
        center.delegate = self
    }
    func refresh() {
        center.getNotificationSettings { [weak self] settings in
            let allowed = Self.allowed(settings)
            DispatchQueue.main.async { self?.authorized = allowed; self?.onChange() }
        }
    }
    func toggle() {
        if optedIn {
            defaults.set(false, forKey: "notifications.enabled")
            center.removePendingNotificationRequests(withIdentifiers: RegionalMenuService.allCases.map {
                "regional-restriction-" + $0.rawValue
            })
            onChange()
        } else {
            center.requestAuthorization(options: [.alert, .sound]) { [weak self] granted, _ in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.defaults.set(granted, forKey: "notifications.enabled")
                    self.refresh()
                    if !granted { self.onDenied() }
                }
            }
        }
    }
    func consider(_ reading: RegionalMenuReading, generation: UInt64) {
        let service = reading.service
        if reading.status == .webReachable { delivered.removeValue(forKey: service) }
        guard optedIn, reading.status == .regionRestricted, reading.regionConfirmed,
              let context = reading.contextID, delivered[service] != context, !pending.contains(service) else { return }
        let now = Date().timeIntervalSince1970 * 1_000
        if let previous = lastAlert[service], now < previous || now - previous < 300_000 { return }
        pending.insert(service)
        center.getNotificationSettings { [weak self] settings in
            let allowed = Self.allowed(settings)
            DispatchQueue.main.async {
                guard let self else { return }
                guard allowed, self.optedIn, self.isCurrent(reading, generation) else {
                    self.pending.remove(service); return
                }
                let content = UNMutableNotificationContent()
                content.title = reading.service.title + ": " + tr("Explicit regional refusal")
                content.body = tr("The public service reported a regional refusal twice. Verify access in the service; account features remain unverified.")
                content.sound = .default
                content.userInfo = ["service": service.rawValue]
                let request = UNNotificationRequest(identifier: "regional-restriction-" + service.rawValue,
                                                    content: content, trigger: nil)
                self.center.add(request) { [weak self] error in
                    let succeeded = error == nil
                    DispatchQueue.main.async {
                        guard let self else { return }
                        self.pending.remove(service)
                        if succeeded {
                            self.delivered[service] = context
                            self.lastAlert[service] = Date().timeIntervalSince1970 * 1_000
                        }
                    }
                }
            }
        }
    }
    nonisolated private static func allowed(_ settings: UNNotificationSettings) -> Bool {
        (settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional) &&
            settings.alertSetting == .enabled
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
        willPresent notification: UNNotification, withCompletionHandler completionHandler:
        @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let service = (response.notification.request.content.userInfo["service"] as? String)
            .flatMap(RegionalMenuService.init(rawValue:))
        DispatchQueue.main.async { if let service { NSWorkspace.shared.open(service.address) } }
        completionHandler()
    }
}
