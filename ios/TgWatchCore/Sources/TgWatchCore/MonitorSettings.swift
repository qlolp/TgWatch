import Foundation

/// Persisted settings are decoded by the UI and backup validator using the same types.
public struct AlertSettings: Codable, Equatable, Sendable {
    public var down = false
    public var partial = false
    public var recovery = false
    public var offline = false
    public var sound = false
    public var quietStart = 23 * 60
    public var quietEnd = 8 * 60
    public var quietEnabled = false
    public var slowThresholdMilliseconds: Int?
    public var mutedUntil: Date?
    public init() {}

    public var valid: Bool {
        (0..<1440).contains(quietStart) && (0..<1440).contains(quietEnd) &&
        (slowThresholdMilliseconds.map { [500, 1000, 2000, 5000].contains($0) } ?? true)
    }
    public func enabled(_ status: ProbeStatus) -> Bool {
        switch status {
        case .down: down
        case .partial: partial
        case .ok: recovery
        case .noNetwork: offline
        case .unknown: false
        }
    }
    public func audible(at date: Date) -> Bool {
        guard sound, mutedUntil.map({ $0 <= date }) ?? true else { return false }
        guard quietEnabled, quietStart != quietEnd else { return true }
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        let quiet = quietStart < quietEnd ? (quietStart..<quietEnd).contains(minute) : minute >= quietStart || minute < quietEnd
        return !quiet
    }
    enum CodingKeys: String, CodingKey { case down, partial, recovery, offline, sound, quietStart, quietEnd, quietEnabled, slowThresholdMilliseconds }
}

public struct WebhookSettings: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var address: String
    public var json: Bool
    public init(enabled: Bool = false, address: String = "", json: Bool = true) {
        self.enabled = enabled; self.address = address; self.json = json
    }
    public var valid: Bool { address.utf8.count <= 1024 && (!enabled || WebhookAddress.isValid(address)) }
}
