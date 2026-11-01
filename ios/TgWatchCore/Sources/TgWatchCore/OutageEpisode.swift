import Foundation

public struct OutageEpisode: Identifiable, Sendable {
    public enum Ending: Sendable { case recovered, interrupted, ongoing }
    public var id: Date { from }
    public let from: Date
    public let to: Date
    public let startObserved: Bool
    public let ending: Ending
    public var duration: TimeInterval { to.timeIntervalSince(from) }
}

extension HistoryAnalytics {
    /// Only OK closes an outage as recovered. Partial continues it; missing/offline time breaks it.
    static func episodes(intervals: [ObservationInterval], from lower: Date, to: Date) -> [OutageEpisode] {
        var result: [OutageEpisode] = []
        var start: Date?
        var startObserved = false
        var previous: ObservationInterval?
        for interval in intervals {
            if interval.status == .down, start == nil {
                start = interval.from
                startObserved = previous.map {
                    $0.to == interval.from && ($0.status == .ok || $0.status == .partial)
                } ?? false
            }
            if let began = start, interval.status != .down && interval.status != .partial {
                if interval.from > lower {
                    result.append(.init(from: max(lower, began), to: interval.from,
                                        startObserved: startObserved && began >= lower,
                                        ending: interval.status == .ok ? .recovered : .interrupted))
                }
                start = nil
            }
            previous = interval
        }
        if let start, to > max(lower, start) {
            result.append(.init(from: max(lower, start), to: to, startObserved: startObserved && start >= lower, ending: .ongoing))
        }
        return result
    }

    /// Excludes episodes clipped by the reporting window, gaps, clock changes, or offline periods.
    public var meanTimeToRecovery: TimeInterval? {
        let complete = outageEpisodes.filter { $0.startObserved && $0.ending == .recovered }
        guard !complete.isEmpty else { return nil }
        return complete.reduce(0) { $0 + $1.duration } / Double(complete.count)
    }
}
