import Foundation

public struct HealthJSONCondition: Codable, Equatable, Sendable {
    public var path: String
    public var expectedValue: String
    public init(path: String = "", expectedValue: String = "") {
        self.path = path
        self.expectedValue = expectedValue
    }
    public var valid: Bool {
        let keys = path.split(separator: ".", omittingEmptySubsequences: false)
        return !path.isEmpty && path.count <= 64 && expectedValue.count <= 256 &&
            keys.count <= 3 && keys.allSatisfy { !$0.isEmpty }
    }
}

public struct HealthRequestHeader: Codable, Equatable, Sendable {
    public var name: String
    public var value: String
    public init(name: String = "", value: String = "") { self.name = name; self.value = value }
    public var valid: Bool {
        let tokens = Set("!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".utf8)
        // Transport framing and identity encoding remain owned by URLSession/the probe.
        let reserved = ["host", "content-length", "transfer-encoding", "connection", "accept-encoding"]
        return !name.isEmpty && name.utf8.count <= 64 && name.utf8.allSatisfy { tokens.contains($0) } &&
            value.utf8.count <= 256 && value.utf8.allSatisfy { (32...126).contains($0) } &&
            !reserved.contains(name.lowercased())
    }
}

/// Foundation checks the JSON grammar; this bounded preflight additionally rejects deep
/// nesting and duplicate object keys, including escaped spellings of the same key.
enum StrictHealthJSON {
    static func decode(_ data: Data) -> Any? {
        guard data.count <= 32_768, String(data: data, encoding: .utf8) != nil else { return nil }
        var scanner = Scanner(bytes: Array(data))
        guard scanner.value(depth: 0) else { return nil }
        scanner.whitespace()
        guard scanner.index == scanner.bytes.count else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    private struct Scanner {
        let bytes: [UInt8]
        var index = 0
        mutating func whitespace() {
            while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
        }
        mutating func take(_ byte: UInt8) -> Bool {
            whitespace()
            guard index < bytes.count, bytes[index] == byte else { return false }
            index += 1
            return true
        }
        mutating func string() -> String? {
            whitespace()
            let start = index
            guard index < bytes.count, bytes[index] == 34 else { return nil }
            index += 1
            while index < bytes.count {
                let byte = bytes[index]
                index += 1
                if byte == 34 {
                    return (try? JSONSerialization.jsonObject(with: Data(bytes[start..<index]), options: [.fragmentsAllowed])) as? String
                }
                if byte == 92 {
                    guard index < bytes.count else { return nil }
                    index += 1
                }
            }
            return nil
        }
        mutating func value(depth: Int) -> Bool {
            whitespace()
            guard index < bytes.count else { return false }
            switch bytes[index] {
            case 123:
                guard depth < 10 else { return false }
                index += 1
                if take(125) { return true }
                var keys = Set<String>()
                repeat {
                    guard let key = string(), keys.insert(key).inserted, take(58), value(depth: depth + 1) else { return false }
                    if take(125) { return true }
                } while take(44)
                return false
            case 91:
                guard depth < 10 else { return false }
                index += 1
                if take(93) { return true }
                repeat {
                    guard value(depth: depth + 1) else { return false }
                    if take(93) { return true }
                } while take(44)
                return false
            case 34: return string() != nil
            default:
                let start = index
                while index < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) { index += 1 }
                return index > start
            }
        }
    }
}
