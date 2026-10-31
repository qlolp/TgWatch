import Foundation
public protocol ServiceTransport: Sendable {
    /// Must honor cancellation and close the request at the monotonic deadline.
    func probe(target: ServiceTarget, deadline: ContinuousClock.Instant) async throws -> ServiceEvidence
    func control(deadline: ContinuousClock.Instant) async throws -> Bool
}
@MainActor private final class ServiceRunner: ProbeRunner {
    var target: ServiceTarget = .matrix
    let transport: any ServiceTransport
    init(transport: any ServiceTransport) { self.transport=transport }
    func check(epoch: UInt64) async throws -> ProbeSnapshot {
        let target=self.target, start=Date(), deadline=ContinuousClock.now.advanced(by:.seconds(6))
        var result: ServiceEvidence
        do { result=try await transport.probe(target:target,deadline:.now.advanced(by:.seconds(3))) }
        catch {
            try Task.checkCancellation()
            if error is CancellationError { throw error }
            let outcome: ServiceOutcome = (error as? URLError)?.code == .timedOut ? .timeout : .transportError
            result = .init(target:target,outcome:outcome)
        }
        try Task.checkCancellation()
        if result.target != target { result = .init(target:target,outcome:.unknown) }
        if ContinuousClock.now >= deadline && result.status == .ok { result = .init(target:target,outcome:.timeout,httpCode:result.httpCode) }
        if result.status != .ok && result.outcome != .unknown && ContinuousClock.now < deadline {
            do { result.controlConfirmed = try await transport.control(deadline:min(deadline,.now.advanced(by:.seconds(3)))) }
            catch { try Task.checkCancellation(); result.controlConfirmed=false }
        }
        try Task.checkCancellation()
        if ContinuousClock.now >= deadline { result.controlConfirmed = false }
        return .init(epoch:epoch,startedAt:start,finishedAt:Date(),evidence:.init(service:result))
    }
}
/// One extra target at a time; Telegram keeps its own session and alert policy.
@MainActor public final class SelectedServices {
    public private(set) var target: ServiceTarget = .matrix
    public private(set) var intervalSeconds = 60
    public let session: MonitorSession
    public var onChange: (() -> Void)?
    public var onObservation: ((ServiceTarget,ProbeSnapshot) -> Void)?
    private let runner: ServiceRunner
    private var active=false, visible=false
    public init(transport: any ServiceTransport) {
        runner=ServiceRunner(transport:transport)
        session=MonitorSession(runner:runner,interval:.seconds(60))
        session.onChange = { [weak self] in self?.onChange?() }
        session.onObservation = { [weak self] value in
            guard let self else { return }
            self.onObservation?(self.target,value)
        }
    }
    public func select(_ target: ServiceTarget) {
        guard target != .telegram && target != self.target else { return }
        self.target=target; runner.target=target; session.clockChanged(); onChange?()
    }
    public func setInterval(_ seconds: Int) {
        guard [60,120,300].contains(seconds), seconds != intervalSeconds else { return }
        intervalSeconds=seconds; session.setInterval(.seconds(seconds)); onChange?()
    }
    public func setVisible(_ value: Bool) { visible=value; session.setActive(active && visible) }
    public func setActive(_ value: Bool) { active=value; session.setActive(active && visible) }
    public func networkChanged(unavailable: Bool) { session.networkChanged(unavailable:unavailable) }
    public func clockChanged() { session.clockChanged() }
}
