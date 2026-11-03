import Foundation
import WidgetKit
import WatchConnectivity
import TgWatchCore

@MainActor
final class CompanionBridge: NSObject, WCSessionDelegate {
    static let shared = CompanionBridge()

    private let appGroupID = "group.ru.tgwatch.ios"
    private var lastSnapshotData: Data?
    private weak var catalogRef: ServiceCatalog?
    private weak var egress: EgressController?

    func register(egress: EgressController) {
        self.egress = egress
        egress.onChange = { [weak self] in
            guard let self, let catalog = self.catalogRef else { return }
            self.publish(catalog: catalog)
        }
    }

    private var egressCountryFlag: String? {
        guard let egress, let date = egress.checkedAt,
              (0...90).contains(Date().timeIntervalSince(date)) else { return nil }
        return egress.address?.flag
    }

    override init() {
        super.init()
        if WCSession.isSupported() {
            let session = WCSession.default
            session.delegate = self
            session.activate()
        }
    }

    func register(catalog: ServiceCatalog) {
        self.catalogRef = catalog
    }

    func publish(catalog: ServiceCatalog) {
        self.catalogRef = catalog
        var items: [CompanionSnapshot.Item] = []

        // 1. Telegram
        if let telegram = catalog.monitors.first(where: { $0.definition.kind == .telegram }) {
            let snapshot = telegram.session.isFresh() ? telegram.session.snapshot : nil
            let observedAt = snapshot?.finishedAt ?? Date.distantPast
            let validUntil = observedAt.addingTimeInterval(telegram.session.resultLifetime)
            items.append(
                CompanionSnapshot.Item(
                    id: "telegram",
                    displayName: "Telegram",
                    statusRaw: snapshot?.status.rawValue ?? "unknown",
                    latencyMilliseconds: snapshot?.latencyMilliseconds,
                    observedAt: observedAt,
                    validUntil: validUntil,
                    countryFlag: egressCountryFlag,
                    countryObservedAt: egress?.checkedAt,
                    countryValidUntil: egress?.checkedAt?.addingTimeInterval(90)
                )
            )
        }

        // 2. Favorites
        for monitor in catalog.monitors where catalog.favoriteIDs.contains(monitor.definition.id) && monitor.definition.kind != .telegram {
            let snapshot = monitor.session.isFresh() ? monitor.session.snapshot : nil
            let observedAt = snapshot?.finishedAt ?? Date.distantPast
            let validUntil = observedAt.addingTimeInterval(monitor.session.resultLifetime)
            items.append(
                CompanionSnapshot.Item(
                    id: monitor.definition.id,
                    displayName: monitor.definition.displayName,
                    statusRaw: snapshot?.status.rawValue ?? "unknown",
                    latencyMilliseconds: snapshot?.latencyMilliseconds,
                    observedAt: observedAt,
                    validUntil: validUntil
                )
            )
        }

        let companionSnapshot = CompanionSnapshot(generatedAt: Date(), items: items)
        guard let data = try? JSONEncoder().encode(companionSnapshot) else { return }
        self.lastSnapshotData = data

        // Write to App Group
        if let containerURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID) {
            let fileURL = containerURL.appendingPathComponent("companion_snapshot.json")
            try? data.write(to: fileURL, options: .atomic)
            WidgetCenter.shared.reloadAllTimelines()
        }

        // Send to Apple Watch
        if WCSession.isSupported() {
            let session = WCSession.default
            if session.activationState == .activated {
                try? session.updateApplicationContext(["snapshot": data])
            }
        }
    }

    // MARK: - WCSessionDelegate

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if activationState == .activated {
            Task { @MainActor in
                if let data = self.lastSnapshotData {
                    try? session.updateApplicationContext(["snapshot": data])
                }
            }
        }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String : Any], replyHandler: @escaping ([String : Any]) -> Void) {
        Task { @MainActor in
            if message["action"] as? String == "refresh" {
                if let catalog = self.catalogRef {
                    for monitor in catalog.monitors where monitor.definition.kind == .telegram || catalog.favoriteIDs.contains(monitor.definition.id) {
                        monitor.session.checkNow()
                    }
                    self.publish(catalog: catalog)
                }
                if let data = self.lastSnapshotData {
                    replyHandler(["snapshot": data])
                } else {
                    replyHandler([:])
                }
            }
        }
    }
}
