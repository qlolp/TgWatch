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
