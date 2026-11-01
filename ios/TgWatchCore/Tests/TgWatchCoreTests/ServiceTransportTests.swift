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
        let body = path == "/oversized" ? Data(repeating: 65, count: 32_769) : Data(#"{"status":"ok","versions":["v1.1"]}"#.utf8)
        guard let response = HTTPURLResponse(url: url, statusCode: code, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": String(body.count)]) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

struct ServiceTransportTests {
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
