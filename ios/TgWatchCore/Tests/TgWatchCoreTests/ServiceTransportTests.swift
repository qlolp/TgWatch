#if canImport(Network)
import Foundation
import Testing
@testable import TgWatchCore

private final class ServiceFixtureProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        let path = url.path
        if path == "/tls" { client?.urlProtocol(self, didFailWithError: URLError(.serverCertificateUntrusted)); return }
        var code = 200
        if path == "/fallback", request.httpMethod == "HEAD" { code = 405 }
        if path == "/redirect" { code = 302 }
        if path == "/down" { code = 503 }
        if path == "/vendor", request.value(forHTTPHeaderField: "Accept") != "application/vnd.health+json" { code = 406 }
        if path == "/headers" {
            let correct = request.httpMethod == "GET" && request.value(forHTTPHeaderField: "X-Health-Key") == "fixture-secret" &&
                request.value(forHTTPHeaderField: "Accept-Encoding") == "identity" && request.value(forHTTPHeaderField: "Accept") == "application/json"
            if !correct { code = 403 }
        }
        if path == "/generate_204", request.value(forHTTPHeaderField: "X-Health-Key") != nil { code = 503 }
        var body = path == "/oversized" ? Data(repeating: 65, count: 32_769) : Data(#"{"status":"ok","versions":["v1.1"]}"#.utf8)
        if ["/known-cap", "/unknown-cap"].contains(path) { body.append(Data(repeating: 32, count: 32_768 - body.count)) }
        if path == "/duplicate" { body = Data(#"{"status":"down","status":"ok"}"#.utf8) }
        var responseHeaders = ["Content-Length": String(body.count)]
        if path == "/unknown-cap" { responseHeaders.removeValue(forKey: "Content-Length") }
        if path == "/compressed" { responseHeaders["Content-Encoding"] = "gzip" }
        guard let response = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: responseHeaders) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

struct ServiceTransportTests {
    @Test func preservesVendorAcceptForJSONChecks() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ServiceFixtureProtocol.self]
        let target = ServiceDefinition(id: "custom", kind: .custom, address: "https://fixture.invalid/vendor",
            additionalJSONConditions: [.init(path: "status", expectedValue: "ok")],
            requestHeaders: [.init(name: "aCcEpT", value: "application/vnd.health+json")])
        #expect(try await ServiceProbeRunner(definition: target, configuration: config).check(epoch: 0).status == .ok)
    }

    @Test func bodyBoundariesAndAmbiguousResponsesFailClosed() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ServiceFixtureProtocol.self]
        for (path, expected): (String, ProbeStatus) in [("known-cap", .ok), ("unknown-cap", .down), ("compressed", .down), ("duplicate", .down)] {
            let target = ServiceDefinition(id: "custom", kind: .custom, address: "https://fixture.invalid/\(path)",
                additionalJSONConditions: [.init(path: "status", expectedValue: "ok")])
            #expect(try await ServiceProbeRunner(definition: target, configuration: config).check(epoch: 0).status == expected)
        }
    }
    @Test func customHeadersAndCombinedCriteriaUseOneGETWithoutLeakingToControl() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ServiceFixtureProtocol.self]
        var target = ServiceDefinition(id: "custom", kind: .custom, address: "https://fixture.invalid/headers",
            bodySubstring: "versions", additionalJSONConditions: [.init(path: "status", expectedValue: "ok")],
            requestHeaders: [.init(name: "X-Health-Key", value: "fixture-secret")])
        #expect(try await ServiceProbeRunner(definition: target, configuration: config).check(epoch: 0).status == .ok)
        target.additionalJSONConditions = [.init(path: "status", expectedValue: "wrong")]
        #expect(try await ServiceProbeRunner(definition: target, configuration: config).check(epoch: 0).status == .down)
    }
    @Test func healthTransportValidatesBeforePublishing() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ServiceFixtureProtocol.self]
        for (path, expected): (String, ProbeStatus) in [("ok", .ok), ("down", .down), ("oversized", .down), ("tls", .down), ("redirect", .down)] {
            let target = ServiceDefinition(id: "custom", kind: .custom, address: "https://fixture.invalid/\(path)", jsonPath: "status", expectedValue: "ok")
            let result = try await ServiceProbeRunner(definition: target, configuration: config).check(epoch: 7)
            #expect(result.status == expected)
            #expect(result.epoch == 7)
            #expect((result.latencyMilliseconds != nil) == (expected == .ok))
        }
    }
    @Test func headFallbackAndMatrixVersions() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ServiceFixtureProtocol.self]
        let target = ServiceDefinition(id: "custom", kind: .custom, address: "https://fixture.invalid/fallback")
        #expect(try await ServiceProbeRunner(definition: target, configuration: config).check(epoch: 0).status == .ok)
        let matrix = ServiceDefinition(id: "matrix", kind: .matrix, address: "https://fixture.invalid/ok")
        #expect(try await ServiceProbeRunner(definition: matrix, configuration: config).check(epoch: 0).status == .ok)
    }
}
#endif
