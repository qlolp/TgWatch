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
        if writable && !FileManager.default.fileExists(atPath: file.path) {
            do { try persist(definitions) } catch { storageError = true; writable = false }
        }
        _ = MonitorNotifications.shared
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
        if let index = monitors.firstIndex(where: { $0.definition.id == definition.id }) {
            let previous = monitors[index]
            previous.session.setMonitoring(false)
            previous.session.setActive(false)
            replacement.setProfile(previous.profile)
            replacement.setInterval(previous.intervalSeconds)
            replacement.alertSettings = previous.alertSettings
            replacement.saveAlerts()
            monitors[index] = replacement
        } else { monitors.append(replacement) }
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
        let directory = file.deletingLastPathComponent().appendingPathComponent("targets/\(definition.id)")
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
    private func persist(_ definitions: [ServiceDefinition]) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(definitions).write(to: file, options: [.atomic, .completeFileProtection])
        var url = file
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
}
