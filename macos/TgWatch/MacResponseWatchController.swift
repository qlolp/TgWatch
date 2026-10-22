import Foundation

/// Main-actor orchestration; persistence contains four fixed intent fields and never carries positive evidence.
@MainActor final class MacResponseWatchController {
    private let defaults: UserDefaults
    private let live: (RegionalMenuService) -> (RegionalMenuReading, UInt64)?
    private let notifications: RegionalNativeNotifications
    private var watches: [RegionalMenuService: MacPublicResponseWatch] = [:]
    var onChange: () -> Void = {}

    init(defaults: UserDefaults, services: Set<RegionalMenuService>, notifications: RegionalNativeNotifications,
         live: @escaping (RegionalMenuService) -> (RegionalMenuReading, UInt64)?) {
        self.defaults = defaults
        self.notifications = notifications
        self.live = live
        let now = Date().timeIntervalSince1970 * 1_000
        let uptime = ProcessInfo.processInfo.systemUptime
        for intent in MacWatchIntentCodec.decode(defaults.data(forKey: "response.watches") ?? Data(), now: now) {
            if services.contains(intent.service), let watch = MacPublicResponseWatch(intent: intent, now: now, uptime: uptime) {
                watches[intent.service] = watch
            }
        }
    }
    func intent(_ service: RegionalMenuService) -> MacWatchIntent? { watches[service]?.intent }
    func arm(_ service: RegionalMenuService) {
        guard let (reading, _) = live(service), let watch = MacPublicResponseWatch.arm(reading,
            now: Date().timeIntervalSince1970 * 1_000, uptime: ProcessInfo.processInfo.systemUptime) else { return }
        watches[service] = watch
        persist()
        onChange()
    }
    func cancel(_ service: RegionalMenuService) { watches.removeValue(forKey: service); persist(); onChange() }
    func profilesChanged(_ services: Set<RegionalMenuService>) {
        let removed = watches.keys.filter { !services.contains($0) }
        removed.forEach { watches.removeValue(forKey: $0) }
        if !removed.isEmpty { persist() }
        resetEvidence()
    }
    func resetEvidence() { for service in Array(watches.keys) { watches[service]?.resetEvidence() } }
    func prune() {
        let now = Date().timeIntervalSince1970 * 1_000, uptime = ProcessInfo.processInfo.systemUptime
        let expired = watches.keys.filter { watches[$0]?.active(now: now, uptime: uptime) != true }
        expired.forEach { watches.removeValue(forKey: $0) }
        if !expired.isEmpty { persist(); onChange() }
    }
    func observe() {
        prune()
        let now = Date().timeIntervalSince1970 * 1_000, uptime = ProcessInfo.processInfo.systemUptime
        for service in Array(watches.keys) {
            if let (reading, generation) = live(service) {
                watches[service]?.observe(reading, generation: generation, now: now, uptime: uptime)
            } else { watches[service]?.resetEvidence() }
        }
        considerReady()
    }
    func considerReady() {
        prune()
        for service in Array(watches.keys) {
            guard let intent = watches[service]?.intent, ready(service, id: intent.id) else { continue }
            notifications.postPublicResponse(service: service, requestID: intent.id,
                isCurrent: { [weak self] in self?.ready(service, id: intent.id) == true },
                completion: { [weak self] accepted in
                    guard let self, accepted, self.watches[service]?.intent.id == intent.id else { return }
                    self.watches.removeValue(forKey: service)
                    self.persist()
                    self.onChange()
                })
        }
    }
    private func ready(_ service: RegionalMenuService, id: String) -> Bool {
        guard let watch = watches[service], watch.intent.id == id,
            let (reading, generation) = live(service), reading.status == .webReachable else { return false }
        return watch.ready(context: reading.contextID, generation: generation,
            now: Date().timeIntervalSince1970 * 1_000, uptime: ProcessInfo.processInfo.systemUptime)
    }
    private func persist() {
        let intents = watches.values.map(\.intent).sorted { $0.service.rawValue < $1.service.rawValue }
        if let data = MacWatchIntentCodec.encode(intents) { defaults.set(data, forKey: "response.watches") }
    }
}
