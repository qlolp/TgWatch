import XCTest
@testable import TgWatchCore
@MainActor private final class DelayedAlertScheduler: AlertScheduler {
    var submitted: [String] = []
    var removed: [String] = []
    var completion: CheckedContinuation<Void, Error>?
    func add(id: String, event: ConnectionAlert, observedAt: Date) async throws {
        submitted.append(id)
        try await withCheckedThrowingContinuation { completion = $0 }
    }
    func remove(id: String) { removed.append(id) }
}
final class AlertDeliveryTests: XCTestCase {
    @MainActor func testInvalidatedLossDoesNotSuppressNewRouteLoss() async throws {
        let scheduler = DelayedAlertScheduler(), delivery = AlertDelivery(scheduler: DelayedAlertScheduler())
        let subject = AlertDelivery(scheduler: scheduler)
        var policy = ConnectionAlertPolicy()
        subject.onSubmitted = { policy.confirmSubmission($0) }
        let date = Date()
        XCTAssertNil(policy.observe(.down, at: date, epoch: 1))
        let event = try XCTUnwrap(policy.observe(.down, at: date.addingTimeInterval(30), epoch: 1))
        subject.submit(event: event, observedAt: date, eligible: { true })
        try await wait(scheduler); subject.invalidate()
        scheduler.completion?.resume(); scheduler.completion = nil
        for _ in 0..<20 { await Task.yield() }
        XCTAssertNil(policy.observe(.down, at: date.addingTimeInterval(60), epoch: 2))
        XCTAssertEqual(policy.observe(.down, at: date.addingTimeInterval(90), epoch: 2), .telegramLost)
        delivery.invalidate()
    }
    @MainActor private func wait(_ scheduler: DelayedAlertScheduler) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while scheduler.completion == nil && ContinuousClock.now < deadline { await Task.yield() }
        XCTAssertNotNil(scheduler.completion)
    }
    @MainActor func testDeniedAdmissionAndSchedulingFailure() async throws {
        let scheduler = DelayedAlertScheduler(), delivery = AlertDelivery(scheduler: DelayedAlertScheduler())
        let subject = AlertDelivery(scheduler: scheduler)
        subject.submit(event: .telegramLost, observedAt: Date(), eligible: { false })
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(scheduler.submitted.isEmpty)
        var failures = 0; subject.onFailure = { failures += 1 }
        subject.submit(event: .telegramLost, observedAt: Date(), eligible: { true })
        try await wait(scheduler)
        let id = try XCTUnwrap(scheduler.submitted.first)
        scheduler.completion?.resume(throwing: URLError(.unknown)); scheduler.completion = nil
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(failures, 1); XCTAssertFalse(subject.canPresent(id: id)); XCTAssertFalse(delivery.canPresent(id: id))
    }

    @MainActor func testInvalidationRemovesLateSubmissionAndSuppressesPresentation() async throws {
        let scheduler = DelayedAlertScheduler(), now = Date()
        let delivery = AlertDelivery(scheduler: scheduler)
        delivery.submit(event: .telegramLost, observedAt: now, eligible: { true })
        try await wait(scheduler)
        let id = try XCTUnwrap(scheduler.submitted.first)
        XCTAssertTrue(delivery.canPresent(id: id))
        delivery.invalidate() // opt-out, inactive, route or clock transition
        XCTAssertFalse(delivery.canPresent(id: id))
        scheduler.completion?.resume(); scheduler.completion = nil
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(scheduler.removed.filter { $0 == id }.count, 2)
    }
    @MainActor func testPermissionOrFreshnessLossRejectsLatePresentation() async throws {
        let scheduler = DelayedAlertScheduler(), delivery = AlertDelivery(scheduler: DelayedAlertScheduler())
        let subject = AlertDelivery(scheduler: scheduler)
        var allowed = true
        subject.submit(event: .networkLost, observedAt: Date(), eligible: { allowed })
        try await wait(scheduler)
        let id = try XCTUnwrap(scheduler.submitted.first)
        allowed = false
        XCTAssertFalse(subject.canPresent(id: id)); XCTAssertFalse(delivery.canPresent(id: id))
        scheduler.completion?.resume(); scheduler.completion = nil
        for _ in 0..<20 { await Task.yield() }
        XCTAssertTrue(scheduler.removed.contains(id))
    }
}
