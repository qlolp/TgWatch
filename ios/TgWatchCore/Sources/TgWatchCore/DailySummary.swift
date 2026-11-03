import Foundation

public struct DailySummarySettings: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var minuteOfDay: Int
    public init(enabled: Bool = false, minuteOfDay: Int = DailySummary.defaultMinute) {
        self.enabled = enabled; self.minuteOfDay = minuteOfDay
    }
    public var valid: Bool { (0..<1440).contains(minuteOfDay) }
}

public struct DailySummaryStats: Equatable, Sendable {
    public let checks: Int
    public let availability: Double?
    public let outageCount: Int
    public let longestOutage: TimeInterval
    public let ongoing: Bool
    public let partialSeconds: TimeInterval
    public let offlineSeconds: TimeInterval
    public let unknownSeconds: TimeInterval
    public let averageLatencyMilliseconds: Double?
}

/// Numbers for a once-a-day local notification. The app formats the text; nothing is sent off device.
public enum DailySummary {
    public static let defaultMinute = 21 * 60

    /// Nil when the window has no checks, so the caller does not invent a zero-uptime day.
    public static func stats(analytics: HistoryAnalytics, latencies: [Double]) -> DailySummaryStats? {
        guard analytics.checks > 0 else { return nil }
        let measured = latencies.filter { $0.isFinite && $0 >= 0 && $0 <= 600_000 }
        let average = measured.isEmpty ? nil : measured.reduce(0, +) / Double(measured.count)
        return DailySummaryStats(
            checks: analytics.checks,
            availability: analytics.availability,
            outageCount: analytics.outageCount,
            longestOutage: analytics.longestOutage,
            ongoing: analytics.outageEpisodes.last?.ending == .ongoing,
            partialSeconds: analytics.duration(.partial),
            offlineSeconds: analytics.duration(.noNetwork),
            unknownSeconds: analytics.duration(.unknown),
            averageLatencyMilliseconds: average
        )
    }

    /// Next local wall-clock occurrence strictly after `now`. Reapplies the hour after a DST shift.
    public static func nextDate(after now: Date, minuteOfDay: Int, calendar: Calendar = .current) -> Date {
        let minute = min(1439, max(0, minuteOfDay))
        func at(_ day: Date) -> Date {
            var parts = calendar.dateComponents([.year, .month, .day], from: day)
            parts.hour = minute / 60
            parts.minute = minute % 60
            parts.second = 0
            parts.nanosecond = 0
            return calendar.date(from: parts) ?? day
        }
        let today = at(now)
        if today > now { return today }
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: today) ?? today.addingTimeInterval(86_400)
        return at(tomorrow)
    }

    public static func due(force: Bool, optedIn: Bool, monitoring: Bool, deliveredToday: Bool) -> Bool {
        force || (optedIn && monitoring && !deliveredToday)
    }

    public static func timeLabel(_ minuteOfDay: Int) -> String {
        let minute = min(1439, max(0, minuteOfDay))
        return String(format: "%02d:%02d", minute / 60, minute % 60)
    }
}
