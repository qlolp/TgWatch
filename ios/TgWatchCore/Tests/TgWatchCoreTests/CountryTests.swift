import XCTest
@testable import TgWatchCore
private actor CountryFixture: CountryLookup {
    private var pending: [CheckedContinuation<String?, Error>] = []
    func country() async throws -> String? { try await withCheckedThrowingContinuation { pending.append($0) } }
    func count() -> Int { pending.count }
    func finish(_ iso: String?) { pending.removeFirst().resume(returning: iso) }
}
final class CountryTests: XCTestCase {
    func testTraceKeepsOnlyValidatedCountryAndRejectsAmbiguity() {
        XCTAssertEqual(EgressCountry.parse(Data("ip=203.0.113.7\nloc=de\nwarp=on\n".utf8)), "DE")
        for text in ["loc=XX", "loc=US\nloc=DE", "ip=US", "loc=USA", "loc=", "loc=ZZ"] {
            XCTAssertNil(EgressCountry.parse(Data(text.utf8)), text)
        }
        XCTAssertNil(EgressCountry.parse(Data(repeating: 65, count: 8193)))
        XCTAssertNil(EgressCountry.parse(Data([255,254])))
        XCTAssertEqual(EgressCountry.flag("DE"), "🇩🇪")
        XCTAssertEqual(EgressCountry.flag("ZZ"), "")
    }
    @MainActor func testNetworkChangeAndBackgroundRejectOldCountry() async throws {
        let provider = CountryFixture(), model = CountrySession(provider: CountryFixture())
        let session = CountrySession(provider: provider)
        session.setActive(true)
        try await wait(provider)
        session.networkChanged(unavailable: true)
        await provider.finish("DE")
        try await Task.sleep(for: .milliseconds(15))
        XCTAssertNil(session.iso); XCTAssertFalse(session.isFresh()); XCTAssertNil(model.iso)
        session.networkChanged(unavailable: false)
        try await wait(provider)
        session.setActive(false); await provider.finish("US")
        try await Task.sleep(for: .milliseconds(15)); XCTAssertNil(session.iso)
    }
    @MainActor func testForcedFailureDoesNotResurrectCountry() async throws {
        let provider = CountryFixture(), session = CountrySession(provider: CountryFixture())
        let model = CountrySession(provider: provider)
        model.setActive(true); try await wait(provider); await provider.finish("DE")
        try await Task.sleep(for: .milliseconds(15)); XCTAssertTrue(model.isFresh())
        model.refresh(); XCTAssertNil(model.iso)
        try await wait(provider); await provider.finish(nil)
        try await Task.sleep(for: .milliseconds(15)); XCTAssertNil(model.iso); XCTAssertTrue(model.failed)
        XCTAssertNil(session.iso)
    }
    @MainActor func testExpiryAndClockDiscontinuityCannotLookFresh() async throws {
        for rollback in [false, true] {
            let provider = CountryFixture(), clock = CountryClock()
            let model = CountrySession(provider: provider, now: { clock.wall() }, instant: { clock.instant() })
            model.setActive(true); try await wait(provider); await provider.finish("DE")
            try await Task.sleep(for: .milliseconds(15)); XCTAssertTrue(model.isFresh())
            clock.advance(wall: rollback ? -60 : 21600, monotonic: rollback ? 1 : 21600)
            XCTAssertFalse(model.isFresh()); XCTAssertEqual(model.iso, "DE") // may show explicitly stale, never verified
            model.setActive(false); XCTAssertNil(model.iso)
        }
    }
    private func wait(_ provider: CountryFixture) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while await provider.count() == 0 && ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(5)) }
        let count = await provider.count(); XCTAssertEqual(count, 1)
    }
}

private final class CountryClock: @unchecked Sendable {
    private let lock = NSLock()
    private var date = Date(), tick = ContinuousClock.now
    func wall() -> Date { lock.lock(); defer { lock.unlock() }; return date }
    func instant() -> ContinuousClock.Instant { lock.lock(); defer { lock.unlock() }; return tick }
    func advance(wall: Double, monotonic: Double) {
        lock.lock(); defer { lock.unlock() }
        date = date.addingTimeInterval(wall); tick = tick.advanced(by: .seconds(monotonic))
    }
}
