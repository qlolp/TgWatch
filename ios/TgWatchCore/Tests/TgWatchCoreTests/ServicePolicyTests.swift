import Foundation
import Testing
@testable import TgWatchCore

struct ServicePolicyTests {
    @Test func privateAddressesRejectCredentialsAndNonHTTPS() {
        for address in ["http://example.com", "https://user:secret@example.com", "https://example.com?a=1", "https://example.com/#token", "https://"] {
            #expect(!ServiceDefinition.validAddress(address))
        }
        #expect(ServiceDefinition.validAddress("https://example.com/health"))
    }
    @Test func healthRequiresBothCodeAndStringValue() {
        let definition = ServiceDefinition(id: "custom", kind: .custom, address: "https://example.com/", expectedCode: 200,
                                           jsonPath: "status.indicator", expectedValue: "none")
        let good = Data(#"{"status":{"indicator":"none"}}"#.utf8)
        #expect(definition.accepts(code: 200, body: good))
        #expect(!definition.accepts(code: 503, body: good))
        #expect(!definition.accepts(code: 200, body: Data(#"{"status":{"indicator":true}}"#.utf8)))
        #expect(!definition.accepts(code: 200, body: Data(repeating: 32, count: 32_769)))
        let matrix = ServiceDefinition.defaults[1]
        #expect(!matrix.accepts(code: 200, body: Data("{}".utf8)))
        #expect(matrix.accepts(code: 200, body: Data(#"{"versions":["v1.1"]}"#.utf8)))
    }
    @Test func recoveryRequiresContinuousObservedImpairment() {
        let start = Date(timeIntervalSince1970: 1000)
        var policy = AlertTransition()
        #expect(policy.observe(.ok, at: start, validFor: 30) == nil)
        #expect(policy.observe(.down, at: start.addingTimeInterval(10), validFor: 30) == .down)
        #expect(policy.observe(.down, at: start.addingTimeInterval(20), validFor: 30) == nil)
        #expect(policy.observe(.partial, at: start.addingTimeInterval(30), validFor: 30) == .partial)
        #expect(policy.observe(.ok, at: start.addingTimeInterval(40), validFor: 30) == .ok)
        _ = policy.observe(.down, at: start.addingTimeInterval(50), validFor: 30)
        #expect(policy.observe(.ok, at: start.addingTimeInterval(90), validFor: 30) == nil)
        _ = policy.observe(.down, at: start.addingTimeInterval(100), validFor: 30)
        #expect(policy.observe(.ok, at: start, validFor: 30) == nil)
    }
    @Test func genericStatusDoesNotPretendMTProtoSucceeded() {
        let result = ProbeSnapshot(epoch: 0, startedAt: Date(), finishedAt: Date(), evidence: .init(webConfirmed: true), measuredStatus: .ok)
        #expect(result.status == .ok)
        #expect(!result.evidence.mtprotoConfirmed)
    }
}
