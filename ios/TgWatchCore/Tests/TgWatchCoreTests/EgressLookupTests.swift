#if canImport(Network)
import Foundation
import Testing
@testable import TgWatchCore

struct EgressLookupTests {
    @Test func parsesCountryOfIPInsteadOfDatacenter() throws {
        let result = try EgressAddress.parse(Data("ip=203.0.113.42\ncolo=AMS\nloc=DE\nwarp=off\n".utf8))
        #expect(result.ip == "203.0.113.42")
        #expect(result.countryCode == "DE")
        #expect(result.flag == "🇩🇪")
        let ipv6 = try EgressAddress.parse(Data("ip=2001:db8::1\r\nloc=us\r\n".utf8))
        #expect(ipv6.countryCode == "US")
        #expect(ipv6.flag == "🇺🇸")
    }

    @Test func unknownCountryNeverFabricatesFlag() throws {
        for country in ["", "XX", "T1", "USA", "<script>", "12"] {
            let result = try EgressAddress.parse(Data("ip=203.0.113.42\nloc=\(country)\n".utf8))
            #expect(result.countryCode == nil)
            #expect(result.flag == "🌐")
        }
    }

    @Test func rejectsMalformedAmbiguousAndOversizedResponses() {
        for text in ["<html>blocked</html>", "ip=hostname.invalid\nloc=DE", "ip=999.1.1.1", "ip=::1%en0",
                     "ip=203.0.113.42\nip=203.0.113.43", "ip=203.0.113.42\nloc=DE\nloc=US", String(repeating: "x", count: 4097)] {
            #expect(throws: (any Error).self) { try EgressAddress.parse(Data(text.utf8)) }
        }
    }

    @Test func lookupUsesBoundedUncachedRequest() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [EgressFixtureProtocol.self]
        let result = try await EgressLookup(configuration: config).fetch()
        #expect(result.ip == "203.0.113.42")
        #expect(result.countryCode == "DE")
    }

    @Test func transportRejectsHTTPFailuresAndOversizedBodies() async {
        for scenario in ["http-error", "oversized", "declared-oversized"] {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [EgressFixtureProtocol.self]
            config.httpAdditionalHeaders = ["X-Fixture": scenario]
            do {
                _ = try await EgressLookup(configuration: config).fetch()
                Issue.record("Unexpected success for \(scenario)")
            } catch {}
        }
    }
}

private final class EgressFixtureProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url else { return }
        let valid = url.absoluteString == "https://www.cloudflare.com/cdn-cgi/trace" &&
            request.value(forHTTPHeaderField: "Cache-Control") == "no-cache" &&
            request.value(forHTTPHeaderField: "Accept") == "text/plain" &&
            request.value(forHTTPHeaderField: "Cookie") == nil
        let scenario = request.value(forHTTPHeaderField: "X-Fixture")
        let headers = scenario == "declared-oversized" ? ["Content-Length": "5000"] : [:]
        guard let response = HTTPURLResponse(url: url, statusCode: valid && scenario != "http-error" ? 200 : 403, httpVersion: "HTTP/1.1", headerFields: headers) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        let body = scenario == "oversized" ? Data(repeating: 65, count: 4097) : Data("ip=203.0.113.42\nloc=DE\n".utf8)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
#endif
