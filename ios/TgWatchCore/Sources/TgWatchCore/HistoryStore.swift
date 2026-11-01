import Foundation
public struct HistoryEntry: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let schemaVersion: Int
    public let epoch: UInt64
    public let observedAt: Date
    public let status: ProbeStatus
    public let validForSeconds: Double?
    public let latencyMilliseconds: Double?
    public var coverageLifetime: Double { min(600, max(0.001, validForSeconds ?? 120)) }
    public init(epoch: UInt64, observedAt: Date, status: ProbeStatus, validForSeconds: Double? = nil, latencyMilliseconds: Double? = nil) {
        self.latencyMilliseconds = latencyMilliseconds.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
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
    private struct Envelope: Codable {
        let schemaVersion: Int
        let entries: [HistoryEntry]
        let archive: LongTermHistory
    }
    public init(url: URL, limit: Int = 10_000, now: @escaping @Sendable () -> Date = { Date() }) { self.url = url; self.limit = max(1, min(limit, 10_000)); self.now = now }
    public func load() throws -> [HistoryEntry] {
        if let entries { let retained = prune(entries, at: now()); self.entries = retained; return retained }
        guard FileManager.default.fileExists(atPath: url.path) else { entries = []; return [] }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        let data = try handle.read(upToCount: 8*1024*1024+1) ?? Data()
        guard data.count <= 8*1024*1024 else { throw Failure.oversized }
        let decoded: [HistoryEntry]
        var loadedArchive = LongTermHistory()
        if data.first(where: { ![9, 10, 13, 32].contains($0) }) == 91 {
            decoded = try JSONDecoder().decode([HistoryEntry].self, from: data)
            for entry in decoded {
                guard entry.observedAt.timeIntervalSince1970.isFinite else { throw Failure.unsupportedSchema }
                loadedArchive.append(entry)
            }
        } else {
            let envelope = try JSONDecoder().decode(Envelope.self, from: data)
            guard envelope.schemaVersion == 2, envelope.archive.valid else { throw Failure.unsupportedSchema }
            decoded = envelope.entries
            loadedArchive = envelope.archive
        }
        guard decoded.allSatisfy({ $0.schemaVersion == 1 && $0.observedAt.timeIntervalSince1970.isFinite }) else { throw Failure.unsupportedSchema }
        loadedArchive.prune(at: now())
        archive = loadedArchive
        entries = prune(decoded, at: now()); return entries!
    }
    public func append(_ entry: HistoryEntry) throws {
        guard entry.schemaVersion == 1, entry.observedAt.timeIntervalSince1970.isFinite else { throw Failure.unsupportedSchema }
        var all = try load()
        all.append(entry)
        archive.append(entry)
        // Bound memory even if the disk remains unavailable; never discard a dirty batch on retry.
        all = prune(all, at: max(now(), entry.observedAt))
        entries = all
        try persist(all)
    }
    public func replace(_ replacement: [HistoryEntry]) throws {
        guard replacement.allSatisfy({ $0.schemaVersion == 1 && $0.observedAt.timeIntervalSince1970.isFinite }) else { throw Failure.unsupportedSchema }
        let retained = prune(replacement, at: now())
        var replacementArchive = LongTermHistory()
        for entry in replacement { replacementArchive.append(entry) }
        replacementArchive.prune(at: now())
        try persist(retained, archive: replacementArchive)
        archive = replacementArchive
        entries = retained
    }
    public func clear() throws {
        try persist([], archive: LongTermHistory()) // Only explicit clear can replace an unreadable file.
        archive = LongTermHistory()
        entries = []
    }
    private func prune(_ value: [HistoryEntry], at date: Date) -> [HistoryEntry] {
        let cutoff = date.addingTimeInterval(-7*86400)
        return Array(value.filter { $0.observedAt >= cutoff }.suffix(limit))
    }
    public func longTermHistory() throws -> LongTermHistory {
        _ = try load()
        archive.prune(at: now())
        return archive
    }
    private func persist(_ value: [HistoryEntry], archive replacement: LongTermHistory? = nil) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(Envelope(schemaVersion: 2, entries: value, archive: replacement ?? archive))
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
