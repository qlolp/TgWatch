import Testing
import Foundation
@testable import TgWatchCore

@Suite struct CompanionSnapshotTests {
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
