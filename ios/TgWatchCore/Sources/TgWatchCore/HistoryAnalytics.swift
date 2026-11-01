import Foundation

/// Bounded observations: missing time never contributes to availability.
public struct HistoryAnalytics: Sendable {
    public let from: Date
    public let to: Date
    public let intervals: [ObservationInterval]
    public let checks: Int
    public let outageCount: Int
    public let longestOutage: TimeInterval

    /// Arrival-order segment after the latest backward timestamp. Earlier segments stay archived.
    public static func currentClockSegment(entries: [HistoryEntry]) -> [HistoryEntry] {
        var current: [HistoryEntry] = []
        for entry in entries {
            if let last = current.last, entry.observedAt < last.observedAt {
                current.removeAll(keepingCapacity: true)
            }
            current.append(entry)
        }
        return current
    }

    public init(entries: [HistoryEntry], from: Date, to: Date) {
        let lower = min(from, to)
        self.from = lower
        self.to = to
        let current = Self.currentClockSegment(entries: entries)
        checks = current.filter { $0.observedAt >= lower && $0.observedAt <= to }.count
        var clipped: [ObservationInterval] = []
        var cursor = lower
        for value in HistoryCoverage.intervals(entries: current, now: to) {
            let start = max(lower, value.from), end = min(to, value.to)
            guard end > start else { continue }
            if start > cursor { clipped.append(.init(from: cursor, to: start, status: .unknown)) }
            clipped.append(.init(from: start, to: end, status: value.status))
            cursor = end
        }
        if cursor < to { clipped.append(.init(from: cursor, to: to, status: .unknown)) }
        intervals = clipped
        var count = 0
        var duration: TimeInterval = 0
        var longest: TimeInterval = 0
        for value in clipped {
            if value.status == .down || (value.status == .partial && duration > 0) {
                if duration == 0 { count += 1 }
                duration += value.to.timeIntervalSince(value.from)
                longest = max(longest, duration)
            } else { duration = 0 }
        }
        outageCount = count
        longestOutage = longest
    }

    public func duration(_ status: ProbeStatus) -> TimeInterval {
        intervals.filter { $0.status == status }.reduce(0) { $0 + $1.to.timeIntervalSince($1.from) }
    }

    public var availability: Double? {
        let assessed = duration(.ok) + duration(.partial) + duration(.down)
        return assessed > 0 ? duration(.ok) / assessed : nil
    }

    public var coverage: Double {
        let window = to.timeIntervalSince(from)
        return window > 0 ? max(0, min(1, 1 - duration(.unknown) / window)) : 0
    }

    /// Android's legacy Telegram CSV columns; latency is unavailable, not zero.
    public var csv: String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var rows = ["from_utc,until_utc,status,duration_ms,latency_ms"]
        for value in intervals {
            let status: String
            switch value.status {
            case .ok: status = "OK"
            case .partial: status = "PARTIAL"
            case .down: status = "TG_DOWN"
            case .noNetwork: status = "NO_NETWORK"
            case .unknown: status = "UNKNOWN"
            }
            let start = formatter.string(from: value.from), end = formatter.string(from: value.to)
            // Calculate from serialized millisecond timestamps so Android's strict reader agrees.
            guard let startDate = formatter.date(from: start), let endDate = formatter.date(from: end) else { continue }
            let milliseconds = Int64((endDate.timeIntervalSince(startDate) * 1000).rounded())
            guard milliseconds > 0 else { continue }
            rows.append("\(start),\(end),\(status),\(milliseconds),-1")
        }
        return rows.joined(separator: "\n") + "\n"
    }

    public func chartSlices(count: Int = 24) -> [HistoryChartSlice] {
        let count = max(1, min(168, count))
        let width = to.timeIntervalSince(from) / Double(count)
        guard width > 0 else { return [] }
        let statuses: [ProbeStatus] = [.ok, .partial, .down, .noNetwork, .unknown]
        var values = Array(repeating: Array(repeating: 0.0, count: statuses.count), count: count)
        for interval in intervals {
            guard let statusIndex = statuses.firstIndex(of: interval.status) else { continue }
            let first = max(0, min(count - 1, Int(interval.from.timeIntervalSince(from) / width)))
            let last = max(first, min(count - 1, Int(interval.to.timeIntervalSince(from) / width)))
            for index in first...last {
                let lower = from.addingTimeInterval(Double(index) * width)
                let upper = lower.addingTimeInterval(width)
                values[index][statusIndex] += max(0, min(upper, interval.to).timeIntervalSince(max(lower, interval.from)))
            }
        }
        return (0..<count).flatMap { index in
            statuses.indices.compactMap { statusIndex in
                let duration = values[index][statusIndex]
                return duration > 0 ? HistoryChartSlice(id: index * 5 + statusIndex,
                    date: from.addingTimeInterval((Double(index) + 0.5) * width),
                    status: statuses[statusIndex], seconds: duration) : nil
            }
        }
    }
}

public struct HistoryChartSlice: Identifiable, Sendable {
    public let id: Int
    public let date: Date
    public let status: ProbeStatus
    public let seconds: Double
}
