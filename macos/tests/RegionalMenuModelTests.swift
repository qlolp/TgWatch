import Foundation

@main struct RegionalMenuModelTests {
    static var assertions = 0
    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        assertions += 1
        if !condition() { FileHandle.standardError.write(Data((message + "\n").utf8)); exit(1) }
    }
    static func line(_ overrides: [String: Any] = [:]) -> Data {
        var row: [String: Any] = ["schema": 1, "source": "mac_public", "service": "gemini",
            "status": "WEB_REACHABLE", "http_code": 200, "country": "DE", "checked_at": 1_000,
            "region_confirmed": false, "context_id": "0123456789abcdef0123456789abcdef"]
        overrides.forEach { row[$0] = $1 }
        return try! JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
    }
    static func main() {
        var model = RegionalMenuModel()
        model.replace(generation: 1, services: Set(RegionalMenuService.allCases))
        for service in RegionalMenuService.allCases {
            expect(model.ingest(line(["service": service.rawValue]), generation: 1, now: 1_100, uptime: 10),
                   "Every fixed service must be measured independently")
            expect(model.current(service, now: 1_100, uptime: 11)?.status == .webReachable, "Fresh reading")
        }
        let bad: [[String: Any]] = [["schema": 2], ["schema": true], ["service": "private-api"],
            ["status": "ACCOUNT_READY"], ["http_code": 99], ["http_code": true], ["country": "ZZ"],
            ["country": "1.2.3.4"], ["country": "001"], ["country": "EU"], ["country": "XK"], ["checked_at": 1_101], ["checked_at": true], ["checked_at": 999.5],
            ["region_confirmed": 1], ["region_confirmed": true], ["context_id": "raw-ip-hash"],
            ["source": "browser_cookie"], ["password": "secret"], ["context_id": NSNull()]]
        for row in bad {
            expect(!model.ingest(line(row), generation: 1, now: 1_100, uptime: 12), "Reject malformed \(row.keys)")
        }
        expect(!model.ingest(Data(repeating: 32, count: 4_097), generation: 1, now: 1_100, uptime: 12), "Bounded line")
        expect(!model.ingest(Data("not-json".utf8), generation: 1, now: 1_100, uptime: 12), "Malformed JSON")
        let text = String(data: line(), encoding: .utf8)!
        let duplicate = "{\"service\":\"youtube\"," + text.dropFirst()
        expect(!model.ingest(Data(duplicate.utf8), generation: 1, now: 1_100, uptime: 12), "Duplicate key")
        expect(!model.ingest(line(), generation: 0, now: 1_100, uptime: 12), "Replaced child cannot publish")
        expect(!model.ingest(line(), generation: 1, now: 602_001, uptime: 12), "Stale payload rejected")
        expect(model.current(.gemini, now: 602_001, uptime: 12) == nil, "Old cached observation is not current")
        expect(model.current(.gemini, now: 999, uptime: 12) == nil, "Wall clock reversal")
        expect(model.current(.gemini, now: 1_100, uptime: 9) == nil, "Receipt uptime reversal")
        model.replace(generation: 2, services: [.gemini, .youtube])
        expect(model.current(.gemini, now: 1_100, uptime: 12) == nil, "Replacement clears observations")
        expect(!model.ingest(line(["service": "dots"]), generation: 2, now: 1_100, uptime: 12), "Disabled profile")
        expect(model.ingest(line(), generation: 2, now: 1_100, uptime: 12), "New process fresh reading")
        expect(model.ingest(line(["service": "youtube", "context_id": "abcdef0123456789abcdef0123456789"]),
                            generation: 2, now: 1_100, uptime: 13), "New opaque context")
        expect(model.current(.gemini, now: 1_100, uptime: 13) == nil, "Network context clears other profiles")
        expect(model.ingest(line(["status": "UNKNOWN", "context_id": NSNull(), "country": NSNull()]),
                            generation: 2, now: 1_100, uptime: 14), "Explicit unverified context")
        expect(model.current(.youtube, now: 1_100, uptime: 14) == nil, "Unknown route cannot preserve other proof")
        model.replace(generation: 3, services: [])
        expect(!model.ingest(line(), generation: 2, now: 1_100, uptime: 15), "Stopped process cannot revive results")
        expect(model.current(.gemini, now: 1_100, uptime: 15) == nil, "Stopped has no current evidence")
        print("RegionalMenuModel: \(assertions) assertions passed")
    }
}
