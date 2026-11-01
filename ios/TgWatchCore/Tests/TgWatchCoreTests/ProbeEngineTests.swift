import XCTest
@testable import TgWatchCore
private actor TransportFixture: ProbeTransport {
    var active = 0, peak = 0, calls = 0
    let delay: Duration
    init(delay: Duration = .milliseconds(5)) { self.delay = delay }
    func check(_ endpoint: ProbeEndpoint, deadline: ContinuousClock.Instant) async throws -> Bool {
        active += 1; peak = max(peak, active); calls += 1
        defer { active -= 1 }
        try await Task.sleep(for: delay)
        return endpoint.group == .control
    }
    func counts() -> (Int,Int,Int) { (active,peak,calls) }
}
final class ProbeEngineTests: XCTestCase {
    func testBoundsConcurrencyAndClassifiesCompletedNegativePrimary() async throws {
        let transport = TransportFixture()
        let endpoints = (0..<17).map { ProbeEndpoint(name: "e\($0)", address: "unused", group: $0 == 0 ? .control : .web) }
        let engine = ProbeEngine(transport: transport, endpoints: endpoints)
        let result = try await engine.check(epoch: 12)
        XCTAssertEqual(result.status, .down); XCTAssertEqual(result.epoch, 12)
        let counts = await transport.counts()
        XCTAssertEqual(counts.0, 0); XCTAssertLessThanOrEqual(counts.1, 4); XCTAssertEqual(counts.2, 17)
    }
    func testDeadlineAndCancellationDoNotReportOutage() async throws {
        let transport = TransportFixture(delay: .seconds(30))
        let engine = ProbeEngine(transport: transport, budget: .milliseconds(40))
        let start = ContinuousClock.now
        let result = try await engine.check(epoch: 1)
        XCTAssertEqual(result.status, .unknown)
        XCTAssertFalse(result.evidence.primaryMeasurementsComplete)
        XCTAssertLessThan(start.duration(to: .now), .seconds(1))
        let task = Task { try await engine.check(epoch: 2) }
        try await Task.sleep(for: .milliseconds(5)); task.cancel()
        do { _ = try await task.value; XCTFail("cancel must throw") } catch is CancellationError {} catch { XCTFail("\(error)") }
        let counts = await transport.counts(); XCTAssertEqual(counts.0, 0)
    }
    func testConcurrentBatchesAreRejected() async throws {
        let engine = ProbeEngine(transport: TransportFixture(delay: .seconds(30)), budget: .milliseconds(80))
        let first = Task { try await engine.check(epoch: 1) }
        try await Task.sleep(for: .milliseconds(10))
        do { _ = try await engine.check(epoch: 2); XCTFail("overlap") } catch ProbeEngine.Failure.busy {} catch { XCTFail("\(error)") }
        _ = try await first.value
    }
}
