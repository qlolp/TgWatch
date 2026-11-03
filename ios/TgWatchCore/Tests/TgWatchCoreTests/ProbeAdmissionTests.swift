import Foundation
import Testing
@testable import TgWatchCore

private actor HeldProbe: ProbeRunner {
    private(set) var starts: [UInt64] = []
    private var continuations: [UInt64: CheckedContinuation<ProbeSnapshot, any Error>] = [:]

    func check(epoch: UInt64) async throws -> ProbeSnapshot {
        try await withCheckedThrowingContinuation { continuation in
            starts.append(epoch)
            continuations[epoch] = continuation
        }
    }

    func finish(_ epoch: UInt64, fail: Bool = false) {
        guard let continuation = continuations.removeValue(forKey: epoch) else { return }
        if fail { continuation.resume(throwing: CocoaError(.fileReadUnknown)) }
        else { continuation.resume(returning: .init(epoch: epoch, startedAt: Date(), finishedAt: Date(), evidence: .init())) }
    }
}

private func eventually(_ condition: @escaping () async -> Bool) async -> Bool {
    for _ in 0..<400 {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return false
}

struct ProbeAdmissionTests {
    @Test func limitsParallelWorkAndServesQueueInOrder() async throws {
        let admission = ProbeAdmission(limit: 2)
        let probe = HeldProbe()
        let first = Task { try await admission.check(using: probe, epoch: 1) }
        #expect(await eventually { await probe.starts == [1] })
        let second = Task { try await admission.check(using: probe, epoch: 2) }
        #expect(await eventually { await probe.starts == [1, 2] })
        let third = Task { try await admission.check(using: probe, epoch: 3) }
        #expect(await eventually { await admission.waitingCount == 1 })
        let fourth = Task { try await admission.check(using: probe, epoch: 4) }
        #expect(await eventually { await admission.waitingCount == 2 })
        #expect(await probe.starts == [1, 2])
        await probe.finish(1)
        _ = try await first.value
        #expect(await eventually { await probe.starts == [1, 2, 3] })
        await probe.finish(2)
        _ = try await second.value
        #expect(await eventually { await probe.starts == [1, 2, 3, 4] })
        await probe.finish(3)
        await probe.finish(4)
        _ = try await third.value
        _ = try await fourth.value
    }

    @Test func cancellationSkipsWaiterAndRetainsActiveSlotUntilTeardown() async throws {
        let admission = ProbeAdmission(limit: 1)
        let probe = HeldProbe()
        let first = Task { try await admission.check(using: probe, epoch: 1) }
        #expect(await eventually { await probe.starts == [1] })
        let cancelled = Task { try await admission.check(using: probe, epoch: 2) }
        #expect(await eventually { await admission.waitingCount == 1 })
        cancelled.cancel()
        do { _ = try await cancelled.value; Issue.record("Cancelled waiter returned a result") }
        catch { #expect(error is CancellationError) }
        let next = Task { try await admission.check(using: probe, epoch: 3) }
        #expect(await eventually { await admission.waitingCount == 1 })
        first.cancel()
        #expect(await probe.starts == [1])
        #expect(await admission.waitingCount == 1)
        await probe.finish(1) // Deliberately cancellation-insensitive transport teardown.
        do { _ = try await first.value; Issue.record("Cancelled active probe returned a result") }
        catch { #expect(error is CancellationError) }
        #expect(await eventually { await probe.starts == [1, 3] })
        await probe.finish(3)
        _ = try await next.value
    }

    @Test func transportErrorReleasesPermit() async throws {
        let admission = ProbeAdmission(limit: 1)
        let probe = HeldProbe()
        let first = Task { try await admission.check(using: probe, epoch: 1) }
        #expect(await eventually { await probe.starts == [1] })
        let next = Task { try await admission.check(using: probe, epoch: 2) }
        #expect(await eventually { await admission.waitingCount == 1 })
        await probe.finish(1, fail: true)
        do { _ = try await first.value; Issue.record("Transport error was swallowed") } catch {}
        #expect(await eventually { await probe.starts == [1, 2] })
        await probe.finish(2)
        #expect(try await next.value.epoch == 2)
    }
}
