import Foundation
import Testing
@testable import TgWatchCore

struct AdvancedHealthTests {
    private func target() -> ServiceDefinition {
        .init(id: "custom", kind: .custom, address: "https://example.com/health", expectedCode: 200,
              bodySubstring: "healthy", additionalJSONConditions: [
                .init(path: "status.indicator", expectedValue: "none"),
                .init(path: "message", expectedValue: "healthy")
              ])
    }
    @Test func codeBodyAndAllConditionsMustMatch() {
        let definition = target()
        let good = Data(#"{"status":{"indicator":"none"},"message":"healthy"}"#.utf8)
        #expect(definition.accepts(code: 200, body: good))
        #expect(!definition.accepts(code: 503, body: good))
        #expect(!definition.accepts(code: 200, body: Data(#"{"status":{"indicator":"major"},"message":"healthy"}"#.utf8)))
        var text = definition
        text.bodySubstring = "Healthy"
        #expect(!text.accepts(code: 200, body: good))
        text.additionalJSONConditions = []
        text.bodySubstring = "ready"
        #expect(text.accepts(code: 200, body: Data("server ready".utf8)))
        #expect(!text.accepts(code: 200, body: Data([0xFF])))
    }
    @Test func rejectAmbiguousMalformedAndDeepJSON() {
        let definition = target()
        for text in [
            #"{"status":{"indicator":"major","indicator":"none"},"message":"healthy"}"#,
            #"{"status":{"indicator":"none"},"message":"broken","mess\u0061ge":"healthy"}"#,
            #"{"status":{"indicator":"none"},"message":"healthy",}"#,
            #"{"status":{"indicator":"none"},"message":"healthy"}garbage"#,
            #"{"status":{"indicator":"none"},"message":"healthy","other":{"a":1,"a":2}}"#
        ] { #expect(!definition.accepts(code: 200, body: Data(text.utf8))) }
        #expect(StrictHealthJSON.decode(Data((String(repeating: "[", count: 10) + "0" + String(repeating: "]", count: 10)).utf8)) != nil)
        #expect(StrictHealthJSON.decode(Data((String(repeating: "[", count: 11) + "0" + String(repeating: "]", count: 11)).utf8)) == nil)
        #expect(StrictHealthJSON.decode(Data(#"{"escaped":"a\"b","array":[true,null,1.2e3]}"#.utf8)) != nil)
    }
    @Test func validateHeaderLimitsAndInjection() {
        var definition = target()
        definition.requestHeaders = [.init(name: "Authorization", value: "Bearer secret")]
        #expect(definition.valid)
        for name in ["Host", "Content-Length", "Accept-Encoding", "bad name", "x\r\ninject"] {
            definition.requestHeaders = [.init(name: name, value: "x")]
            #expect(!definition.valid)
        }
        definition.requestHeaders = [.init(name: "X-Key", value: "x"), .init(name: "x-key", value: "y")]
        #expect(!definition.valid)
        definition.requestHeaders = [.init(name: "X-Key", value: "x\r\nAuthorization: y")]
        #expect(!definition.valid)
        definition.requestHeaders = (0..<9).map { .init(name: "X-Key-\($0)", value: "x") }
        #expect(!definition.valid)
        definition.requestHeaders = []
        definition.additionalJSONConditions = (0..<5).map { .init(path: "key\($0)", expectedValue: "") }
        #expect(!definition.valid)
    }
    @Test func existingDefinitionsDecodeWithoutNewKeys() throws {
        let data = Data(#"{"id":"custom","kind":"custom","revision":"00000000-0000-0000-0000-000000000001","address":"https://example.com/health","jsonPath":"status","expectedValue":""}"#.utf8)
        let old = try JSONDecoder().decode(ServiceDefinition.self, from: data)
        #expect(old.valid)
        #expect(old.accepts(code: 200, body: Data(#"{"status":""}"#.utf8)))
        #expect(old.requestHeaders == nil)
        let new = target()
        #expect(try JSONDecoder().decode(ServiceDefinition.self, from: JSONEncoder().encode(new)) == new)
    }
}
