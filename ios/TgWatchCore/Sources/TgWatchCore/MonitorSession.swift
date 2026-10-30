import Foundation
/// Lifecycle policy is platform independent; UI only forwards scene/path/clock changes.
@MainActor public final class MonitorSession {
    public private(set) var snapshot: ProbeSnapshot?
    public private(set) var checking = false
    public private(set) var monitoring = false
    public private(set) var active = false
    public private(set) var pathUnavailable = false
    public var onChange: (() -> Void)?
    public var onObservation: ((ProbeSnapshot) -> Void)?
    private let runner: any ProbeRunner
    private let interval: Duration
    private let now: @Sendable () -> Date
    private let instant: @Sendable () -> ContinuousClock.Instant
    private var receivedWall: Date?
    private var receivedInstant: ContinuousClock.Instant?
    private var epoch: UInt64 = 0
    private var generation: UInt64 = 0
    private var pendingCheck = false
    private var batch: Task<Void, Never>?
    private var loop: Task<Void, Never>?
    public init(runner: any ProbeRunner, interval: Duration = .seconds(60),
                now: @escaping @Sendable () -> Date = { Date() },
                instant: @escaping @Sendable () -> ContinuousClock.Instant = { .now }) {
        self.runner = runner; self.interval = interval; self.now = now; self.instant = instant
    }
    public func isFresh() -> Bool {
        guard snapshot != nil, let receivedInstant, let receivedWall else { return false }
        let age = receivedInstant.duration(to: instant())
        let seconds = Double(age.components.seconds) + Double(age.components.attoseconds) / 1e18
        return age >= .zero && age <= .seconds(120) && abs(now().timeIntervalSince(receivedWall) - seconds) <= 5
    }
    public func checkNow() {
        guard active, batch == nil else { return }
        if snapshot != nil && !isFresh() { epoch &+= 1; snapshot = nil }
        if pathUnavailable {
            let time = now()
            accept(.init(epoch: epoch, startedAt: time, finishedAt: time, evidence: .init(pathUnavailable: true)))
            onChange?(); return
        }
        let capturedEpoch = epoch, capturedGeneration = generation
        checking = true; onChange?()
        batch = Task { [weak self, runner] in
            do {
                let result = try await runner.check(epoch: capturedEpoch)
                if let self, !Task.isCancelled, self.active, self.epoch == capturedEpoch,
                   self.generation == capturedGeneration, result.epoch == capturedEpoch { self.accept(result) }
            } catch { /* Cancellation and failed admission are never recorded as outages. */ }
            guard let self else { return }
            // Keep ownership until transport teardown finishes, even after cancel().
            self.batch = nil; self.checking = false
            let restart = self.pendingCheck && self.active
            self.pendingCheck = false; self.onChange?()
            if restart { self.checkNow() }
        }
    }
    private func accept(_ result: ProbeSnapshot) {
        snapshot = result; receivedWall = now(); receivedInstant = instant(); onObservation?(result)
    }
    public func cancel() {
        generation &+= 1; pendingCheck = false; batch?.cancel(); checking = false; onChange?()
    }
    public func setActive(_ value: Bool) {
        guard value != active else { return }
        active = value; invalidate()
    }
    public func setMonitoring(_ value: Bool) { monitoring = value; restartLoop(); onChange?() }
    public func networkChanged(unavailable: Bool) { pathUnavailable = unavailable; invalidate() }
    public func clockChanged() { invalidate() }
    private func invalidate() {
        epoch &+= 1; snapshot = nil; receivedInstant = nil; receivedWall = nil
        cancel(); restartLoop(); onChange?()
    }
    private func restartLoop() {
        loop?.cancel(); loop = nil; pendingCheck = false
        guard active && monitoring else { return }
        if batch != nil { pendingCheck = true } else { checkNow() }
        loop = Task { [weak self, interval] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: interval) } catch { return }
                guard let self, self.active, self.monitoring else { return }
                self.checkNow()
            }
        }
    }
    deinit { batch?.cancel(); loop?.cancel() }
}
