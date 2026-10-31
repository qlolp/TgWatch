import Foundation
public struct HistoryIncident: Identifiable, Sendable {
    public let id: UUID
    public let status: ProbeStatus
    public let startedAt, lastObservedAt: Date
    public let observations: Int
    public let recoveredAt: Date?
    public var confirmed: Bool { observations >= 2 }
}
/// Sampled intervals only: no extrapolation before/after a pair of compatible observations.
public struct HistoryReport: Sendable {
    public let observations: [HistoryEntry]
    public let incidents: [HistoryIncident]
    public let from, until: Date
    public let target: ServiceTarget
    private let durations: [ProbeStatus: TimeInterval]
    private let currentIDs: Set<UUID>
    public var coveredSeconds: TimeInterval { durations.filter { $0.key != .unknown }.values.reduce(0,+) }
    public func seconds(_ status: ProbeStatus) -> TimeInterval { durations[status, default: 0] }
    public init(entries: [HistoryEntry], from: Date, until: Date, target: ServiceTarget = .telegram) {
        self.target = target
        self.from = from; self.until = until
        guard from.timeIntervalSince1970.isFinite, until.timeIntervalSince1970.isFinite, until >= from else {
            observations = []; incidents = []; durations = [:]; currentIDs = []; return
        }
        var seen = Set<UUID>()
        let unique = entries.suffix(10_000).filter { $0.targetID == target && $0.observedAt.timeIntervalSince1970.isFinite && seen.insert($0.id).inserted }
        observations = unique.filter { $0.observedAt >= from && $0.observedAt <= until }
        var segment: [HistoryEntry] = []
        for row in unique {
            if let last = segment.last, row.observedAt < last.observedAt { segment.removeAll() }
            segment.append(row)
        }
        segment = segment.filter { $0.observedAt <= until }
        currentIDs = Set(segment.map(\.id))
        var sums: [ProbeStatus: TimeInterval] = [.unknown: until.timeIntervalSince(from)]
        for (left,right) in zip(segment,segment.dropFirst()) where Self.joins(left,right) {
            let duration = max(0,min(until,right.observedAt).timeIntervalSince(max(from,left.observedAt)))
            if left.status != .unknown {
                sums[left.status,default:0] += duration; sums[.unknown,default:0] -= duration
            }
        }
        durations = sums
        var events: [HistoryIncident] = []
        var index = 0
        while index < segment.count {
            let first = segment[index]
            guard first.status == .down || first.status == .noNetwork else { index += 1; continue }
            var end = index
            while end+1 < segment.count && segment[end+1].status == first.status && Self.joins(segment[end],segment[end+1]) { end += 1 }
            let last = segment[end]
            var recovery: Date?
            var nextIndex = end+1
            var successful = 0
            while nextIndex < segment.count && Self.joins(segment[nextIndex-1],segment[nextIndex]) {
                let next = segment[nextIndex]
                // A later negative run owns its own recovery. Uncertain observations
                // reset confirmation but do not erase later evidence of recovery.
                if next.status == .down || next.status == .noNetwork { break }
                successful = next.status == .ok ? successful+1 : 0
                if successful == 2 { recovery = next.observedAt; break }
                nextIndex += 1
            }
            if last.observedAt >= from && first.observedAt <= until {
                events.append(.init(id:first.id,status:first.status,startedAt:first.observedAt,lastObservedAt:last.observedAt,
                                    observations:end-index+1,recoveredAt:recovery))
            }
            index = end+1
        }
        incidents = Array(events.suffix(200))
    }
    private static func joins(_ left: HistoryEntry, _ right: HistoryEntry) -> Bool {
        let gap = right.observedAt.timeIntervalSince(left.observedAt)
        return left.sessionID != nil && left.sessionID == right.sessionID && left.epoch == right.epoch && gap > 0 && gap <= 120
    }
    public var csv: String {
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime,.withFractionalSeconds]
        let header = "target_id,observed_at_utc,status,epoch,session_id,in_current_statistics\n"
        return header + observations.map {
            "\($0.targetID.rawValue),\(formatter.string(from:$0.observedAt)),\($0.status.rawValue),\($0.epoch),\($0.sessionID?.uuidString ?? ""),\(currentIDs.contains($0.id))\n"
        }.joined()
    }
}
