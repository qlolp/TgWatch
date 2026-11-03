import Foundation

/// The saved-file contract is shared by export, preview and the restore transaction.
/// Every current target has an explicit history/settings set, including default values.
public enum BackupContents {
    public static let manifestLimit = 65_536
    public static let historyLimit = 8 * 1024 * 1024
    public static let settingsLimit = 4096
    public static let maxFiles = 1024
    public static let directoryFiles = ["history-v1.json", "preferences.json", "alerts.json", "interval.json", "webhook.json"]
    public static let serviceIDs: Set<String> = ["telegram", "matrix", "whatsapp", "custom", "custom2", "custom3", "custom4", "custom5"]

    public struct Validated: Sendable {
        public let definitions: [ServiceDefinition]
        public let favorites: Set<String>
        public let historyEntries: Int
    }

    public static func decodeManifest(_ data: Data) throws -> [ServiceDefinition] {
        guard data.count <= manifestLimit else { throw BackupError.payloadTooLarge }
        let definitions = try JSONDecoder().decode([ServiceDefinition].self, from: data)
        guard !definitions.isEmpty, definitions.count <= 8,
              Set(definitions.map(\.id)).count == definitions.count,
              definitions.contains(where: { $0.id == "telegram" && $0.kind == .telegram }),
              definitions.allSatisfy({ definition in
                  guard serviceIDs.contains(definition.id), definition.valid else { return false }
                  switch definition.id {
                  case "telegram": return definition.kind == .telegram
                  case "matrix": return definition.kind == .matrix
                  case "whatsapp": return definition.kind == .whatsapp
                  default: return definition.kind == .custom
                  }
              }) else { throw BackupError.corruptedData }
        return definitions
    }

    public static func encodedManifest(_ definitions: [ServiceDefinition]) throws -> Data {
        let data = try JSONEncoder().encode(definitions)
        _ = try decodeManifest(data)
        return data
    }

    public static func prefix(for definition: ServiceDefinition) -> String {
        definition.kind == .telegram ? "" : "targets/\(definition.id)/\(definition.revision.uuidString)/"
    }

    public static func fileLimit(_ path: String) -> Int {
        if path == "services-v1.json" { return manifestLimit }
        if path.hasSuffix("history-v1.json") || path.hasSuffix("long-term-v1.json") { return historyLimit }
        return settingsLimit
    }

    public static func defaultDirectoryFiles() throws -> [String: Data] {
        ["history-v1.json": try JSONEncoder().encode([HistoryEntry]()),
         "preferences.json": Data("60".utf8),
         "interval.json": Data("60".utf8),
         "alerts.json": try JSONEncoder().encode(AlertSettings()),
         "webhook.json": try JSONEncoder().encode(WebhookSettings())]
    }

    public static func validate(_ payload: EncryptedBackupPayload) throws -> Validated {
        let result = try validateFiles(payload.files)
        guard try JSONEncoder().encode(payload).count <= EncryptedBackup.maxPlaintextSize else { throw BackupError.payloadTooLarge }
        guard payload.preview.createdAt.timeIntervalSince1970.isFinite,
              !payload.preview.appVersion.isEmpty, payload.preview.appVersion.utf8.count <= 64,
              payload.preview.serviceCount == result.definitions.count,
              payload.preview.totalHistoryEntries == result.historyEntries,
              payload.preview.serviceNames.count == result.definitions.count,
              payload.preview.serviceNames.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 256 }) else {
            throw BackupError.corruptedData
        }
        return result
    }

    public static func validateFiles(_ files: [String: Data]) throws -> Validated {
        guard !files.isEmpty, files.count <= maxFiles else { throw BackupError.corruptedData }
        var bytes = 0
        for (path, data) in files {
            try EncryptedBackup.validatePath(path)
            guard data.count <= fileLimit(path) else { throw BackupError.payloadTooLarge }
            bytes += data.count
            guard bytes <= EncryptedBackup.maxPlaintextSize else { throw BackupError.payloadTooLarge }
        }
        guard let manifest = files["services-v1.json"], let favoritesData = files["favorites.json"] else {
            throw BackupError.corruptedData
        }
        let definitions = try decodeManifest(manifest)
        let favorites = try JSONDecoder().decode(Set<String>.self, from: favoritesData)
        guard favorites.isSubset(of: Set(definitions.map(\.id))) else { throw BackupError.corruptedData }
        var prefixes = Set(definitions.map(prefix(for:)))
        for path in files.keys where path.hasPrefix("targets/") {
            let components = path.split(separator: "/")
            guard definitions.contains(where: { $0.id == components[1] && $0.kind != .telegram }) else {
                throw BackupError.corruptedData
            }
            prefixes.insert(components.prefix(3).joined(separator: "/") + "/")
        }
        var counts: [String: Int] = [:]
        for prefix in prefixes {
            guard directoryFiles.allSatisfy({ files[prefix + $0] != nil }) else { throw BackupError.corruptedData }
            counts[prefix] = try validateHistory(files[prefix + "history-v1.json"]!)
            let profile = try JSONDecoder().decode(Int.self, from: files[prefix + "preferences.json"]!)
            let interval = try JSONDecoder().decode(Int.self, from: files[prefix + "interval.json"]!)
            let alerts = try JSONDecoder().decode(AlertSettings.self, from: files[prefix + "alerts.json"]!)
            let webhook = try JSONDecoder().decode(WebhookSettings.self, from: files[prefix + "webhook.json"]!)
            guard [15, 30, 60].contains(profile), (10...300).contains(interval), alerts.valid, webhook.valid else {
                throw BackupError.corruptedData
            }
            if let data = files[prefix + "long-term-v1.json"] {
                guard try JSONDecoder().decode(LongTermHistory.self, from: data).valid else { throw BackupError.corruptedData }
            }
        }
        if let maintenance = files["maintenance.json"] {
            let windows = try JSONDecoder().decode([MaintenanceWindow].self, from: maintenance)
            guard MaintenanceSchedule.sanitized(windows) != nil else { throw BackupError.corruptedData }
        }
        if let summary = files["daily-summary.json"] {
            let settings = try JSONDecoder().decode(DailySummarySettings.self, from: summary)
            guard settings.valid else { throw BackupError.corruptedData }
        }
        return Validated(definitions: definitions, favorites: favorites,
                         historyEntries: definitions.reduce(0) { $0 + (counts[prefix(for: $1)] ?? 0) })
    }

    private struct HistoryEnvelope: Decodable {
        let schemaVersion: Int
        let entries: [HistoryEntry]
        let archive: LongTermHistory
        let minutes: [HistoryBucket]?
        let coarse: [HistoryBucket]?
    }

    private static func validateHistory(_ data: Data) throws -> Int {
        let entries: [HistoryEntry]
        if data.first(where: { ![9, 10, 13, 32].contains($0) }) == 91 {
            entries = try JSONDecoder().decode([HistoryEntry].self, from: data)
        } else {
            let envelope = try JSONDecoder().decode(HistoryEnvelope.self, from: data)
            guard (envelope.schemaVersion == 2 || envelope.schemaVersion == 3), envelope.archive.valid,
                  (envelope.minutes ?? []).allSatisfy(\.valid), (envelope.coarse ?? []).allSatisfy(\.valid) else {
                throw BackupError.corruptedData
            }
            entries = envelope.entries
        }
        guard entries.count <= 10_000, entries.allSatisfy({ entry in
            entry.schemaVersion == 1 && entry.observedAt.timeIntervalSince1970.isFinite &&
            (entry.validForSeconds.map { $0.isFinite && (0.001...600).contains($0) } ?? true) &&
            (entry.latencyMilliseconds.map { $0.isFinite && $0 >= 0 } ?? true) &&
            (entry.networkKind == nil || NetworkKind.normalize(entry.networkKind) != nil)
        }) else { throw BackupError.corruptedData }
        return entries.count
    }
}
