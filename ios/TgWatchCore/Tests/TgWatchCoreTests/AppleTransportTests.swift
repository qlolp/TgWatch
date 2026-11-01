#if canImport(Network)
import Foundation
import XCTest
@testable import TgWatchCore
private final class RequestRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String] = []
    func record(_ value: String) { lock.lock(); values.append(value); lock.unlock() }
    func reset() { lock.lock(); values.removeAll(); lock.unlock() }
    func snapshot() -> [String] { lock.lock(); defer { lock.unlock() }; return values }
}
private final class FixtureURLProtocol: URLProtocol, @unchecked Sendable {
    private static let recorder = RequestRecorder()
    static func resetRequests() { recorder.reset() }
    static var requests: [String] { recorder.snapshot() }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let path = request.url!.path, method = request.httpMethod ?? ""
        Self.recorder.record("\(method) \(path)")
        if path == "/tls" { client?.urlProtocol(self, didFailWithError: URLError(.serverCertificateUntrusted)); return }
        let fallback = ["/fallback", "/not-implemented"].contains(path) && method == "HEAD"
        let code = fallback ? (path == "/fallback" ? 405 : 501) : (path == "/redirect" ? 302 : (path == "/server-error" ? 500 : 200))
        let headers = path == "/redirect" ? ["Location": "https://fixture.invalid/ok"] : nil
        let size = path == "/oversized" ? 65537 : 1
        let response = HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: headers)!
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
    func testHTTPMethodFallbackAndRedirectSequence() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [FixtureURLProtocol.self]
        let probe = HTTPProbe(configuration: config)
        let cases = [
            ("ok", true, ["HEAD /ok"]),
            ("fallback", true, ["HEAD /fallback", "GET /fallback"]),
            ("not-implemented", true, ["HEAD /not-implemented", "GET /not-implemented"]),
            ("redirect", true, ["HEAD /redirect"]),
            ("server-error", false, ["HEAD /server-error"])
        ]
        for (path, expected, requests) in cases {
            FixtureURLProtocol.resetRequests()
            let result = try await probe.check(url: URL(string: "https://fixture.invalid/\(path)")!, deadline: .now.advanced(by: .seconds(2)))
            XCTAssertEqual(result, expected, path)
            XCTAssertEqual(FixtureURLProtocol.requests, requests, path)
        }
    }
}
#endif
#if canImport(Network)
import Network
private final class TCPFixtureServer: @unchecked Sendable {
    enum Mode { case response, oversized, blocked }
    let listener: NWListener
    let queue = DispatchQueue(label: "tcp-fixture")
    var connections: [NWConnection] = []
    private var waiting = true // Accessed only on listener queue.
    init() throws { listener = try NWListener(using: .tcp, on: .any) }
    func start(mode: Mode) async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if self.waiting { self.waiting = false; continuation.resume(returning: self.listener.port!.rawValue) }
                case .failed(let error):
                    if self.waiting { self.waiting = false; continuation.resume(throwing: error) }
                default: break
                }
            }
            listener.newConnectionHandler = { connection in
                self.connections.append(connection); connection.start(queue: self.queue)
                connection.receive(minimumIncompleteLength: 42, maximumLength: 42) { data, _, _, _ in
                    guard let data, data.count == 42 else { return }
                    if mode == .blocked { return }
                    if mode == .oversized {
                        connection.send(content: Data([127,1,4,0]), completion: .contentProcessed { _ in }); return
                    }
                    let response: Data = {
                        var bytes = MTProtoCodecTests.responseBytes
                        bytes.replaceSubrange(24..<40, with: data[26..<42]); return bytes
                    }()
                    connection.send(content: Data([21]), completion: .contentProcessed { _ in
                        connection.send(content: response.prefix(33), completion: .contentProcessed { _ in
                            connection.send(content: response.dropFirst(33), completion: .contentProcessed { _ in })
                        })
                    })
                }
            }
            listener.start(queue: queue)
        }
    }
    func stop() { queue.sync { listener.cancel(); connections.forEach { $0.cancel() } } }
}
final class TCPTransportTests: XCTestCase {
    func testFragmentedLoopbackHandshakeAndOversizedFrame() async throws {
        for (mode, expected): (TCPFixtureServer.Mode, Bool) in [(.response,true),(.oversized,false)] {
            let server = try TCPFixtureServer(); let port = try await server.start(mode: mode)
            let result = try await TCPProbe.check(endpoint: "127.0.0.1", port: port, nonce: Data(0..<16), deadline: .now.advanced(by: .seconds(2)))
            server.stop(); XCTAssertEqual(result, expected)
        }
    }
    func testCancelBlockedSocketReturnsPromptly() async throws {
        let server = try TCPFixtureServer(); let port = try await server.start(mode: .blocked)
        defer { server.stop() }
        let start = ContinuousClock.now
        let task = Task { try await TCPProbe.check(endpoint: "127.0.0.1", port: port, nonce: Data(0..<16), deadline: .now.advanced(by: .seconds(6))) }
        try await Task.sleep(for: .milliseconds(40)); task.cancel()
        do { _ = try await task.value; XCTFail("cancelled socket must throw") } catch is CancellationError {} catch { XCTFail("\(error)") }
        XCTAssertLessThan(start.duration(to: .now), .seconds(1))
    }
}
#endif
