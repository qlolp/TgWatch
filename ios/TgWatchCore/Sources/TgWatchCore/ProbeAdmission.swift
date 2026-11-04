import Foundation

/// Bounds complete service measurements; a cancelled active probe retains its slot
/// until its transport has finished cleaning up. Individual probes bound their own sockets.
public actor ProbeAdmission {
    private struct Waiter {
        let id: UUID
        let continuation: CheckedContinuation<Void, any Error>
    }

    private let limit: Int
    private var active = 0
    private var waiters: [Waiter] = []
    var waitingCount: Int { waiters.count }

    public init(limit: Int = 2) { self.limit = max(1, limit) }

    public func check(using runner: any ProbeRunner, epoch: UInt64) async throws -> ProbeSnapshot {
        try await acquire()
        defer { release() }
        // Cancellation may race with a queued permit being granted.
        try Task.checkCancellation()
        let result = try await runner.check(epoch: epoch)
        try Task.checkCancellation()
        return result
    }

    private func acquire() async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                } else if active < limit {
                    active += 1
                    continuation.resume()
                } else {
                    waiters.append(Waiter(id: id, continuation: continuation))
                }
            }
        } onCancel: {
            Task { await self.cancelWaiter(id) }
        }
    }

    private func cancelWaiter(_ id: UUID) {
        guard let index = waiters.firstIndex(where: { $0.id == id }) else { return }
        waiters.remove(at: index).continuation.resume(throwing: CancellationError())
    }

    private func release() {
        if waiters.isEmpty { active -= 1 }
        else { waiters.removeFirst().continuation.resume() }
    }
}

public struct AdmittedProbeRunner: ProbeRunner {
    private let base: any ProbeRunner
    private let admission: ProbeAdmission

    public init(base: any ProbeRunner, admission: ProbeAdmission) {
        self.base = base
        self.admission = admission
    }

    public func check(epoch: UInt64) async throws -> ProbeSnapshot {
        try await admission.check(using: base, epoch: epoch)
    }
}
