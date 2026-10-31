import Foundation
/// Same source as Android EgressCountry; never retains the trace's IP or other fields.
public enum EgressCountry {
    public static let endpoint = URL(string: "https://www.cloudflare.com/cdn-cgi/trace")!
    public static let maxBytes = 8192
    public static let freshness: Duration = .seconds(6 * 60 * 60)
    public static func valid(_ iso: String) -> Bool {
        iso.count == 2 && iso == iso.uppercased() && Locale.Region.isoRegions.contains { $0.identifier == iso }
    }
    public static func parse(_ bytes: Data) -> String? {
        guard bytes.count <= maxBytes, let text = String(data: bytes, encoding: .utf8) else { return nil }
        let values = text.split(whereSeparator: \.isNewline).compactMap { line -> String? in
            let line = line.trimmingCharacters(in: .whitespaces)
            guard line.lowercased().hasPrefix("loc=") else { return nil }
            return String(line.dropFirst(4)).trimmingCharacters(in: .whitespaces).uppercased()
        }
        guard values.count == 1, let value = values.first, valid(value) else { return nil }
        return value
    }
    public static func flag(_ iso: String) -> String {
        guard valid(iso) else { return "" }
        return iso.unicodeScalars.map { String(UnicodeScalar(0x1f1e6 + $0.value - 65)!) }.joined()
    }
}
public protocol CountryLookup: Sendable { func country() async throws -> String? }
@MainActor public final class CountrySession {
    public private(set) var iso: String?
    public private(set) var checkedAt: Date?
    public private(set) var checking = false
    public private(set) var failed = false
    public private(set) var unavailable = false
    public var onChange: (() -> Void)?
    private let provider: any CountryLookup
    private let now: @Sendable () -> Date
    private let instant: @Sendable () -> ContinuousClock.Instant
    private var checkedInstant: ContinuousClock.Instant?
    private var active = false
    private var generation: UInt64 = 0
    private var task: Task<Void, Never>?
    private var pending = false
    public init(provider: any CountryLookup, now: @escaping @Sendable () -> Date = { Date() },
                instant: @escaping @Sendable () -> ContinuousClock.Instant = { .now }) {
        self.provider = provider; self.now = now; self.instant = instant
    }
    public func isFresh() -> Bool {
        guard active, iso != nil, let checkedAt, let checkedInstant else { return false }
        let age = checkedInstant.duration(to: instant())
        let seconds = Double(age.components.seconds) + Double(age.components.attoseconds)/1e18
        return age >= .zero && age < EgressCountry.freshness && abs(now().timeIntervalSince(checkedAt) - seconds) < 5
    }
    public func setActive(_ value: Bool) {
        guard active != value else { return }
        active = value
        if value { if !isFresh() { refresh() } } else { invalidate() }
    }
    public func networkChanged(unavailable: Bool) {
        self.unavailable = unavailable; invalidate()
        if active && !unavailable { refresh() }
    }
    public func refresh() {
        guard active && !unavailable else { return }
        invalidate(); checking = true; onChange?()
        guard task == nil else { pending = true; return }
        let ticket = generation
        task = Task { [weak self, provider] in
            var result: String?
            do { result = try await provider.country() } catch { result = nil }
            guard let self else { return }
            if self.generation == ticket && self.active && !Task.isCancelled {
                self.iso = result.flatMap { EgressCountry.valid($0) ? $0 : nil }
                self.checkedAt = self.iso == nil ? nil : self.now()
                self.checkedInstant = self.iso == nil ? nil : self.instant()
                self.failed = self.iso == nil
            }
            self.task = nil; self.checking = false
            let retry = self.pending && self.active && !self.unavailable
            self.pending = false; self.onChange?()
            if retry { self.refresh() }
        }
    }
    private func invalidate() {
        generation &+= 1; task?.cancel(); pending = false; iso = nil; checkedAt = nil
        checkedInstant = nil; checking = false; failed = false; onChange?()
    }
    deinit { task?.cancel() }
}
