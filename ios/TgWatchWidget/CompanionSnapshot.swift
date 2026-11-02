import Foundation

public struct CompanionSnapshot: Codable, Equatable, Sendable {
    public struct Item: Codable, Equatable, Sendable, Identifiable {
        public let id: String
        public let displayName: String
        public let statusRaw: String
        public let latencyMilliseconds: Double?
        public let observedAt: Date
        public let validUntil: Date
        public let countryFlag: String?

        public init(
            id: String,
            displayName: String,
            statusRaw: String,
            latencyMilliseconds: Double?,
            observedAt: Date,
            validUntil: Date,
            countryFlag: String? = nil
        ) {
            self.id = id
            self.displayName = displayName
            self.statusRaw = statusRaw
            self.latencyMilliseconds = latencyMilliseconds
            self.observedAt = observedAt
            self.validUntil = validUntil
            self.countryFlag = countryFlag
        }

        public func isStale(at date: Date = Date()) -> Bool {
            date > validUntil
        }
    }

    public let generatedAt: Date
    public let items: [Item]

    public init(generatedAt: Date = Date(), items: [Item]) {
        self.generatedAt = generatedAt
        self.items = items
    }

    public var telegramItem: Item? {
        items.first(where: { $0.id == "telegram" })
    }
}
