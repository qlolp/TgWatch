import Foundation

public enum ConnectionDiagnosis: String, Sendable {
    case stale, healthy, slow, partial, serviceDown, offline, inconclusive
    public static func reason(_ snapshot: ProbeSnapshot?, fresh: Bool) -> Self {
        guard fresh, let snapshot else { return .stale }
        switch snapshot.status {
        case .noNetwork: return .offline
        case .down: return .serviceDown
        case .partial: return .partial
        case .unknown: return .inconclusive
        case .ok:
            if let latency = snapshot.latencyMilliseconds, latency.isFinite, latency >= 1000 { return .slow }
            return .healthy
        }
    }
}

public enum VPNComparison: String, Sendable {
    case improved, worsened, unchanged, inconclusive
    public static func outcome(before: ProbeStatus, after: ProbeStatus) -> Self {
        let ranks: [ProbeStatus: Int] = [.down: 0, .partial: 1, .ok: 2]
        guard let first = ranks[before], let second = ranks[after] else { return .inconclusive }
        return second > first ? .improved : second < first ? .worsened : .unchanged
    }
}
