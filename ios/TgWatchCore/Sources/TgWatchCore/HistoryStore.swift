import Foundation
public struct HistoryEntry: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let schemaVersion: Int
    public let epoch: UInt64
    public let observedAt: Date
    public let status: ProbeStatus
    public let validForSeconds: Double?
    public let latencyMilliseconds: Double?
    public let networkKind: String?
    public var coverageLifetime: Double { min(600, max(0.001, validForSeconds ?? 120)) }
    public init(epoch: UInt64, observedAt: Date, status: ProbeStatus, validForSeconds: Double? = nil,
                latencyMilliseconds: Double? = nil, networkKind: String? = nil) {
        self.latencyMilliseconds = latencyMilliseconds.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
        self.networkKind = NetworkKind.normalize(networkKind)
        id = UUID(); schemaVersion = 1; self.epoch = epoch; self.observedAt = observedAt; self.status = status
        self.validForSeconds = validForSeconds.flatMap { $0.isFinite ? min(600, max(0.001, $0)) : nil }
    }
}
public actor HistoryStore {
    public enum Failure: Error { case oversized, unsupportedSchema }
    private let url: URL
    private let limit: Int
    private let now: @Sendable () -> Date
    private var entries: [HistoryEntry]?
    private var archive = LongTermHistory()
    private var minutes: [HistoryBucket] = []
    private var coarse: [HistoryBucket] = []
    private struct Envelope: Codable {
        let schemaVersion: Int
        let entries: [HistoryEntry]
        let archive: LongTermHistory
        let minutes: [HistoryBucket]
        let coarse: [HistoryBucket]
        enum CodingKeys: String, CodingKey {
            case schemaVersion, entries, archive, minutes, coarse
        }
        init(entries: [HistoryEntry], archive: LongTermHistory, minutes: [HistoryBucket], coarse: [HistoryBucket]) {
            schemaVersion = 3; self.entries = entries; self.archive = archive; self.minutes = minutes; self.coarse = coarse
        }
        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
            guard schemaVersion == 2 || schemaVersion == 3 else { throw DecodingError.dataCorruptedError(forKey: .schemaVersion, in: container, debugDescription: "unsupported") }
            entries = try container.decode([HistoryEntry].self, forKey: .entries)
            archive = try container.decode(LongTermHistory.self, forKey: .archive)
            minutes = try container.decodeIfPresent([HistoryBucket].self, forKey: .minutes) ?? []
            coarse = try container.decodeIfPresent([HistoryBucket].self, forKey: .coarse) ?? []
        }
        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(schemaVersion, forKey: .schemaVersion)
            try container.encode(entries, forKey: .entries)
            try container.encode(archive, forKey: .archive)
            try container.encode(minutes, forKey: .minutes)
            try container.encode(coarse, forKey: .coarse)
        }
    }
    public init(url: URL, limit: Int = 10_000, now: @escaping @Sendable () -> Date = { Date() }) { self.url = url; self.limit = max(1, min(limit, 10_000)); self.now = now }
    public func load() throws -> [HistoryEntry] {
        if let entries {
            let rolled = roll(entries, at: now())
            self.entries = rolled.raw
            return rolled.raw
        }
        guard FileManager.default.fileExists(atPath: url.path) else { entries = []; return [] }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        let data = try handle.read(upToCount: 8*1024*1024+1) ?? Data()
        guard data.count <= 8*1024*1024 else { throw Failure.oversized }
        let decoded: [HistoryEntry]
        var loadedArchive = LongTermHistory()
        var loadedMinutes: [HistoryBucket] = []
        var loadedCoarse: [HistoryBucket] = []
        if data.first(where: { ![9, 10, 13, 32].contains($0) }) == 91 {
            decoded = try JSONDecoder().decode([HistoryEntry].self, from: data)
            for entry in decoded {
                guard entry.observedAt.timeIntervalSince1970.isFinite else { throw Failure.unsupportedSchema }
                loadedArchive.append(entry)
            }
        } else {
            let envelope = try JSONDecoder().decode(Envelope.self, from: data)
            guard envelope.schemaVersion == 2 || envelope.schemaVersion == 3, envelope.archive.valid,
                  envelope.minutes.allSatisfy(\.valid), envelope.coarse.allSatisfy(\.valid) else { throw Failure.unsupportedSchema }
            decoded = envelope.entries
            loadedArchive = envelope.archive
            loadedMinutes = envelope.minutes
            loadedCoarse = envelope.coarse
        }
        guard decoded.allSatisfy(Self.acceptable) else { throw Failure.unsupportedSchema }
        loadedArchive.prune(at: now())
        archive = loadedArchive
        minutes = loadedMinutes
        coarse = loadedCoarse
        let rolled = roll(decoded, at: now())
        entries = rolled.raw
        return rolled.raw
    }
    public func storedBuckets() -> (minutes: [HistoryBucket], coarse: [HistoryBucket]) { (minutes, coarse) }
    public func append(_ entry: HistoryEntry) throws {
        guard Self.acceptable(entry) else { throw Failure.unsupportedSchema }
        var all = try load()
        all.append(entry)
        archive.append(entry)
        let rolled = roll(all, at: max(now(), entry.observedAt))
        entries = rolled.raw
        try persist(rolled.raw)
    }
    public func replace(_ replacement: [HistoryEntry]) throws {
        guard replacement.allSatisfy(Self.acceptable) else { throw Failure.unsupportedSchema }
        let segment = HistoryRollup.currentSegment(replacement)
        var replacementArchive = LongTermHistory()
        for entry in segment { replacementArchive.append(entry) }
        replacementArchive.prune(at: now())
        let rolled = HistoryRollup.roll(entries: replacement, now: now(), rawLimit: limit)
        try persist(rolled.raw, archive: replacementArchive, minutes: rolled.minutes, coarse: rolled.coarse)
        archive = replacementArchive
        minutes = rolled.minutes
        coarse = rolled.coarse
        entries = rolled.raw
    }
    /// Persists retained observations, including writes that previously failed.
    public func flush() throws {
        try persist(load())
    }

    public func clear() throws {
        try persist([], archive: LongTermHistory(), minutes: [], coarse: [])
        archive = LongTermHistory()
        minutes = []
        coarse = []
        entries = []
    }
    private static func acceptable(_ entry: HistoryEntry) -> Bool {
        entry.schemaVersion == 1 && entry.observedAt.timeIntervalSince1970.isFinite &&
        (entry.networkKind == nil || NetworkKind.normalize(entry.networkKind) != nil)
    }
    private func roll(_ value: [HistoryEntry], at date: Date) -> RolledHistory {
        let rolled = HistoryRollup.roll(entries: value, minuteBuckets: minutes, coarseBuckets: coarse, now: date, rawLimit: limit)
        minutes = rolled.minutes
        coarse = rolled.coarse
        return rolled
    }
    public func longTermHistory() throws -> LongTermHistory {
        _ = try load()
        archive.prune(at: now())
        return archive
    }
    private func persist(_ value: [HistoryEntry], archive replacement: LongTermHistory? = nil,
                         minutes replacementMinutes: [HistoryBucket]? = nil, coarse replacementCoarse: [HistoryBucket]? = nil) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(Envelope(entries: value, archive: replacement ?? archive,
                                                     minutes: replacementMinutes ?? minutes, coarse: replacementCoarse ?? coarse))
        guard data.count <= 8*1024*1024 else { throw Failure.oversized }
        try data.write(to: url, options: .atomic)
    }
}
public struct ObservationInterval: Equatable, Sendable {
    public let from, to: Date
    public let status: ProbeStatus
}
public enum HistoryCoverage {
    public static func isFresh(_ entry: HistoryEntry, now: Date) -> Bool {
        let age = now.timeIntervalSince(entry.observedAt); return age >= 0 && age <= entry.coverageLifetime
    }
    public static func intervals(entries: [HistoryEntry], now: Date) -> [ObservationInterval] {
        // Arrival order is intentional: a clock rollback starts a new segment, never reorders observations.
        var segment: [HistoryEntry] = []
        for entry in entries {
            if let last = segment.last, entry.observedAt < last.observedAt { segment.removeAll() }
            segment.append(entry)
        }
        segment = segment.filter { $0.observedAt <= now }
        var result: [ObservationInterval] = []
        for (index, entry) in segment.enumerated() {
            let end = index + 1 < segment.count ? segment[index+1].observedAt : now
            let freshEnd = min(end, entry.observedAt.addingTimeInterval(entry.coverageLifetime))
            if freshEnd > entry.observedAt { result.append(.init(from: entry.observedAt, to: freshEnd, status: entry.status)) }
            if end > freshEnd { result.append(.init(from: freshEnd, to: end, status: .unknown)) }
        }
        return result
    }
}
