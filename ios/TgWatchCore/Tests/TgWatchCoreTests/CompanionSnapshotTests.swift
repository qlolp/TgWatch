import Testing
import Foundation
@testable import TgWatchCore

@Suite struct CompanionSnapshotTests {
    @Test func countryExpiresIndependentlyAndLegacySnapshotsHideIt() throws {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let item = CompanionSnapshot.Item(id: "telegram", displayName: "Telegram", statusRaw: "ok",
            latencyMilliseconds: nil, observedAt: now, validUntil: now.addingTimeInterval(300),
            countryFlag: "🇩🇪", countryObservedAt: now, countryValidUntil: now.addingTimeInterval(90))
        #expect(item.freshCountryFlag(at: now.addingTimeInterval(90)) == "🇩🇪")
        #expect(item.freshCountryFlag(at: now.addingTimeInterval(91)) == nil)
        #expect(item.freshCountryFlag(at: now.addingTimeInterval(-1)) == nil)
        var json = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(item)) as? [String: Any])
        json.removeValue(forKey: "countryObservedAt")
        json.removeValue(forKey: "countryValidUntil")
        let legacy = try JSONDecoder().decode(CompanionSnapshot.Item.self, from: JSONSerialization.data(withJSONObject: json))
        #expect(legacy.countryFlag == "🇩🇪")
        #expect(legacy.freshCountryFlag(at: now) == nil)
    }
    @Test func futureObservationAndClockRollbackNeverLookFresh() {
        let observed = Date(timeIntervalSince1970: 1_700_000_000)
        let item = CompanionSnapshot.Item(id: "telegram", displayName: "Telegram", statusRaw: "ok",
            latencyMilliseconds: 10, observedAt: observed, validUntil: observed.addingTimeInterval(120))
        #expect(item.isStale(at: observed.addingTimeInterval(-3600)))
        let snapshot = CompanionSnapshot(generatedAt: observed.addingTimeInterval(30), items: [item])
        #expect(snapshot.isStale(item, at: observed.addingTimeInterval(10)))
        #expect(!snapshot.isStale(item, at: observed.addingTimeInterval(31)))
    }

    @Test func invalidExpiryOrNonFiniteTimeNeverLooksFresh() {
        let observed = Date(timeIntervalSince1970: 1_700_000_000)
        let backwards = CompanionSnapshot.Item(id: "telegram", displayName: "Telegram", statusRaw: "ok",
            latencyMilliseconds: nil, observedAt: observed, validUntil: observed.addingTimeInterval(-1))
        #expect(backwards.isStale(at: observed))
        let infinite = CompanionSnapshot.Item(id: "telegram", displayName: "Telegram", statusRaw: "ok",
            latencyMilliseconds: nil, observedAt: observed, validUntil: Date(timeIntervalSince1970: .infinity))
        #expect(infinite.isStale(at: observed))
    }
    @Test func expiryMarksStaleAccurately() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let item = CompanionSnapshot.Item(
            id: "telegram",
            displayName: "Telegram",
            statusRaw: "ok",
            latencyMilliseconds: 85.0,
            observedAt: now,
            validUntil: now.addingTimeInterval(120),
            countryFlag: "🇩🇪"
        )

        #expect(!item.isStale(at: now.addingTimeInterval(60)))
        #expect(!item.isStale(at: now.addingTimeInterval(120)))
        #expect(item.isStale(at: now.addingTimeInterval(121)))
    }

    @Test func privacyAllowlistExcludesSensitiveFields() throws {
        let item = CompanionSnapshot.Item(
            id: "custom1",
            displayName: "Internal Gateway",
            statusRaw: "ok",
            latencyMilliseconds: 42.0,
            observedAt: Date(),
            validUntil: Date().addingTimeInterval(60)
        )
        let snapshot = CompanionSnapshot(items: [item])
        let data = try JSONEncoder().encode(snapshot)
        guard let jsonString = String(data: data, encoding: .utf8) else {
            Issue.record("Failed to decode JSON string")
            return
        }

        let forbiddenSubstrings = [
            "ip",
            "url",
            "endpoint",
            "token",
            "secret",
            "authorization",
            "bearer",
            "password"
        ]

        let keys = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let firstItemDict = (keys?["items"] as? [[String: Any]])?.first ?? [:]
        let itemKeys = Set(firstItemDict.keys)

        for forbidden in forbiddenSubstrings {
            #expect(!itemKeys.contains(forbidden))
        }
        #expect(!jsonString.contains("192.168."))
        #expect(!jsonString.contains("https://"))
    }

    @Test func snapshotRoundtripSerialization() throws {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let original = CompanionSnapshot(
            generatedAt: now,
            items: [
                CompanionSnapshot.Item(
                    id: "telegram",
                    displayName: "Telegram",
                    statusRaw: "ok",
                    latencyMilliseconds: 120.5,
                    observedAt: now,
                    validUntil: now.addingTimeInterval(180),
                    countryFlag: "🇫🇮"
                )
            ]
        )

        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(CompanionSnapshot.self, from: data)

        #expect(decoded == original)
        #expect(decoded.telegramItem?.countryFlag == "🇫🇮")
        #expect(decoded.telegramItem?.statusRaw == "ok")
    }
}
