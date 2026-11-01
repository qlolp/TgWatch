#if canImport(Network)
import Foundation

public struct ServiceProbeRunner: ProbeRunner, @unchecked Sendable {
    private let definition: ServiceDefinition
    private let configuration: URLSessionConfiguration
    public init(definition: ServiceDefinition, configuration: URLSessionConfiguration = .ephemeral) {
        self.definition = definition
        self.configuration = configuration.copy() as! URLSessionConfiguration
    }

    public func check(epoch: UInt64) async throws -> ProbeSnapshot {
        guard definition.valid, let url = URL(string: definition.address) else { throw URLError(.badURL) }
        let start = Date(), instant = ContinuousClock.now
        let confirmed: Bool
        do { confirmed = try await request(url) }
        catch {
            try Task.checkCancellation()
            confirmed = false
        }
        try Task.checkCancellation()
        let elapsed = instant.duration(to: .now)
        let latency = Double(elapsed.components.seconds) * 1000 + Double(elapsed.components.attoseconds) / 1e15
        var control = false
        if !confirmed, let controlURL = URL(string: "https://www.gstatic.com/generate_204") {
            control = (try? await HTTPProbe(configuration: configuration).check(url: controlURL, deadline: .now.advanced(by: .seconds(3)))) == true
        }
        try Task.checkCancellation()
        return ProbeSnapshot(epoch: epoch, startedAt: start, finishedAt: Date(),
                             evidence: .init(webConfirmed: confirmed, controlConfirmed: control, primaryMeasurementsComplete: true),
                             measuredStatus: confirmed ? .ok : (control ? .down : .unknown),
                             latencyMilliseconds: confirmed ? latency : nil)
    }

    private func request(_ url: URL) async throws -> Bool {
        let config = configuration.copy() as! URLSessionConfiguration
        config.httpCookieStorage = nil; config.urlCache = nil; config.httpShouldSetCookies = false
        config.timeoutIntervalForRequest = 3; config.timeoutIntervalForResource = 6
        let session = URLSession(configuration: config, delegate: ServiceRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let needsBody = definition.needsBody
        for method in needsBody ? ["GET"] : ["HEAD", "GET"] {
            try Task.checkCancellation()
            var request = URLRequest(url: url)
            request.httpMethod = method
            for header in definition.requestHeaders ?? [] {
                request.setValue(header.value, forHTTPHeaderField: header.name)
            }
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            if definition.needsJSON { request.setValue("application/json", forHTTPHeaderField: "Accept") }
            let (bytes, response) = try await session.bytes(for: request)
            guard let response = response as? HTTPURLResponse else { return false }
            if method == "HEAD" && [405, 501].contains(response.statusCode) { continue }
            if !needsBody { return definition.accepts(code: response.statusCode, body: Data()) }
            guard response.expectedContentLength <= 32_768,
                  [nil, "identity"].contains(response.value(forHTTPHeaderField: "Content-Encoding")?.lowercased()) else { return false }
            var body = Data()
            for try await byte in bytes {
                try Task.checkCancellation()
                guard body.count < 32_768 else { return false }
                body.append(byte)
            }
            if response.expectedContentLength < 0 && body.count == 32_768 { return false }
            return definition.accepts(code: response.statusCode, body: body)
        }
        return false
    }
}

private final class ServiceRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

#endif
