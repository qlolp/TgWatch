import Foundation
/// Lifecycle policy is platform independent; UI only forwards scene/path changes.
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
    private var epoch: UInt64 = 0
    private var generation: UInt64 = 0
    private var batch: Task<Void, Never>?
    private var loop: Task<Void, Never>?
    public init(runner: any ProbeRunner, interval: Duration = .seconds(60)) { self.runner = runner; self.interval = interval }
    public func checkNow() {
        guard active, batch == nil else { return }
        if let snapshot, Date() < snapshot.finishedAt { epoch &+= 1; self.snapshot = nil }
        if pathUnavailable {
            let now = Date()
            let result = ProbeSnapshot(epoch: epoch, startedAt: now, finishedAt: now, evidence: .init(pathUnavailable: true))
            snapshot = result; onObservation?(result); onChange?(); return
        }
        let capturedEpoch = epoch, capturedGeneration = generation
        checking = true; onChange?()
        batch = Task { [weak self, runner] in
            do {
                let result = try await runner.check(epoch: capturedEpoch)
                guard let self, !Task.isCancelled, self.active, self.epoch == capturedEpoch,
                      self.generation == capturedGeneration, result.epoch == capturedEpoch else { return }
                self.snapshot = result; self.onObservation?(result)
            } catch { /* Cancellation and failed admission are never recorded as outages. */ }
            guard let self, self.generation == capturedGeneration else { return }
            self.batch = nil; self.checking = false; self.onChange?()
        }
    }
    public func cancel() {
        generation &+= 1; batch?.cancel(); batch = nil; checking = false; onChange?()
    }
    public func setActive(_ value: Bool) {
        guard value != active else { return }
        active = value; epoch &+= 1; snapshot = nil; cancel(); restartLoop(); onChange?()
    }
    public func setMonitoring(_ value: Bool) { monitoring = value; restartLoop(); onChange?() }
    public func networkChanged(unavailable: Bool) {
        pathUnavailable = unavailable; epoch &+= 1; snapshot = nil; cancel(); restartLoop(); onChange?()
    }
    private func restartLoop() {
        loop?.cancel(); loop = nil
        guard active && monitoring else { return }
        checkNow()
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
