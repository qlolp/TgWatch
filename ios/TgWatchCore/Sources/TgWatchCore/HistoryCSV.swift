import Foundation

public enum HistoryCSV {
    public enum Failure: Error { case invalid, oversized, wrongTarget, multipleRevisions }
    public static let legacyHeader = "from_utc,until_utc,status,duration_ms,latency_ms"
    public static let header = legacyHeader + ",clock_epoch,in_current_statistics,target_id,endpoint_revision"

    public static func export(_ analytics: HistoryAnalytics, targetID: String, entries: [HistoryEntry]) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var rows = [header]
        var byDate: [Date: Double] = [:]
        for entry in entries { if let value = entry.latencyMilliseconds { byDate[entry.observedAt] = value } }
        for interval in analytics.intervals {
            let start = formatter.string(from: interval.from), end = formatter.string(from: interval.to)
            guard let startDate = formatter.date(from: start), let endDate = formatter.date(from: end) else { continue }
            let duration = Int64((endDate.timeIntervalSince(startDate) * 1000).rounded())
            guard duration > 0 else { continue }
            let status: String
            switch interval.status {
            case .ok: status = "OK"
            case .partial: status = "PARTIAL"
            case .down: status = "DOWN"
            case .noNetwork: status = "NO_NETWORK"
            case .unknown: status = "UNKNOWN"
            }
            let latency = byDate[interval.from].flatMap { $0.isFinite && $0 >= 0 && $0 <= 600_000 ? Int64($0.rounded()) : nil } ?? -1
            // Only the selected current revision/clock segment is exported; normalize its portable identity.
            rows.append("\(start),\(end),\(status),\(duration),\(latency),0,true,\(targetID),1")
        }
        return rows.joined(separator: "\n") + "\n"
    }

    /// Validates the entire document before the caller replaces a history.
    /// This single-target import accepts legacy Telegram and current-revision v3 exports.
    public static func parse(_ data: Data, targetID: String) throws -> [HistoryEntry] {
        guard data.count <= 8 * 1024 * 1024 else { throw Failure.oversized }
        guard let text = String(data: data, encoding: .utf8) else { throw Failure.invalid }
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: true)
        guard let first = lines.first else { throw Failure.invalid }
        let legacy = first == legacyHeader
        guard legacy || first == header else { throw Failure.invalid }
        if legacy && targetID != "telegram" { throw Failure.wrongTarget }
        guard lines.count <= 20_001 else { throw Failure.oversized }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var result: [HistoryEntry] = []
        var identity: String?
        var previousEnd: Date?
        for line in lines.dropFirst() {
            let fields = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard fields.count == (legacy ? 5 : 9),
                  let start = formatter.date(from: fields[0]), let end = formatter.date(from: fields[1]),
                  let duration = Int64(fields[3]), duration >= 0,
                  Int64((end.timeIntervalSince(start) * 1000).rounded()) == duration,
                  let latency = Int64(fields[4]), (-1...600_000).contains(latency) else { throw Failure.invalid }
            if !legacy {
                guard fields[7] == targetID else { throw Failure.wrongTarget }
                guard UInt64(fields[5]) != nil, UInt64(fields[8]) != nil, fields[6] == "true" else { throw Failure.multipleRevisions }
                let rowIdentity = fields[5] + ":" + fields[8]
                if let identity, identity != rowIdentity { throw Failure.multipleRevisions }
                identity = rowIdentity
            }
            guard previousEnd.map({ start >= $0 }) ?? true else { throw Failure.invalid }
            previousEnd = end
            let status: ProbeStatus
            switch fields[2] {
            case "OK": status = .ok
            case "PARTIAL": status = .partial
            case "DOWN", "TG_DOWN": status = .down
            case "NO_NETWORK": status = .noNetwork
            case "UNKNOWN": continue
            default: throw Failure.invalid
            }
            guard (1...600_000).contains(duration) else { throw Failure.invalid }
            result.append(.init(epoch: 0, observedAt: start, status: status, validForSeconds: Double(duration) / 1000,
                                latencyMilliseconds: latency >= 0 ? Double(latency) : nil))
            guard result.count <= 10_000 else { throw Failure.oversized }
        }
        return result
    }
}
