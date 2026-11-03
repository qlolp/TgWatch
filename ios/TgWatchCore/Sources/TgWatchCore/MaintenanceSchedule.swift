import Foundation

/// Planned window. Checks continue and the live status stays visible, but history rows and alerts are skipped.
/// The resulting gap is unobserved time, not an incident. Equal endpoints disable the window. Monday is day 0.
public struct MaintenanceWindow: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var startMinuteOfDay: Int
    public var endMinuteOfDay: Int
    public var daysMask: Int
    public var enabled: Bool

    public init(id: String = UUID().uuidString, startMinuteOfDay: Int, endMinuteOfDay: Int, daysMask: Int = 0, enabled: Bool = true) {
        self.id = id; self.startMinuteOfDay = startMinuteOfDay; self.endMinuteOfDay = endMinuteOfDay
        self.daysMask = daysMask; self.enabled = enabled
    }

    public var valid: Bool {
        !id.isEmpty && id.count <= 36 && id.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" } &&
        (0...1439).contains(startMinuteOfDay) && (0...1440).contains(endMinuteOfDay) && (0...0b1111111).contains(daysMask)
    }

    public func covers(minuteOfDay: Int, weekdayMondayZero: Int) -> Bool {
        guard enabled, valid, startMinuteOfDay != endMinuteOfDay, (0...6).contains(weekdayMondayZero) else { return false }
        if daysMask != 0 && (daysMask >> weekdayMondayZero) & 1 == 0 { return false }
        if startMinuteOfDay < endMinuteOfDay { return (startMinuteOfDay..<endMinuteOfDay).contains(minuteOfDay) }
        return minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
    }
}

public enum MaintenanceSchedule {
    public static let maxWindows = 8

    public static func active(_ windows: [MaintenanceWindow], at date: Date, calendar: Calendar = .current) -> Bool {
        guard windows.contains(where: \.enabled) else { return false }
        let minute = calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date)
        let weekday = (calendar.component(.weekday, from: date) + 5) % 7
        return windows.contains { $0.covers(minuteOfDay: minute, weekdayMondayZero: weekday) }
    }

    public static func sanitized(_ windows: [MaintenanceWindow]) -> [MaintenanceWindow]? {
        guard windows.count <= maxWindows, windows.allSatisfy(\.valid), Set(windows.map(\.id)).count == windows.count else { return nil }
        return windows
    }
}
