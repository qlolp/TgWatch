import Foundation
public enum ServiceTarget: String, Codable, CaseIterable, Sendable {
    case telegram, matrix, whatsapp
    public var name: String { switch self { case .telegram: return "Telegram"; case .matrix: return "Matrix"; case .whatsapp: return "WhatsApp Web" } }
    public var endpoint: URL? {
        switch self {
        case .telegram: return nil
        case .matrix: return URL(string:"https://matrix.org/_matrix/client/versions")!
        case .whatsapp: return URL(string:"https://web.whatsapp.com/")!
        }
    }
    public func statusKey(_ status: ProbeStatus) -> String {
        self == .telegram || status == .unknown || status == .noNetwork ? "status."+status.rawValue : "serviceStatus."+status.rawValue
    }
}
public enum ServiceOutcome: String, Codable, Sendable {
    case matrixVersions, httpResponse, httpError, invalidPayload, timeout, transportError, unknown
}
public struct ServiceEvidence: Codable, Equatable, Sendable {
    public let target: ServiceTarget
    public let outcome: ServiceOutcome
    public let httpCode: Int?
    public var controlConfirmed: Bool
    public init(target: ServiceTarget, outcome: ServiceOutcome, httpCode: Int? = nil, controlConfirmed: Bool = false) {
        self.target=target; self.outcome=outcome; self.httpCode=httpCode; self.controlConfirmed=controlConfirmed
    }
    public var status: ProbeStatus {
        if outcome == .matrixVersions || outcome == .httpResponse { return .ok }
        return outcome != .unknown && controlConfirmed ? .down : .unknown
    }
}
public enum ServicePolicy {
    public static let maxBytes = 65536
    public static func evaluate(target: ServiceTarget, code: Int, body: Data, complete: Bool) -> ServiceOutcome {
        guard target != .telegram else { return .unknown }
        if target == .whatsapp { return (100...499).contains(code) ? .httpResponse : .httpError }
        guard (200...299).contains(code) else { return .httpError }
        guard complete, body.count <= maxBytes, String(data:body,encoding:.utf8) != nil else { return .invalidPayload }
        var reader = UniqueJSON(bytes:Array(body))
        guard reader.valid(), let object = try? JSONSerialization.jsonObject(with:body) as? [String:Any],
              let versions = object["versions"] as? [String], !versions.isEmpty,
              versions.allSatisfy({ $0.utf16.count <= 128 && !$0.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty }) else { return .invalidPayload }
        return .matrixVersions
    }
}
/// Bounds nesting before Foundation decoding and rejects duplicate decoded keys at every level.
private struct UniqueJSON {
    let bytes: [UInt8]
    var index=0, nodes=0
    mutating func valid() -> Bool { guard value(depth:0) else { return false }; space(); return index == bytes.count }
    mutating func space() { while index < bytes.count && [9,10,13,32].contains(bytes[index]) { index += 1 } }
    mutating func take(_ byte: UInt8) -> Bool { space(); guard index < bytes.count && bytes[index] == byte else { return false }; index += 1; return true }
    mutating func string() -> String? {
        space(); let start=index
        guard index < bytes.count && bytes[index] == 34 else { return nil }; index += 1
        while index < bytes.count {
            let byte=bytes[index]; index += 1
            if byte == 34 { return try? JSONDecoder().decode(String.self,from:Data(bytes[start..<index])) }
            if byte < 32 { return nil }
            if byte == 92 { guard index < bytes.count else { return nil }; index += 1 }
        }
        return nil
    }
    mutating func value(depth: Int) -> Bool {
        nodes += 1; guard depth <= 64 && nodes <= 4096 else { return false }
        space(); guard index < bytes.count else { return false }
        if bytes[index] == 123 {
            index += 1; if take(125) { return true }
            var keys=Set<String>()
            while true {
                guard let key=string(),keys.insert(key).inserted,take(58),value(depth:depth+1) else { return false }
                if take(125) { return true }; guard take(44) else { return false }
            }
        }
        if bytes[index] == 91 {
            index += 1; if take(93) { return true }
            while true { guard value(depth:depth+1) else { return false }; if take(93) { return true }; guard take(44) else { return false } }
        }
        if bytes[index] == 34 { return string() != nil }
        let start=index
        while index < bytes.count && ![9,10,13,32,44,93,125].contains(bytes[index]) { index += 1 }
        guard index > start, let token=String(bytes:bytes[start..<index],encoding:.utf8) else { return false }
        return ["true","false","null"].contains(token) || token.range(of:"^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$",options:.regularExpression) != nil
    }
}
