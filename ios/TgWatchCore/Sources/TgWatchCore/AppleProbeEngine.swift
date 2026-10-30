#if canImport(Network)
import Foundation
import Network
public struct AppleProbeTransport: ProbeTransport {
    public init() {}
    public func check(_ endpoint: ProbeEndpoint, deadline: ContinuousClock.Instant) async throws -> Bool {
        switch endpoint.group {
        case .mtproto:
            let nonce = Data((0..<16).map { _ in UInt8.random(in: .min ... .max) })
            return try await TCPProbe.check(endpoint: endpoint.address, nonce: nonce, deadline: deadline)
        case .web, .control:
            guard let url = URL(string: endpoint.address) else { return false }
            return try await HTTPProbe().check(url: url, deadline: deadline)
        }
    }
}
public struct HTTPProbe: @unchecked Sendable {
    private let configuration: URLSessionConfiguration
    public init(configuration: URLSessionConfiguration = .ephemeral) { self.configuration = configuration }
    public func check(url: URL, deadline: ContinuousClock.Instant) async throws -> Bool {
        let configuration = configuration.copy() as! URLSessionConfiguration
        configuration.httpCookieStorage = nil; configuration.urlCache = nil
        configuration.httpShouldSetCookies = false
        let delegate = NoRedirects(), session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        for method in ["HEAD", "GET"] {
            try Task.checkCancellation()
            let remaining = ContinuousClock.now.duration(to: deadline)
            guard remaining > .zero else { throw CancellationError() }
            var request = URLRequest(url: url)
            request.httpMethod = method; request.cachePolicy = .reloadIgnoringLocalCacheData
            let seconds = Double(remaining.components.seconds) + Double(remaining.components.attoseconds) / 1e18
            request.timeoutInterval = max(0.001, min(3, seconds))
            let (bytes, response) = try await session.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { return false }
            var count = 0
            for try await _ in bytes {
                try Task.checkCancellation()
                guard ContinuousClock.now < deadline else { throw CancellationError() }
                count += 1; if count > 65_536 { return false }
            }
            if method == "HEAD" && [405,501].contains(http.statusCode) { continue }
            return (100...499).contains(http.statusCode)
        }
        return false
    }
}
private final class NoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
public enum TCPProbe {
    public static func check(endpoint: String, port: UInt16 = 443, nonce: Data, deadline: ContinuousClock.Instant) async throws -> Bool {
        let request = try MTProtoCodec.makeRequest(nonce: nonce)
        let operation = TCPRequest(endpoint: endpoint, port: port, nonce: nonce, request: request)
        return try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { operation.start($0, deadline: deadline) }
        }, onCancel: { operation.cancel() })
    }
}
/// All continuation/socket state is confined to queue; cancellation before start is remembered.
private final class TCPRequest: @unchecked Sendable {
    private let queue = DispatchQueue(label: "ru.tgwatch.ios.tcp")
    private let connection: NWConnection
    private let nonce, request: Data
    private var continuation: CheckedContinuation<Bool, Error>?
    private var cancelled = false
    private var sent = false
    init(endpoint: String, port: UInt16, nonce: Data, request: Data) {
        connection = NWConnection(host: NWEndpoint.Host(endpoint), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        self.nonce = nonce; self.request = request
    }
    func start(_ continuation: CheckedContinuation<Bool, Error>, deadline: ContinuousClock.Instant) {
        queue.async {
            self.continuation = continuation
            guard !self.cancelled else { self.finish(.failure(CancellationError())); return }
            let remaining = ContinuousClock.now.duration(to: deadline)
            guard remaining > .zero else { self.finish(.failure(CancellationError())); return }
            let seconds = min(3, Double(remaining.components.seconds) + Double(remaining.components.attoseconds)/1e18)
            self.queue.asyncAfter(deadline: .now() + seconds) { self.finish(.success(false)) }
            self.connection.stateUpdateHandler = { [weak self] state in
                guard let self, self.continuation != nil else { return }
                switch state {
                case .ready:
                    guard !self.sent else { return }; self.sent = true
                    self.connection.send(content: Data([0xef,10]) + self.request, completion: .contentProcessed { [weak self] error in
                        guard let self else { return }
                        if error != nil { self.finish(.success(false)); return }
                        self.read(count: 1) { first in
                            if first[0] == 127 {
                                self.read(count: 3) { rest in self.readFrame(header: first + rest) }
                            } else { self.readFrame(header: first) }
                        }
                    })
                case .failed: self.finish(.success(false))
                case .cancelled: self.finish(.failure(CancellationError()))
                default: break
                }
            }
            self.connection.start(queue: self.queue)
        }
    }
    func cancel() { queue.async { self.cancelled = true; self.finish(.failure(CancellationError())) } }
    private func readFrame(header: Data) {
        guard let size = try? MTProtoCodec.frameLength(header) else { finish(.success(false)); return }
        read(count: size) { self.finish(.success(MTProtoCodec.validateResponse($0, nonce: self.nonce))) }
    }
    private func read(count: Int, accumulated: Data = Data(), completion: @escaping (Data) -> Void) {
        guard continuation != nil else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: count - accumulated.count) { [weak self] data, _, complete, error in
            guard let self, self.continuation != nil else { return }
            let bytes = accumulated + (data ?? Data())
            if bytes.count == count { completion(bytes) }
            else if error != nil || complete || data?.isEmpty != false { self.finish(.success(false)) }
            else { self.read(count: count, accumulated: bytes, completion: completion) }
        }
    }
    private func finish(_ result: Result<Bool, Error>) {
        guard let continuation else { return }
        self.continuation = nil; connection.stateUpdateHandler = nil; connection.cancel()
        continuation.resume(with: result)
    }
}
#endif
