import Foundation
public struct HistoryEntry: Codable, Equatable, Identifiable, Sendable {
    public let id: UUID
    public let schemaVersion: Int
    public let epoch: UInt64
    public let observedAt: Date
    public let status: ProbeStatus
    public init(epoch: UInt64, observedAt: Date, status: ProbeStatus) {
        id = UUID(); schemaVersion = 1; self.epoch = epoch; self.observedAt = observedAt; self.status = status
    }
}
public actor HistoryStore {
    public enum Failure: Error { case oversized, unsupportedSchema }
    private let url: URL
    private let limit: Int
    private let now: @Sendable () -> Date
    private var entries: [HistoryEntry]?
    public init(url: URL, limit: Int = 10_000, now: @escaping @Sendable () -> Date = { Date() }) { self.url = url; self.limit = max(1, min(limit, 10_000)); self.now = now }
    public func load() throws -> [HistoryEntry] {
        if let entries { let retained = prune(entries, at: now()); self.entries = retained; return retained }
        guard FileManager.default.fileExists(atPath: url.path) else { entries = []; return [] }
        let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
        let data = try handle.read(upToCount: 8*1024*1024+1) ?? Data()
        guard data.count <= 8*1024*1024 else { throw Failure.oversized }
        let decoded = try JSONDecoder().decode([HistoryEntry].self, from: data)
        guard decoded.allSatisfy({ $0.schemaVersion == 1 && $0.observedAt.timeIntervalSince1970.isFinite }) else { throw Failure.unsupportedSchema }
        entries = prune(decoded, at: now()); return entries!
    }
    public func append(_ entry: HistoryEntry) throws {
        var all = try load()
        all.append(entry)
        // Bound memory even if the disk remains unavailable; never discard a dirty batch on retry.
        all = prune(all, at: max(now(), entry.observedAt))
        entries = all
        try persist(all)
    }
    public func clear() throws {
        try persist([]) // Only explicit clear can replace an unreadable file.
        entries = []
    }
    private func prune(_ value: [HistoryEntry], at date: Date) -> [HistoryEntry] {
        let cutoff = date.addingTimeInterval(-7*86400)
        return Array(value.filter { $0.observedAt >= cutoff }.suffix(limit))
    }
    private func persist(_ value: [HistoryEntry]) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(value)
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
        let age = now.timeIntervalSince(entry.observedAt); return age >= 0 && age <= 120
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
            let freshEnd = min(end, entry.observedAt.addingTimeInterval(120))
            if freshEnd > entry.observedAt { result.append(.init(from: entry.observedAt, to: freshEnd, status: entry.status)) }
            if end > freshEnd { result.append(.init(from: freshEnd, to: end, status: .unknown)) }
        }
        return result
    }
}
