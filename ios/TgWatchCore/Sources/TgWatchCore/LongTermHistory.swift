import Foundation

/// UTC daily totals are bounded independently of the seven-day raw observation limit.
public struct HistoryDayTotals: Codable, Equatable, Identifiable, Sendable {
    public var id: Date { day }
    public let day: Date
    public var checks: Int = 0
    public var ok: Double = 0
    public var partial: Double = 0
    public var down: Double = 0
    public var offline: Double = 0

    public var assessed: Double { ok + partial + down }
    public var observed: Double { assessed + offline }
    public var availability: Double? { assessed > 0 ? ok / assessed : nil }
}

public struct LongTermHistory: Codable, Equatable, Sendable {
    public private(set) var days: [HistoryDayTotals] = []
    private var pending: HistoryEntry?
    public init() {}

    public static func dayStart(_ date: Date) -> Date {
        Date(timeIntervalSince1970: floor(date.timeIntervalSince1970 / 86400) * 86400)
    }

    mutating func append(_ entry: HistoryEntry) {
        if let previous = pending {
            if entry.observedAt < previous.observedAt {
                // Match raw analytics: a wall-clock rollback starts a new reporting segment.
                days.removeAll()
            } else {
                addCoverage(previous, until: entry.observedAt)
            }
        }
        let day = Self.dayStart(entry.observedAt)
        let index = index(for: day)
        days[index].checks += 1
        pending = entry
        prune(at: entry.observedAt)
    }

    public func report(days count: Int, at now: Date) -> LongTermReport {
        let count = max(1, min(90, count))
        let from = Self.dayStart(now).addingTimeInterval(-Double(count - 1) * 86400)
        // Aggregates cannot be clipped within a day after a clock rollback. Wait for a new segment.
        if let pending, pending.observedAt > now { return LongTermReport(from: from, to: now, days: []) }
        var snapshot = self
        if let pending, pending.observedAt <= now { snapshot.addCoverage(pending, until: now) }
        let selected = snapshot.days.filter { $0.day >= from && $0.day <= now }
        return LongTermReport(from: from, to: now, days: selected)
    }

    mutating func prune(at now: Date) {
        let cutoff = Self.dayStart(now).addingTimeInterval(-89 * 86400)
        days.removeAll { $0.day < cutoff }
    }

    var valid: Bool {
        days.count <= 90 && Set(days.map(\.day)).count == days.count && days.allSatisfy {
            $0.day.timeIntervalSince1970.isFinite && Self.dayStart($0.day) == $0.day &&
            $0.checks >= 0 && $0.checks <= 10_000_000 &&
            [$0.ok, $0.partial, $0.down, $0.offline].allSatisfy { $0.isFinite && $0 >= 0 } &&
            $0.observed <= 86400.001
        } && (pending.map { $0.schemaVersion == 1 && $0.observedAt.timeIntervalSince1970.isFinite } ?? true)
    }

    private mutating func index(for day: Date) -> Int {
        if let index = days.firstIndex(where: { $0.day == day }) { return index }
        days.append(HistoryDayTotals(day: day))
        return days.count - 1
    }

    private mutating func addCoverage(_ entry: HistoryEntry, until end: Date) {
        guard entry.status != .unknown else { return }
        let end = min(end, entry.observedAt.addingTimeInterval(entry.coverageLifetime))
        var cursor = entry.observedAt
        while cursor < end {
            let day = Self.dayStart(cursor)
            let upper = min(end, day.addingTimeInterval(86400))
            let duration = upper.timeIntervalSince(cursor)
            let index = index(for: day)
            switch entry.status {
            case .ok: days[index].ok += duration
            case .partial: days[index].partial += duration
            case .down: days[index].down += duration
            case .noNetwork: days[index].offline += duration
            case .unknown: break
            }
            cursor = upper
        }
    }
}

public struct LongTermReport: Sendable {
    public let from: Date
    public let to: Date
    public let days: [HistoryDayTotals]
    public var checks: Int { days.reduce(0) { $0 + $1.checks } }
    public var availability: Double? {
        let assessed = days.reduce(0) { $0 + $1.assessed }
        return assessed > 0 ? days.reduce(0) { $0 + $1.ok } / assessed : nil
    }
    public var coverage: Double {
        let duration = to.timeIntervalSince(from)
        return duration > 0 ? min(1, days.reduce(0) { $0 + $1.observed } / duration) : 0
    }
    public var unknown: Double { max(0, to.timeIntervalSince(from) - days.reduce(0) { $0 + $1.observed }) }
}
