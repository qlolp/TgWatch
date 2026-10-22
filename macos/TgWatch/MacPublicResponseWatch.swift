import Foundation
import CoreFoundation

struct MacWatchIntent: Equatable {
    let service: RegionalMenuService
    let id: String
    let armedAt: Double
    let deadline: Double
    static let lifetimeMS: Double = 86_400_000
    var valid: Bool {
        id.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil && armedAt.isFinite &&
            armedAt >= 0 && armedAt.rounded(.towardZero) == armedAt && deadline.isFinite &&
            deadline - armedAt == Self.lifetimeMS
    }
}

enum MacWatchIntentCodec {
    private static let keys: Set<String> = ["service", "id", "armed_at", "deadline"]
    private static let keyPattern = try! NSRegularExpression(pattern: #""[a-z_]+"\s*:"#)
    static func encode(_ intents: [MacWatchIntent]) -> Data? {
        guard intents.count <= RegionalMenuService.allCases.count, intents.allSatisfy(\.valid),
              Set(intents.map(\.service)).count == intents.count else { return nil }
        let rows: [[String: Any]] = intents.map {
            ["service": $0.service.rawValue, "id": $0.id, "armed_at": $0.armedAt, "deadline": $0.deadline]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rows, options: .sortedKeys), data.count <= 4_096
        else { return nil }
        return data
    }
    static func decode(_ data: Data, now: Double) -> [MacWatchIntent] {
        guard data.count <= 4_096, data.allSatisfy({ $0 < 128 }), let text = String(data: data, encoding: .utf8),
              !text.contains("\\"), let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              rows.count <= RegionalMenuService.allCases.count,
              keyPattern.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text)) == keys.count * rows.count
        else { return [] }
        var intents: [MacWatchIntent] = []
        for row in rows {
            guard Set(row.keys) == keys, let raw = row["service"] as? String,
                let service = RegionalMenuService(rawValue: raw), let id = row["id"] as? String,
                let at = number(row["armed_at"]), let deadline = number(row["deadline"]) else { return [] }
            let intent = MacWatchIntent(service: service, id: id, armedAt: at, deadline: deadline)
            guard intent.valid, !intents.contains(where: { $0.service == service }) else { return [] }
            intents.append(intent)
        }
        return intents.filter { now >= $0.armedAt && now < $0.deadline }
    }
    private static func number(_ value: Any?) -> Double? {
        guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue.isFinite
        else { return nil }
        return value.doubleValue
    }
}

/// Only intent is serializable. Evidence always belongs to this process, child generation and opaque connection.
struct MacPublicResponseWatch {
    let intent: MacWatchIntent
    private let deadlineUptime: TimeInterval
    private var lastWall: Double
    private var lastUptime: TimeInterval
    private var ended = false
    private var first: RegionalMenuReading?
    private var last: RegionalMenuReading?
    private var generation: UInt64?
    private var context: String?
    private var lastCheckedAt: Double = -1

    init?(intent: MacWatchIntent, now: Double, uptime: TimeInterval) {
        guard intent.valid, now >= intent.armedAt, now < intent.deadline, uptime.isFinite, uptime >= 0 else { return nil }
        self.intent = intent
        self.deadlineUptime = uptime + (intent.deadline - now) / 1_000
        self.lastWall = now
        self.lastUptime = uptime
    }
    static func arm(_ reading: RegionalMenuReading, now: Double, uptime: TimeInterval) -> MacPublicResponseWatch? {
        guard reading.status == .regionRestricted, reading.regionConfirmed, reading.contextID != nil,
            reading.checkedAt <= now, now - reading.checkedAt <= RegionalMenuDecoder.maximumAgeMS,
            reading.receivedUptime <= uptime,
            uptime - reading.receivedUptime <= RegionalMenuDecoder.maximumAgeMS / 1_000 else { return nil }
        let at = now.rounded(.down)
        let intent = MacWatchIntent(service: reading.service, id: UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
                                    armedAt: at, deadline: at + MacWatchIntent.lifetimeMS)
        return MacPublicResponseWatch(intent: intent, now: now, uptime: uptime)
    }
    func active(now: Double, uptime: TimeInterval) -> Bool {
        !ended && now >= lastWall && uptime >= lastUptime && now >= intent.armedAt && now < intent.deadline &&
            uptime < deadlineUptime
    }
    mutating func observe(_ reading: RegionalMenuReading, generation: UInt64, now: Double, uptime: TimeInterval) {
        guard active(now: now, uptime: uptime) else { ended = true; resetEvidence(); return }
        lastWall = now; lastUptime = uptime
        guard reading.service == intent.service else { return }
        let fresh = reading.checkedAt > intent.armedAt && reading.checkedAt <= now &&
            now - reading.checkedAt <= RegionalMenuDecoder.maximumAgeMS && reading.receivedUptime <= uptime &&
            uptime - reading.receivedUptime <= RegionalMenuDecoder.maximumAgeMS / 1_000
        guard fresh, reading.status == .webReachable, let scope = reading.contextID else { resetEvidence(); return }
        if self.generation != generation || context != scope { resetEvidence(); self.generation = generation; context = scope }
        guard reading.checkedAt > lastCheckedAt else { return }
        lastCheckedAt = reading.checkedAt
        if let first, reading.checkedAt - first.checkedAt > RegionalMenuDecoder.maximumAgeMS ||
            reading.receivedUptime - first.receivedUptime > RegionalMenuDecoder.maximumAgeMS / 1_000 {
            self.first = reading
        } else if first == nil { first = reading }
        last = reading
    }
    func ready(context: String?, generation: UInt64, now: Double, uptime: TimeInterval) -> Bool {
        guard active(now: now, uptime: uptime), let first, let last, context != nil,
              self.context == context, self.generation == generation,
              last.checkedAt - first.checkedAt >= 15_000, last.receivedUptime - first.receivedUptime >= 15,
              now >= last.checkedAt, now - first.checkedAt <= RegionalMenuDecoder.maximumAgeMS,
              uptime >= last.receivedUptime,
              uptime - first.receivedUptime <= RegionalMenuDecoder.maximumAgeMS / 1_000 else { return false }
        return true
    }
    mutating func resetEvidence() { first = nil; last = nil; generation = nil; context = nil; lastCheckedAt = -1 }
}

/// Kernel path changes can invalidate buffered output before their main-queue redraw is processed.
final class MacConnectionFence: @unchecked Sendable {
    private let lock = NSLock()
    private var revision: UInt64 = 0
    func capture() -> UInt64 { lock.lock(); defer { lock.unlock() }; return revision }
    func changed() { lock.lock(); revision &+= 1; lock.unlock() }
    func matches(_ captured: UInt64) -> Bool { capture() == captured }
}
