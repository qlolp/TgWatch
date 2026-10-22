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
    private var responsePending: [RegionalMenuService: String] = [:]
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
            } + RegionalMenuService.allCases.map { "regional-public-response-" + $0.rawValue })
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
    func postPublicResponse(service: RegionalMenuService, requestID: String,
        isCurrent: @escaping () -> Bool, completion: @escaping (Bool) -> Void) {
        guard optedIn, responsePending[service] == nil else { return }
        responsePending[service] = requestID
        let finish: (Bool) -> Void = { [weak self] accepted in
            if self?.responsePending[service] == requestID { self?.responsePending.removeValue(forKey: service) }
            completion(accepted)
        }
        center.getNotificationSettings { [weak self] settings in
            let allowed = Self.allowed(settings)
            DispatchQueue.main.async {
                guard let self, allowed, self.optedIn, isCurrent() else { finish(false); return }
                self.center.getDeliveredNotifications { [weak self] delivered in
                    let alreadyPosted = delivered.contains {
                        $0.request.identifier == "regional-public-response-" + service.rawValue &&
                            $0.request.content.userInfo["goal_id"] as? String == requestID
                    }
                    DispatchQueue.main.async {
                        guard let self, self.optedIn, isCurrent() else { finish(false); return }
                        if alreadyPosted { finish(true); return }
                        let identifier = "regional-public-response-" + service.rawValue
                        let content = UNMutableNotificationContent()
                        content.title = service.title + ": " + tr("Public website responds")
                        content.body = tr("Two fresh public responses were observed. Account and feature access remain unverified.")
                        content.sound = .default
                        content.userInfo = ["service": service.rawValue, "goal_id": requestID]
                        // All card changes occur after the main-actor request/context check.
                        self.center.removeDeliveredNotifications(withIdentifiers: [identifier])
                        self.center.removePendingNotificationRequests(withIdentifiers: [identifier])
                        self.center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil)) { error in
                            let accepted = error == nil
                            DispatchQueue.main.async { finish(accepted) }
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
