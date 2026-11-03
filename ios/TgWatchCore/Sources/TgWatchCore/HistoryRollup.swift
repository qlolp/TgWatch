import Foundation

/// One aligned minute or five-minute bucket of checks that already happened.
/// Counts are observations, not invented coverage.
public struct HistoryBucket: Codable, Equatable, Sendable, Identifiable {
    public let start: Date
    public let resolution: TimeInterval
    public var ok: Int
    public var fail: Int
    public var offline: Int
    public var partial: Int
    public var unknown: Int
    public var latencySum: Double
    public var latencyCount: Int

    public var id: TimeInterval { start.timeIntervalSince1970 + resolution }
    public var total: Int { ok + fail + offline + partial + unknown }
    public var end: Date { start.addingTimeInterval(resolution) }

    public var valid: Bool {
        (resolution == HistoryRollup.minute || resolution == HistoryRollup.coarse) &&
        start.timeIntervalSince1970.isFinite && HistoryRollup.isAligned(start, resolution: resolution) &&
        [ok, fail, offline, partial, unknown, latencyCount].allSatisfy { $0 >= 0 } &&
        total <= HistoryRollup.maxChecksPerBucket && latencyCount <= ok &&
        latencySum.isFinite && latencySum >= 0
    }

    public var dominantStatus: ProbeStatus {
        let ranked: [(ProbeStatus, Int)] = [(.ok, ok), (.down, fail), (.noNetwork, offline), (.partial, partial), (.unknown, unknown)]
        return ranked.max { $0.1 < $1.1 }?.0 ?? .unknown
    }

    public init(start: Date, resolution: TimeInterval, ok: Int = 0, fail: Int = 0, offline: Int = 0,
                partial: Int = 0, unknown: Int = 0, latencySum: Double = 0, latencyCount: Int = 0) {
        self.start = start; self.resolution = resolution
        self.ok = ok; self.fail = fail; self.offline = offline; self.partial = partial; self.unknown = unknown
        self.latencySum = latencySum; self.latencyCount = latencyCount
    }

    func adding(_ entry: HistoryEntry) -> HistoryBucket {
        var copy = self
        switch entry.status {
        case .ok:
            copy.ok += 1
            if let latency = entry.latencyMilliseconds, latency.isFinite, latency >= 0, latency <= 600_000 {
                copy.latencySum += latency
                copy.latencyCount += 1
            }
        case .down: copy.fail += 1
        case .noNetwork: copy.offline += 1
        case .partial: copy.partial += 1
        case .unknown: copy.unknown += 1
        }
        return copy
    }

    func merging(_ other: HistoryBucket) -> HistoryBucket {
        var copy = self
        copy.ok += other.ok; copy.fail += other.fail; copy.offline += other.offline
        copy.partial += other.partial; copy.unknown += other.unknown
        copy.latencySum += other.latencySum; copy.latencyCount += other.latencyCount
        return copy
    }
}

public struct RolledHistory: Equatable, Sendable {
    public let raw: [HistoryEntry]
    public let minutes: [HistoryBucket]
    public let coarse: [HistoryBucket]
}

/// Raw checks for 7 days, minute buckets through 30 days, five-minute buckets through 90 days.
/// A backward timestamp starts a new segment and drops older buckets. Nothing is invented.
public enum HistoryRollup {
    public static let minute: TimeInterval = 60
    public static let coarse: TimeInterval = 300
    public static let rawWindow: TimeInterval = 7 * 86_400
    public static let minuteWindow: TimeInterval = 30 * 86_400
    public static let coarseWindow: TimeInterval = 90 * 86_400
    public static let maxChecksPerBucket = 1_000_000

    public static func align(_ date: Date, resolution: TimeInterval) -> Date {
        let seconds = date.timeIntervalSince1970
        guard seconds.isFinite, resolution > 0 else { return date }
        return Date(timeIntervalSince1970: floor(seconds / resolution) * resolution)
    }

    public static func isAligned(_ date: Date, resolution: TimeInterval) -> Bool {
        let seconds = date.timeIntervalSince1970
        let unit = Int64(resolution)
        guard seconds.isFinite, seconds >= 0, unit > 0 else { return false }
        let whole = Int64(seconds.rounded())
        return abs(seconds - Double(whole)) < 0.001 && whole % unit == 0
    }

    public static func roll(entries: [HistoryEntry], minuteBuckets: [HistoryBucket] = [], coarseBuckets: [HistoryBucket] = [],
                            now: Date, rawLimit: Int = 10_000) -> RolledHistory {
        let segment = currentSegment(entries)
        let reset = segment.count != entries.count
        var minuteMap: [Date: HistoryBucket] = [:]
        var coarseMap: [Date: HistoryBucket] = [:]
        if !reset {
            for bucket in minuteBuckets where bucket.valid { minuteMap[bucket.start] = bucket }
            for bucket in coarseBuckets where bucket.valid { coarseMap[bucket.start] = bucket }
        }
        let rawCutoff = now.addingTimeInterval(-rawWindow)
        let minuteCutoff = now.addingTimeInterval(-minuteWindow)
        let coarseCutoff = now.addingTimeInterval(-coarseWindow)
        var raw: [HistoryEntry] = []
        for entry in segment where entry.observedAt.timeIntervalSince1970.isFinite {
            if entry.observedAt > rawCutoff { raw.append(entry) }
            else if entry.observedAt > minuteCutoff { add(entry, resolution: minute, to: &minuteMap) }
            else if entry.observedAt > coarseCutoff { add(entry, resolution: coarse, to: &coarseMap) }
        }
        var keptMinutes: [Date: HistoryBucket] = [:]
        for (start, bucket) in minuteMap {
            if start <= minuteCutoff {
                guard start > coarseCutoff else { continue }
                let aligned = align(start, resolution: coarse)
                let base = coarseMap[aligned] ?? HistoryBucket(start: aligned, resolution: coarse)
                coarseMap[aligned] = base.merging(HistoryBucket(
                    start: aligned, resolution: coarse, ok: bucket.ok, fail: bucket.fail, offline: bucket.offline,
                    partial: bucket.partial, unknown: bucket.unknown, latencySum: bucket.latencySum, latencyCount: bucket.latencyCount))
            } else {
                keptMinutes[start] = bucket
            }
        }
        minuteMap = keptMinutes
        coarseMap = coarseMap.filter { $0.key > coarseCutoff && $0.key <= minuteCutoff && $0.value.valid }
        minuteMap = minuteMap.filter(\.value.valid)
        let limit = max(1, min(rawLimit, 10_000))
        return RolledHistory(
            raw: Array(raw.suffix(limit)),
            minutes: minuteMap.values.sorted { $0.start < $1.start },
            coarse: coarseMap.values.sorted { $0.start < $1.start }
        )
    }

    /// Arrival-order segment after the latest backward timestamp.
    public static func currentSegment(_ entries: [HistoryEntry]) -> [HistoryEntry] {
        var current: [HistoryEntry] = []
        for entry in entries {
            if let last = current.last, entry.observedAt < last.observedAt { current.removeAll(keepingCapacity: true) }
            current.append(entry)
        }
        return current
    }

    private static func add(_ entry: HistoryEntry, resolution: TimeInterval, to map: inout [Date: HistoryBucket]) {
        let start = align(entry.observedAt, resolution: resolution)
        guard start.timeIntervalSince1970 >= 0 else { return }
        let bucket = map[start] ?? HistoryBucket(start: start, resolution: resolution)
        let next = bucket.adding(entry)
        guard next.total <= maxChecksPerBucket else { return }
        map[start] = next
    }
}

public enum NetworkKind: String, Codable, CaseIterable, Sendable {
    case wifi, cellular, wired, other, unavailable, unknown
    public static func normalize(_ value: String?) -> String? {
        guard let value, let kind = NetworkKind(rawValue: value) else { return nil }
        return kind.rawValue
    }
}

public struct NetworkBreakdown: Equatable, Sendable {
    public let counts: [String: Int]
    public init(entries: [HistoryEntry]) {
        var counts: [String: Int] = [:]
        for entry in entries {
            let key = NetworkKind.normalize(entry.networkKind) ?? NetworkKind.unknown.rawValue
            counts[key, default: 0] += 1
        }
        self.counts = counts
    }
}
