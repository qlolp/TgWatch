import XCTest
@testable import TgWatchCore
private actor SlowServiceTransport: ServiceTransport {
    var targets: [ServiceTarget] = []
    var pending: [CheckedContinuation<ServiceEvidence,Error>] = []
    func probe(target: ServiceTarget, deadline: ContinuousClock.Instant) async throws -> ServiceEvidence {
        targets.append(target)
        return try await withCheckedThrowingContinuation { pending.append($0) }
    }
    func control(deadline: ContinuousClock.Instant) async throws -> Bool { true }
    func count() -> Int { targets.count }
    func finish() { let target=targets.removeFirst(); pending.removeFirst().resume(returning:.init(target:target,outcome:target == .matrix ? .matrixVersions : .httpResponse,httpCode:200)) }
}
final class SelectedServicesTests: XCTestCase {
    @MainActor func testSwitchCannotPublishOldTargetOrOverlapPendingTransport() async throws {
        let transport=SlowServiceTransport(), monitor=SelectedServices(transport:SlowServiceTransport())
        let subject=SelectedServices(transport:transport)
        subject.setActive(true); subject.setVisible(true); subject.session.checkNow()
        try await wait(transport)
        subject.select(.whatsapp); subject.session.setMonitoring(true)
        let during=await transport.count(); XCTAssertEqual(during,1)
        XCTAssertNil(subject.session.snapshot)
        await transport.finish(); try await wait(transport)
        await transport.finish(); try await Task.sleep(for:.milliseconds(15))
        XCTAssertEqual(subject.session.snapshot?.evidence.service?.target,.whatsapp)
        XCTAssertNil(monitor.session.snapshot)
        subject.setVisible(false); XCTAssertNil(subject.session.snapshot)
        XCTAssertFalse(subject.session.active)
    }
    @MainActor func testHiddenBackgroundAndInvalidSettingsDoNotStartWork() async throws {
        let transport=SlowServiceTransport(), subject=SelectedServices(transport:SlowServiceTransport())
        let monitor=SelectedServices(transport:transport)
        monitor.setActive(true); monitor.session.setMonitoring(true)
        try await Task.sleep(for:.milliseconds(15)); let count=await transport.count(); XCTAssertEqual(count,0)
        monitor.select(.telegram); XCTAssertEqual(monitor.target,.matrix)
        monitor.setInterval(0); XCTAssertEqual(monitor.intervalSeconds,60)
        monitor.setInterval(300); XCTAssertEqual(monitor.intervalSeconds,300)
        monitor.setVisible(true); try await wait(transport)
        monitor.setActive(false); await transport.finish()
        try await Task.sleep(for:.milliseconds(15)); XCTAssertNil(monitor.session.snapshot)
        XCTAssertFalse(subject.session.monitoring)
    }
    @MainActor private func wait(_ transport: SlowServiceTransport) async throws {
        let deadline=ContinuousClock.now.advanced(by:.seconds(2))
        while await transport.count() == 0 && ContinuousClock.now < deadline { await Task.yield() }
        let count=await transport.count(); XCTAssertEqual(count,1)
    }
}
private struct ErrorServiceTransport: ServiceTransport {
    let controlResponds: Bool
    let timeout: Bool
    func probe(target: ServiceTarget, deadline: ContinuousClock.Instant) async throws -> ServiceEvidence {
        if timeout { throw URLError(.timedOut) }
        return .init(target:target,outcome:.httpError,httpCode:503)
    }
    func control(deadline: ContinuousClock.Instant) async throws -> Bool { controlResponds }
}
extension SelectedServicesTests {
    @MainActor func testTimeoutAndHTTPErrorNeedControlBeforeFailedCheckClassification() async throws {
        for timeout in [true,false] { for control in [true,false] {
            let monitor=SelectedServices(transport:ErrorServiceTransport(controlResponds:control,timeout:timeout))
            monitor.setActive(true); monitor.setVisible(true); monitor.session.checkNow()
            let deadline=ContinuousClock.now.advanced(by:.seconds(2))
            while monitor.session.snapshot == nil && ContinuousClock.now < deadline { await Task.yield() }
            XCTAssertEqual(monitor.session.snapshot?.status,control ? .down : .unknown)
            XCTAssertEqual(monitor.session.snapshot?.evidence.service?.outcome,timeout ? .timeout : .httpError)
            monitor.setVisible(false); XCTAssertNil(monitor.session.snapshot)
        } }
    }
    @MainActor func testUnavailablePathKeepsSelectedTargetAndSkipsTransport() async throws {
        let transport=SlowServiceTransport(), monitor=SelectedServices(transport:SlowServiceTransport())
        let subject=SelectedServices(transport:transport)
        var savedTarget: ServiceTarget?
        subject.onObservation = { target,_ in savedTarget=target }
        subject.select(.whatsapp); subject.setActive(true); subject.setVisible(true)
        subject.networkChanged(unavailable:true); subject.session.checkNow()
        XCTAssertEqual(subject.session.snapshot?.status,.noNetwork); XCTAssertEqual(savedTarget,.whatsapp)
        let count=await transport.count(); XCTAssertEqual(count,0)
        XCTAssertNil(monitor.session.snapshot)
    }
}
private struct DeadlineServiceTransport: ServiceTransport {
    func probe(target: ServiceTarget, deadline: ContinuousClock.Instant) async throws -> ServiceEvidence {
        try await Task.sleep(until:deadline,clock:.continuous); throw URLError(.timedOut)
    }
    func control(deadline: ContinuousClock.Instant) async throws -> Bool {
        try await Task.sleep(until:deadline,clock:.continuous); throw URLError(.timedOut)
    }
}
extension SelectedServicesTests {
    @MainActor func testPrimaryTimeoutEvidenceSurvivesControlDeadline() async throws {
        let subject=SelectedServices(transport:DeadlineServiceTransport())
        subject.setActive(true); subject.setVisible(true); subject.session.checkNow()
        let deadline=ContinuousClock.now.advanced(by:.seconds(8))
        while subject.session.snapshot == nil && ContinuousClock.now < deadline { try await Task.sleep(for:.milliseconds(10)) }
        XCTAssertEqual(subject.session.snapshot?.status,.unknown)
        XCTAssertEqual(subject.session.snapshot?.evidence.service?.outcome,.timeout)
        XCTAssertEqual(subject.session.snapshot?.evidence.service?.controlConfirmed,false)
    }
    @MainActor func testManualCheckAfterTargetSwitchWaitsForCanceledTeardown() async throws {
        let transport=SlowServiceTransport(), subject=SelectedServices(transport:SlowServiceTransport())
        let monitor=SelectedServices(transport:transport)
        monitor.setActive(true); monitor.setVisible(true); monitor.session.checkNow(); try await wait(transport)
        monitor.select(.whatsapp); monitor.session.checkNow()
        await transport.finish()
        let deadline=ContinuousClock.now.advanced(by:.seconds(2))
        while await transport.count() == 0 && ContinuousClock.now < deadline { await Task.yield() }
        guard await transport.count() == 1 else { XCTFail("manual replacement request was lost"); return }
        await transport.finish(); try await Task.sleep(for:.milliseconds(15))
        XCTAssertEqual(monitor.session.snapshot?.evidence.service?.target,.whatsapp)
        XCTAssertFalse(monitor.session.monitoring); XCTAssertNil(subject.session.snapshot)
    }
}
