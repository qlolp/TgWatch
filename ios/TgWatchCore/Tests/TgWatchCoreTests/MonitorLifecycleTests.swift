import XCTest
@testable import TgWatchCore
private actor SuspendedRunner: ProbeRunner {
    var continuations: [CheckedContinuation<ProbeSnapshot, Error>] = []
    var epochs: [UInt64] = []
    func check(epoch: UInt64) async throws -> ProbeSnapshot {
        epochs.append(epoch)
        return try await withCheckedThrowingContinuation { continuations.append($0) }
    }
    func count() -> Int { epochs.count }
    func finish() {
        let continuation = continuations.removeFirst(), epoch = epochs.removeFirst()
        continuation.resume(returning: ProbeSnapshot(epoch: epoch, startedAt: Date(), finishedAt: Date(), evidence: .init(mtprotoConfirmed: true, webConfirmed: true)))
    }
}
final class MonitorLifecycleTests: XCTestCase {
    @MainActor func testUnavailablePathRecordsNoNetworkWithoutTransport() async throws {
        let runner = SuspendedRunner(), session = MonitorSession(runner: SuspendedRunner())
        let offline = MonitorSession(runner: runner)
        offline.setActive(true); offline.networkChanged(unavailable: true); offline.checkNow()
        XCTAssertEqual(offline.snapshot?.status, .noNetwork)
        let calls = await runner.count(); XCTAssertEqual(calls, 0)
        XCTAssertNil(session.snapshot)
    }
    @MainActor func testBackgroundAndOldNetworkResultsCannotPublish() async throws {
        let runner = SuspendedRunner()
        let model = MonitorSession(runner: runner)
        model.setActive(true); model.checkNow(); model.checkNow()
        try await Task.sleep(for: .milliseconds(10))
        let count = await runner.count(); XCTAssertEqual(count, 1)
        model.setActive(false)
        await runner.finish(); try await Task.sleep(for: .milliseconds(10))
        XCTAssertNil(model.snapshot); XCTAssertFalse(model.checking)
        model.setActive(true); model.checkNow()
        try await Task.sleep(for: .milliseconds(10))
        model.networkChanged(unavailable: false)
        await runner.finish(); try await Task.sleep(for: .milliseconds(10))
        XCTAssertNil(model.snapshot)
    }
    @MainActor func testSuccessfulResultAndCancelPreserveNoFakeObservation() async throws {
        let runner = SuspendedRunner(), model = MonitorSession(runner: SuspendedRunner())
        // A separate runner makes accidental global ownership visible.
        let session = MonitorSession(runner: runner)
        session.setActive(true); session.checkNow()
        try await Task.sleep(for: .milliseconds(10)); await runner.finish()
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(session.snapshot?.status, .ok); XCTAssertNil(model.snapshot)
        session.setActive(false); XCTAssertNil(session.snapshot)
    }
    @MainActor func testOptInResumesButDoesNotRunInBackground() async throws {
        let runner = SuspendedRunner(), session = MonitorSession(runner: SuspendedRunner())
        let active = MonitorSession(runner: runner, interval: .milliseconds(15))
        active.setMonitoring(true)
        try await Task.sleep(for: .milliseconds(20)); let before = await runner.count(); XCTAssertEqual(before, 0)
        active.setActive(true)
        try await Task.sleep(for: .milliseconds(40)); let during = await runner.count(); XCTAssertEqual(during, 1)
        active.setActive(false); await runner.finish()
        try await Task.sleep(for: .milliseconds(20)); XCTAssertFalse(active.checking); XCTAssertNil(active.snapshot)
        XCTAssertFalse(session.monitoring)
    }
}

private actor SlowCancellationTransport: ProbeTransport {
    var calls = 0
    func check(_ endpoint: ProbeEndpoint, deadline: ContinuousClock.Instant) async throws -> Bool {
        calls += 1
        do { try await Task.sleep(for: .seconds(30)) } catch {
            // Model a real resource teardown that completes after Task.cancel returns.
            await withCheckedContinuation { continuation in
                DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) { continuation.resume() }
            }
            throw CancellationError()
        }
        return false
    }
    func count() -> Int { calls }
}
extension MonitorLifecycleTests {
    @MainActor func testNetworkRestartWaitsForOldTransportCleanup() async throws {
        let transport = SlowCancellationTransport()
        let engine = ProbeEngine(transport: transport, endpoints: [.init(name: "test", address: "unused", group: .web)])
        let model = MonitorSession(runner: engine)
        model.setMonitoring(true); model.setActive(true)
        let firstDeadline = ContinuousClock.now.advanced(by: .seconds(3))
        while await transport.count() < 1 && ContinuousClock.now < firstDeadline { try await Task.sleep(for: .milliseconds(5)) }
        let initialCalls = await transport.count(); XCTAssertEqual(initialCalls, 1)
        model.networkChanged(unavailable: false)
        // Same coarse path properties again must still invalidate the epoch.
        model.networkChanged(unavailable: false)
        let restartDeadline = ContinuousClock.now.advanced(by: .seconds(3))
        while await transport.count() < 2 && ContinuousClock.now < restartDeadline { try await Task.sleep(for: .milliseconds(5)) }
        let calls = await transport.count(); XCTAssertEqual(calls, 2)
        XCTAssertTrue(model.checking)
        model.setActive(false)
        try await Task.sleep(for: .milliseconds(80))
    }
}
private final class AdjustableClock: @unchecked Sendable {
    private let lock = NSLock()
    private var wallValue = Date()
    private var instantValue = ContinuousClock.now
    func wall() -> Date { lock.lock(); defer { lock.unlock() }; return wallValue }
    func instant() -> ContinuousClock.Instant { lock.lock(); defer { lock.unlock() }; return instantValue }
    func advance(wall: Double, monotonic: Double) {
        lock.lock(); defer { lock.unlock() }
        wallValue = wallValue.addingTimeInterval(wall); instantValue = instantValue.advanced(by: .seconds(monotonic))
    }
}
extension MonitorLifecycleTests {
    @MainActor func testWallClockRollbackCannotReviveStaleStatus() async throws {
        let clock = AdjustableClock(), runner = SuspendedRunner()
        let model = MonitorSession(runner: runner, now: { clock.wall() }, instant: { clock.instant() })
        model.setActive(true); model.checkNow()
        try await Task.sleep(for: .milliseconds(10)); await runner.finish()
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertTrue(model.isFresh())
        clock.advance(wall: 300, monotonic: 300); XCTAssertFalse(model.isFresh())
        clock.advance(wall: -240, monotonic: 1); XCTAssertFalse(model.isFresh())
    }
}
