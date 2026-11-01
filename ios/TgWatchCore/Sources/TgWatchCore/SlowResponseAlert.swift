import Foundation

/// Three consecutive slow successes, hysteresis, and a monotonic 30-minute cooldown.
public struct SlowResponseAlert: Sendable {
    private var streak = 0
    private var active = false
    private var lastAlert: TimeInterval?
    private var threshold: Double?

    public init() {}

    /// A lifecycle, route, or observation gap invalidates the streak, but not the cooldown.
    public mutating func resetContinuity() {
        streak = 0
        active = false
    }

    public mutating func observe(status: ProbeStatus, latency: Double?, threshold: Double?, uptime: TimeInterval) -> Bool {
        if self.threshold != threshold {
            resetContinuity()
            self.threshold = threshold
        }
        guard let threshold, threshold.isFinite, threshold > 0, uptime.isFinite, uptime >= 0 else {
            resetContinuity()
            return false
        }
        guard status == .ok else {
            resetContinuity()
            return false
        }
        guard let latency, latency.isFinite, latency >= 0 else {
            streak = 0
            return false
        }
        if latency < threshold {
            streak = 0
            if latency < threshold * 0.6 { active = false }
            return false
        }
        streak = min(3, streak + 1)
        guard !active, streak == 3, lastAlert.map({ uptime - $0 >= 1800 }) ?? true else { return false }
        active = true
        lastAlert = uptime
        return true
    }
}
