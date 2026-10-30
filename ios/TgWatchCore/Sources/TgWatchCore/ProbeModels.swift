import Foundation
public enum ProbeStatus: String, Codable, Sendable { case ok, partial, down, noNetwork, unknown }
public struct ProbeEvidence: Codable, Equatable, Sendable {
    public var mtprotoConfirmed, webConfirmed, controlConfirmed, pathUnavailable, primaryMeasurementsComplete: Bool
    public init(mtprotoConfirmed: Bool = false, webConfirmed: Bool = false, controlConfirmed: Bool = false,
                pathUnavailable: Bool = false, primaryMeasurementsComplete: Bool = false) {
        self.mtprotoConfirmed = mtprotoConfirmed; self.webConfirmed = webConfirmed
        self.controlConfirmed = controlConfirmed; self.pathUnavailable = pathUnavailable
        self.primaryMeasurementsComplete = primaryMeasurementsComplete
    }
}
public func classify(_ evidence: ProbeEvidence) -> ProbeStatus {
    if evidence.pathUnavailable { return .noNetwork }
    if evidence.mtprotoConfirmed && evidence.webConfirmed { return .ok }
    if evidence.mtprotoConfirmed || evidence.webConfirmed { return .partial }
    if evidence.primaryMeasurementsComplete && evidence.controlConfirmed { return .down }
    return .unknown
}
public struct ProbeSnapshot: Codable, Equatable, Sendable {
    public let epoch: UInt64
    public let startedAt, finishedAt: Date
    public let evidence: ProbeEvidence
    public var status: ProbeStatus { classify(evidence) }
    public init(epoch: UInt64, startedAt: Date, finishedAt: Date, evidence: ProbeEvidence) {
        self.epoch = epoch; self.startedAt = startedAt; self.finishedAt = finishedAt; self.evidence = evidence
    }
}
public protocol ProbeRunner: Sendable { func check(epoch: UInt64) async throws -> ProbeSnapshot }
