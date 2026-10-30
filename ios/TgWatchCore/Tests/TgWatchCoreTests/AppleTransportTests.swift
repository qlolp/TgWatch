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
#if canImport(Network)
import Network
private final class TCPFixtureServer: @unchecked Sendable {
    enum Mode { case response, oversized, blocked }
    let listener: NWListener
    let queue = DispatchQueue(label: "tcp-fixture")
    var connections: [NWConnection] = []
    init() throws { listener = try NWListener(using: .tcp, on: .any) }
    func start(mode: Mode) async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            var waiting = true
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if waiting { waiting = false; continuation.resume(returning: self.listener.port!.rawValue) }
                case .failed(let error):
                    if waiting { waiting = false; continuation.resume(throwing: error) }
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
