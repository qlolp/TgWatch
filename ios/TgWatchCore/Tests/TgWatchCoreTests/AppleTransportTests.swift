#if canImport(Network)
import Foundation
import XCTest
@testable import TgWatchCore
private final class FixtureURLProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path
        if path == "/tls" { client?.urlProtocol(self, didFailWithError: URLError(.serverCertificateUntrusted)); return }
        let code = path == "/fallback" && request.httpMethod == "HEAD" ? 405 : (path == "/redirect" ? 302 : 200)
        let size = path == "/oversized" ? 65537 : 1
        let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(repeating: 65, count: size))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
final class AppleTransportTests: XCTestCase {
    func testBoundedHTTPAndTrustFailures() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [FixtureURLProtocol.self]
        let probe = HTTPProbe(configuration: config)
        for path in ["ok", "fallback", "redirect"] {
            let result = try await probe.check(url: URL(string: "https://fixture.invalid/\(path)")!, deadline: .now.advanced(by: .seconds(2)))
            XCTAssertTrue(result, path)
        }
        let large = try await probe.check(url: URL(string: "https://fixture.invalid/oversized")!, deadline: .now.advanced(by: .seconds(2)))
        XCTAssertFalse(large)
        do { _ = try await probe.check(url: URL(string: "https://fixture.invalid/tls")!, deadline: .now.advanced(by: .seconds(2))); XCTFail("TLS must fail") } catch {}
    }
}
#endif
