#if canImport(Network)
import Foundation
import Network

public struct EgressAddress: Equatable, Sendable {
    public let ip: String
    public let countryCode: String?

    public var flag: String {
        guard let countryCode else { return "🌐" }
        return String(String.UnicodeScalarView(countryCode.unicodeScalars.compactMap {
            UnicodeScalar(0x1F1E6 + $0.value - 65)
        }))
    }

    public static func parse(_ data: Data) throws -> EgressAddress {
        guard data.count <= 4096, let text = String(data: data, encoding: .utf8) else { throw URLError(.cannotParseResponse) }
        var fields: [String: String] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let pair = line.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard pair.count == 2 else { continue }
            let key = pair[0].trimmingCharacters(in: .whitespaces)
            guard key == "ip" || key == "loc" else { continue }
            guard fields[key] == nil else { throw URLError(.cannotParseResponse) }
            fields[key] = pair[1].trimmingCharacters(in: .whitespaces)
        }
        guard let ip = fields["ip"], ip.count <= 45, !ip.contains("%"),
              IPv4Address(ip) != nil || IPv6Address(ip) != nil else { throw URLError(.cannotParseResponse) }
        let code = fields["loc"]?.uppercased()
        let validCode = code.flatMap { value in
            value.utf8.count == 2 && value.utf8.allSatisfy { (65...90).contains($0) } &&
            Locale.Region.isoRegions.contains(where: { $0.identifier == value }) ? value : nil
        }
        return EgressAddress(ip: ip, countryCode: validCode)
    }
}

public struct EgressLookup: @unchecked Sendable {
    private let configuration: URLSessionConfiguration
    public init(configuration: URLSessionConfiguration = .ephemeral) {
        self.configuration = (configuration.copy() as? URLSessionConfiguration) ?? .ephemeral
    }

    public func fetch() async throws -> EgressAddress {
        guard let url = URL(string: "https://www.cloudflare.com/cdn-cgi/trace"),
              let config = configuration.copy() as? URLSessionConfiguration else { throw URLError(.badURL) }
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 8
        let session = URLSession(configuration: config, delegate: EgressRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue("text/plain", forHTTPHeaderField: "Accept")
        let (bytes, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse, response.statusCode == 200,
              response.expectedContentLength <= 4096 else { throw URLError(.badServerResponse) }
        var data = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            guard data.count < 4096 else { throw URLError(.dataLengthExceedsMaximum) }
            data.append(byte)
        }
        try Task.checkCancellation()
        return try EgressAddress.parse(data)
    }
}

private final class EgressRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
#endif
