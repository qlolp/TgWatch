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
    @Published private(set) var maintenanceWindows: [MaintenanceWindow] = []
    @Published private(set) var dailySummary = DailySummarySettings()
    @Published private(set) var restoreRevision = UUID()
    @Published var restoreCompleted = false
    var dataRoot: URL { file.deletingLastPathComponent() }
    private let file: URL
    private var active = false
    private var writable = true
    private var removing = false
    private var restoring = false
    private var exporting = false

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
            monitor.onObservationPublished = { [weak self] in self?.noteObservation() }
        }
        if writable && !FileManager.default.fileExists(atPath: file.path) {
            do { try persist(definitions) } catch { storageError = true; writable = false }
        }
        let favoritesFile = root.appendingPathComponent("favorites.json")
        if let data = try? Data(contentsOf: favoritesFile), data.count <= 4096,
           let ids = try? JSONDecoder().decode(Set<String>.self, from: data) {
            favoriteIDs = ids.intersection(Set(definitions.map(\.id)))
        }
        loadSideSettings()
        applyMaintenance()
        _ = MonitorNotifications.shared
        CompanionBridge.shared.register(catalog: self)
        CompanionBridge.shared.publish(catalog: self)
    }

    func setActive(_ value: Bool) {
        guard !restoring && !exporting || !value else { return }
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
        guard writable, !restoring, !exporting, monitors.contains(where: { $0.definition.id == id }) else { return }
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
        guard writable, !removing, !restoring, !exporting, definition.valid else { throw CocoaError(.fileWriteUnknown) }
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
        replacement.onObservationPublished = { [weak self] in self?.noteObservation() }
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
        applyMaintenance()
        CompanionBridge.shared.publish(catalog: self)
    }
    func remove(_ definition: ServiceDefinition) async throws {
        guard writable, !removing, !restoring, !exporting, definition.kind == .custom else { throw CocoaError(.fileWriteUnknown) }
        removing = true
        defer { removing = false }
        let remaining = monitors.filter { $0.definition.id != definition.id }
        // Remove the manifest reference first; reusing a slot always gets a fresh revision.
        try persist(remaining.map(\.definition))
        await monitors.first { $0.definition.id == definition.id }?.stopAndFlush()
        monitors = remaining
        favoriteIDs.remove(definition.id)
        try JSONEncoder().encode(favoriteIDs).write(to: dataRoot.appendingPathComponent("favorites.json"), options: .atomic)
        CompanionBridge.shared.publish(catalog: self)
        let directory = file.deletingLastPathComponent().appendingPathComponent("targets/\(definition.id)")
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }
    private func persist(_ list: [ServiceDefinition]) throws {
        let data = try BackupContents.encodedManifest(list)
        try BackupFileStore.writePrivate(data, to: file, limit: BackupContents.manifestLimit)
    }

    func createBackup(password: String) async throws -> Data {
        guard writable, !restoring, !exporting, !removing else { throw CocoaError(.fileWriteUnknown) }
        try EncryptedBackup.validatePassword(password)
        let wasActive = active
        exporting = true
        defer {
            exporting = false
            setActive(wasActive && UIApplication.shared.applicationState == .active)
        }
        // Prevent new observations while draining every pending history write.
        setActive(false)
        for monitor in monitors { try await monitor.flushHistoryForBackup() }
        let files = try BackupFileStore.capture(at: dataRoot)
        let contents = try BackupContents.validateFiles(files)
        let preview = EncryptedBackupPayload.Preview(
            createdAt: Date(),
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.1.0",
            serviceCount: contents.definitions.count,
            serviceNames: contents.definitions.map(\.displayName),
            totalHistoryEntries: contents.historyEntries
        )
        return try await Task.detached {
            try EncryptedBackup.seal(files: files, preview: preview, password: password)
        }.value
    }

    func previewBackup(data: Data, password: String) async throws -> EncryptedBackupPayload {
        let payload = try await Task.detached {
            try EncryptedBackup.open(data: data, password: password)
        }.value
        let contents = try BackupContents.validate(payload)
        return EncryptedBackupPayload(preview: .init(createdAt: payload.preview.createdAt,
            appVersion: payload.preview.appVersion, serviceCount: contents.definitions.count,
            serviceNames: contents.definitions.map(\.displayName), totalHistoryEntries: contents.historyEntries),
            files: payload.files)
    }

    func restore(payload: EncryptedBackupPayload) async throws {
        guard writable, !restoring, !exporting, !removing else { throw CocoaError(.fileWriteUnknown) }
        _ = try BackupContents.validate(payload) // Reject corruption before stopping or flushing monitors.
        restoring = true
        defer {
            restoring = false
            if writable { setActive(UIApplication.shared.applicationState == .active) }
        }
        setActive(false)
        for monitor in monitors { await monitor.stopAndFlush() }
        do {
            let contents = try BackupFileStore.restore(payload, at: dataRoot)
            // Use already validated definitions, never a silent fallback to default services.
            favoriteIDs = contents.favorites
            monitors = contents.definitions.map { MonitorController(definition: $0) }
            for monitor in monitors {
                monitor.onObservationPublished = { [weak self] in self?.noteObservation() }
            }
            loadSideSettings(resetMissing: true)
            applyMaintenance()
            CompanionBridge.shared.publish(catalog: self)
            // Discard navigation destinations that still retain pre-restore controllers.
            restoreRevision = UUID()
            restoreCompleted = true
        } catch BackupError.restoreRecoveryRequired {
            storageError = true
            writable = false
            throw BackupError.restoreRecoveryRequired
        }
    }

    func saveMaintenance(_ windows: [MaintenanceWindow]) {
        guard writable, !restoring, !exporting, let clean = MaintenanceSchedule.sanitized(windows) else { storageError = true; return }
        do {
            try BackupFileStore.writePrivate(JSONEncoder().encode(clean), to: dataRoot.appendingPathComponent("maintenance.json"), limit: 4096)
            maintenanceWindows = clean
            applyMaintenance()
            storageError = false
        } catch { storageError = true }
    }

    func saveDailySummary(_ settings: DailySummarySettings) {
        guard writable, !restoring, !exporting, settings.valid else { storageError = true; return }
        do {
            try BackupFileStore.writePrivate(JSONEncoder().encode(settings), to: dataRoot.appendingPathComponent("daily-summary.json"), limit: 4096)
            dailySummary = settings
            storageError = false
            refreshDailySummary()
        } catch { storageError = true }
    }

    private func loadSideSettings(resetMissing: Bool = false) {
        if resetMissing {
            maintenanceWindows = []
            dailySummary = DailySummarySettings()
        }
        let maintenanceURL = dataRoot.appendingPathComponent("maintenance.json")
        if FileManager.default.fileExists(atPath: maintenanceURL.path) {
            do {
                let data = try BackupFileStore.read(maintenanceURL, limit: 4096)
                guard let windows = MaintenanceSchedule.sanitized(try JSONDecoder().decode([MaintenanceWindow].self, from: data)) else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                maintenanceWindows = windows
            } catch { storageError = true }
        }
        let summaryURL = dataRoot.appendingPathComponent("daily-summary.json")
        if FileManager.default.fileExists(atPath: summaryURL.path) {
            do {
                let data = try BackupFileStore.read(summaryURL, limit: 4096)
                let settings = try JSONDecoder().decode(DailySummarySettings.self, from: data)
                guard settings.valid else { throw CocoaError(.fileReadCorruptFile) }
                dailySummary = settings
            } catch { storageError = true }
        }
    }

    private func applyMaintenance() {
        for monitor in monitors { monitor.setMaintenance(maintenanceWindows) }
    }

    private func noteObservation() {
        CompanionBridge.shared.publish(catalog: self)
        refreshDailySummary()
    }

    private func refreshDailySummary() {
        guard dailySummary.enabled else {
            Task { await MonitorNotifications.shared.cancelDaily() }
            return
        }
        let minute = dailySummary.minuteOfDay
        let blocks: [(String, DailySummaryStats?)] = monitors.map { monitor in
            let now = Date()
            let analytics = HistoryAnalytics(entries: monitor.history, from: now.addingTimeInterval(-86400), to: now)
            let latencies = monitor.history.compactMap(\.latencyMilliseconds)
            return (monitor.definition.displayName, DailySummary.stats(analytics: analytics, latencies: latencies))
        }
        let body = DailySummaryText.body(blocks)
        let title = DailySummaryText.title(blocks)
        Task { await MonitorNotifications.shared.scheduleDaily(title: title, body: body, minuteOfDay: minute) }
    }
}
