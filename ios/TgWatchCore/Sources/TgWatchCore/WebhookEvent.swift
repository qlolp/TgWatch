import Foundation

/// Android JSON v1 allowlist; private endpoints and response contents never enter the payload.
public struct WebhookEvent: Encodable, Sendable {
    public let serviceID: String
    public let event: String
    public let status: String?
    public let previousStatus: String?
    public let checkedAtMilliseconds: Int64?
    public let latencyMilliseconds: Double?
    public init(serviceID: String, snapshot: ProbeSnapshot?, previous: ProbeStatus?) {
        self.serviceID = serviceID
        status = snapshot.map { Self.wire($0.status) }
        previousStatus = previous.map(Self.wire)
        checkedAtMilliseconds = snapshot.map { Int64(($0.finishedAt.timeIntervalSince1970 * 1000).rounded()) }
        latencyMilliseconds = snapshot?.latencyMilliseconds
        switch snapshot?.status {
        case .down: event = "unreachable"
        case .partial: event = "partial"
        case .noNetwork: event = "network_lost"
        case .ok: event = previous == .noNetwork ? "network_restored" : "recovery"
        default: event = "test"
        }
    }
    public static func wire(_ status: ProbeStatus) -> String {
        switch status {
        case .ok: "OK"
        case .partial: "PARTIAL"
        case .down: "DOWN"
        case .noNetwork: "NO_NETWORK"
        case .unknown: "UNKNOWN"
        }
    }
    private enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version", serviceID = "service_id", event, status
        case previousStatus = "previous_status", checkedAtMilliseconds = "checked_at_ms"
        case latencyMilliseconds = "latency_ms", network
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(1, forKey: .schemaVersion)
        try c.encode(serviceID, forKey: .serviceID)
        try c.encode(event, forKey: .event)
        try c.encode(status, forKey: .status)
        try c.encode(previousStatus, forKey: .previousStatus)
        try c.encode(checkedAtMilliseconds, forKey: .checkedAtMilliseconds)
        try c.encode(latencyMilliseconds, forKey: .latencyMilliseconds)
        try c.encode("UNKNOWN", forKey: .network)
    }
}
