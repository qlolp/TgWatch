import SwiftUI
import TgWatchCore

extension ServiceDefinition {
    var displayName: String {
        switch kind {
        case .telegram: "Telegram"
        case .matrix: "Matrix"
        case .whatsapp: "WhatsApp Web"
        case .custom: NSLocalizedString("customHTTPS", comment: "") + (id == "custom" ? "" : " " + id.replacingOccurrences(of: "custom", with: ""))
        }
    }
    var symbol: String {
        switch kind {
        case .telegram: "paperplane.fill"
        case .matrix: "bubble.left.and.bubble.right"
        case .whatsapp: "phone.bubble"
        case .custom: "server.rack"
        }
    }
}

@MainActor final class ServiceCatalog: ObservableObject {
    @Published private(set) var monitors: [MonitorController] = []
    @Published var storageError = false
    @Published private(set) var favoriteIDs: Set<String> = ["telegram"]
    var dataRoot: URL { file.deletingLastPathComponent() }
    private let file: URL
    private var active = false
    private var writable = true
    private var removing = false

    init() {
        var root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TgWatch")
        if ProcessInfo.processInfo.arguments.contains("--fixture") {
            root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        }
        file = root.appendingPathComponent("services-v1.json")
        var definitions = ServiceDefinition.defaults
        if FileManager.default.fileExists(atPath: file.path) {
            do {
                let handle = try FileHandle(forReadingFrom: file)
                defer { try? handle.close() }
                let data = try handle.read(upToCount: 65_537) ?? Data()
                let decoded = try JSONDecoder().decode([ServiceDefinition].self, from: data)
                let allowed = Set(["telegram", "matrix", "whatsapp", "custom", "custom2", "custom3", "custom4", "custom5"])
                guard data.count <= 65_536, decoded.count <= 8,
                      Set(decoded.map(\.id)).count == decoded.count,
                      decoded.contains(where: { $0.id == "telegram" && $0.kind == .telegram }),
                      decoded.allSatisfy({ allowed.contains($0.id) && $0.valid }) else { throw CocoaError(.fileReadCorruptFile) }
                definitions = decoded
            } catch { storageError = true; writable = false }
        }
        monitors = definitions.map { MonitorController(definition: $0) }
        for monitor in monitors {
            monitor.onObservationPublished = { [weak self] in
                guard let self else { return }
                CompanionBridge.shared.publish(catalog: self)
            }
        }
        if writable && !FileManager.default.fileExists(atPath: file.path) {
            do { try persist(definitions) } catch { storageError = true; writable = false }
        }
        let favoritesFile = root.appendingPathComponent("favorites.json")
        if let data = try? Data(contentsOf: favoritesFile), data.count <= 4096,
           let ids = try? JSONDecoder().decode(Set<String>.self, from: data) {
            favoriteIDs = ids.intersection(Set(definitions.map(\.id)))
        }
        _ = MonitorNotifications.shared
        CompanionBridge.shared.register(catalog: self)
        CompanionBridge.shared.publish(catalog: self)
    }

    func setActive(_ value: Bool) {
        active = value
        for monitor in monitors {
            if !value { monitor.resetContinuity() }
            monitor.session.setActive(value)
        }
    }
    func clockChanged() {
        for monitor in monitors { monitor.resetContinuity(); monitor.session.clockChanged() }
    }
    func toggleFavorite(_ id: String) {
        guard monitors.contains(where: { $0.definition.id == id }) else { return }
        var updated = favoriteIDs
        if updated.contains(id) { updated.remove(id) } else { updated.insert(id) }
        do {
            try JSONEncoder().encode(updated).write(to: dataRoot.appendingPathComponent("favorites.json"), options: .atomic)
            favoriteIDs = updated
            CompanionBridge.shared.publish(catalog: self)
        } catch { storageError = true }
    }
    var nextCustomID: String? {
        ["custom", "custom2", "custom3", "custom4", "custom5"].first { id in !monitors.contains { $0.definition.id == id } }
    }
    func save(_ definition: ServiceDefinition) throws {
        guard writable, !removing, definition.valid else { throw CocoaError(.fileWriteUnknown) }
        var definitions = monitors.map(\.definition)
        if let index = definitions.firstIndex(where: { $0.id == definition.id }) {
            definitions[index] = definition
        } else {
            guard definitions.count < 8, definition.id == nextCustomID else { throw CocoaError(.fileWriteUnknown) }
            definitions.append(definition)
        }
        try persist(definitions)
        let replacement = MonitorController(definition: definition)
        replacement.session.setActive(active)
        replacement.onObservationPublished = { [weak self] in
            guard let self else { return }
            CompanionBridge.shared.publish(catalog: self)
        }
        if let index = monitors.firstIndex(where: { $0.definition.id == definition.id }) {
            let previous = monitors[index]
            previous.session.setMonitoring(false)
            previous.session.setActive(false)
            previous.cancelInFlightWebhook()
            replacement.setProfile(previous.profile)
            replacement.setInterval(previous.intervalSeconds)
            replacement.alertSettings = previous.alertSettings
            replacement.saveAlerts()
            // The new revision directory has no webhook.json; keep the previous recipient and stop its delivery.
            replacement.saveWebhook(previous.webhookSettings)
            monitors[index] = replacement
        } else { monitors.append(replacement) }
        CompanionBridge.shared.publish(catalog: self)
    }
    func remove(_ definition: ServiceDefinition) async throws {
        guard writable, !removing, definition.kind == .custom else { throw CocoaError(.fileWriteUnknown) }
        removing = true
        defer { removing = false }
        let remaining = monitors.filter { $0.definition.id != definition.id }
        // Remove the manifest reference first; reusing a slot always gets a fresh revision.
        try persist(remaining.map(\.definition))
        await monitors.first { $0.definition.id == definition.id }?.stopAndFlush()
        monitors = remaining
        favoriteIDs.remove(definition.id)
        CompanionBridge.shared.publish(catalog: self)
        let directory = file.deletingLastPathComponent().appendingPathComponent("targets/\(definition.id)")
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
    private func persist(_ list: [ServiceDefinition]) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(list)
        try data.write(to: file, options: .atomic)
    }
}
