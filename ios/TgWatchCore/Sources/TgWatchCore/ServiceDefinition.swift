import Foundation

public struct ServiceDefinition: Codable, Equatable, Identifiable, Sendable {
    public enum Kind: String, Codable, Sendable { case telegram, matrix, whatsapp, custom }
    public let id: String
    public let kind: Kind
    public let revision: UUID
    public var address: String
    public var expectedCode: Int?
    public var jsonPath: String
    public var expectedValue: String
    public var bodySubstring: String?
    public var additionalJSONConditions: [HealthJSONCondition]?
    public var requestHeaders: [HealthRequestHeader]?

    public init(id: String, kind: Kind, address: String = "", expectedCode: Int? = nil,
                jsonPath: String = "", expectedValue: String = "", revision: UUID = UUID(),
                bodySubstring: String? = nil, additionalJSONConditions: [HealthJSONCondition]? = nil,
                requestHeaders: [HealthRequestHeader]? = nil) {
        self.id = id; self.kind = kind; self.address = address; self.expectedCode = expectedCode
        self.jsonPath = jsonPath; self.expectedValue = expectedValue; self.revision = revision
        self.bodySubstring = bodySubstring
        self.additionalJSONConditions = additionalJSONConditions
        self.requestHeaders = requestHeaders
    }

    public static let telegram = ServiceDefinition(id: "telegram", kind: .telegram)
    public static var defaults: [ServiceDefinition] {
        [telegram, .init(id: "matrix", kind: .matrix, address: "https://matrix.org/_matrix/client/versions"),
         .init(id: "whatsapp", kind: .whatsapp, address: "https://web.whatsapp.com/")]
    }
    public var valid: Bool {
        if kind == .telegram { return id == "telegram" }
        guard Self.validAddress(address), expectedCode.map({ (200...599).contains($0) }) ?? true,
              expectedValue.count <= 256, jsonPath.count <= 64 else { return false }
        let parts = jsonPath.split(separator: ".", omittingEmptySubsequences: false)
        let conditions = healthConditions
        let headers = requestHeaders ?? []
        return (jsonPath.isEmpty || (parts.count <= 3 && parts.allSatisfy { !$0.isEmpty })) &&
            (bodySubstring?.count ?? 0) <= 256 && conditions.count <= 4 && conditions.allSatisfy(\.valid) &&
            headers.count <= 8 && headers.allSatisfy(\.valid) &&
            Set(headers.map { $0.name.lowercased() }).count == headers.count
    }
    public var healthConditions: [HealthJSONCondition] {
        // Keep the original single-condition fields readable by existing installations.
        (jsonPath.isEmpty ? [] : [.init(path: jsonPath, expectedValue: expectedValue)]) + (additionalJSONConditions ?? [])
    }
    public var needsJSON: Bool { kind == .matrix || !healthConditions.isEmpty }
    public var needsBody: Bool { needsJSON || !(bodySubstring ?? "").isEmpty }
    public static func validAddress(_ value: String) -> Bool {
        guard value.count <= 256, !value.contains(where: { $0.isWhitespace }),
              let url = URLComponents(string: value), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else { return false }
        return true
    }
    public func accepts(code: Int, body: Data) -> Bool {
        guard valid else { return false }
        guard expectedCode.map({ code == $0 }) ?? (200...299).contains(code) else { return false }
        guard body.count <= 32_768 else { return false }
        if needsBody {
            guard let text = String(data: body, encoding: .utf8) else { return false }
            if let bodySubstring, !bodySubstring.isEmpty, !text.contains(bodySubstring) { return false }
        }
        let root = needsJSON ? StrictHealthJSON.decode(body) : nil
        if kind == .matrix {
            guard let json = root as? [String: Any],
                  let versions = json["versions"] as? [String], !versions.isEmpty else { return false }
        }
        for condition in healthConditions {
            guard let root else { return false }
            var value: Any = root
            for key in condition.path.split(separator: ".") {
                guard let object = value as? [String: Any], let next = object[String(key)] else { return false }
                value = next
            }
            guard (value as? String) == condition.expectedValue else { return false }
        }
        return true
    }
}

/// Ephemeral continuity state: a gap, suspension or clock rollback cannot produce a recovery.
public struct AlertTransition: Sendable {
    private var previous: ProbeStatus?
    private var lastDate: Date?
    private var lifetime: TimeInterval = 0
    private var impaired = false
    public init() {}
    public mutating func observe(_ status: ProbeStatus, at date: Date, validFor: TimeInterval) -> ProbeStatus? {
        if let lastDate, date < lastDate || date.timeIntervalSince(lastDate) > lifetime {
            previous = nil; impaired = false
        }
        defer { previous = status; lastDate = date; lifetime = validFor }
        if status == .unknown || status == .noNetwork { impaired = false }
        if status == .down || status == .partial { impaired = true }
        if status == .ok {
            defer { impaired = false }
            return impaired ? .ok : nil
        }
        return status != previous && status != .unknown ? status : nil
    }
}
