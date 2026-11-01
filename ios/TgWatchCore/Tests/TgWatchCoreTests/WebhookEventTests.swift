import Foundation
import Testing
@testable import TgWatchCore

struct WebhookEventTests {
    @Test func payloadMatchesAndroidAllowlistAndPreservesMeasurementTime() throws {
        let time = Date(timeIntervalSince1970: 1_700_000_000)
        let snapshot = ProbeSnapshot(epoch: 5, startedAt: time, finishedAt: time, evidence: .init(), measuredStatus: .down)
        let event = WebhookEvent(serviceID: "custom2", snapshot: snapshot, previous: .ok)
        let object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(event)) as? [String: Any])
        #expect(Set(object.keys) == Set(["schema_version", "service_id", "event", "status", "previous_status", "checked_at_ms", "latency_ms", "network"]))
        #expect(object["checked_at_ms"] as? Int64 == 1_700_000_000_000)
        #expect(object["status"] as? String == "DOWN")
        #expect(object["event"] as? String == "unreachable")
        #expect(object["latency_ms"] is NSNull)
    }
    @Test func testEventCannotClaimAvailability() throws {
        let event = WebhookEvent(serviceID: "telegram", snapshot: nil, previous: nil)
        let object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(event)) as? [String: Any])
        #expect(object["event"] as? String == "test")
        #expect(object["status"] is NSNull)
        #expect(object["checked_at_ms"] is NSNull)
    }
}
