import Foundation
public enum ConnectionAlert: String, Sendable { case telegramLost, networkLost, recovered }
/// Two fresh, distinct observations confirm a transition. UNKNOWN/PARTIAL never confirm recovery.
public struct ConnectionAlertPolicy: Sendable {
    private var candidate: ProbeStatus?
    private var candidateCount = 0
    private var lastAt: Date?
    private var lastEpoch: UInt64?
    private var delivered: ConnectionAlert?
    public init() {}
    public mutating func confirmSubmission(_ event: ConnectionAlert) { delivered = event }
    public mutating func reset() { self = Self() }
    public mutating func observe(_ status: ProbeStatus, at: Date, epoch: UInt64) -> ConnectionAlert? {
        if let lastAt, at == lastAt { return nil }
        if lastEpoch != epoch || lastAt.map({ at < $0 || at.timeIntervalSince($0) > 120 }) == true {
            candidate = nil; candidateCount = 0
        }
        lastAt = at; lastEpoch = epoch
        guard status == .down || status == .noNetwork || status == .ok else {
            candidate = nil; candidateCount = 0; return nil
        }
        if candidate == status { candidateCount = min(2, candidateCount + 1) }
        else { candidate = status; candidateCount = 1 }
        guard candidateCount == 2 else { return nil }
        let next: ConnectionAlert = status == .down ? .telegramLost : status == .noNetwork ? .networkLost : .recovered
        if next == .recovered && (delivered == nil || delivered == .recovered) { return nil }
        guard delivered != next else { return nil }
        return next
    }
}
