import Foundation
import SwiftUI
import Network
import TgWatchCore
@MainActor final class MonitorController: ObservableObject {
    @Published var history: [HistoryEntry] = []
    @Published var historyUnavailable = false
    let fixtureMode: Bool
    let session: MonitorSession
    private let store: HistoryStore
    private let path = NWPathMonitor()
    private var writeTask: Task<Void, Never>?
    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TgWatch")
        var isFixture = false
        var runner: any ProbeRunner = ProbeEngine(transport: AppleProbeTransport())
        var file = directory.appendingPathComponent("history-v1.json")
        #if DEBUG
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--fixture"), ProcessInfo.processInfo.arguments.count > index + 1 {
            isFixture = true
            runner = FixtureRunner(mode: ProcessInfo.processInfo.arguments[index+1])
            file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("fixture.json")
        }
        #endif
        fixtureMode = isFixture
        session = MonitorSession(runner: runner); store = HistoryStore(url: file)
        session.onChange = { [weak self] in self?.objectWillChange.send() }
        session.onObservation = { [weak self] value in self?.save(value) }
        path.pathUpdateHandler = { [weak self] value in
            let unavailable = value.status == .unsatisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                // Every delivered path update may change routing, even with identical interface names.
                self.session.networkChanged(unavailable: unavailable)
            }
        }
        if !isFixture { path.start(queue: DispatchQueue(label: "ru.tgwatch.ios.path")) }
        Task { await reload() }
    }
    private func save(_ snapshot: ProbeSnapshot) {
        let previous = writeTask
        writeTask = Task { [weak self, store] in
            await previous?.value
            do { try await store.append(HistoryEntry(epoch: snapshot.epoch, observedAt: snapshot.finishedAt, status: snapshot.status)) }
            catch { self?.historyUnavailable = true; return }
            await self?.reload()
        }
    }
    func reload() async {
        do { history = try await store.load(); historyUnavailable = false }
        catch { historyUnavailable = true }
    }
    func clearHistory() {
        let previous = writeTask
        writeTask = Task { [weak self, store] in
            await previous?.value
            do { try await store.clear(); await self?.reload() }
            catch { self?.historyUnavailable = true }
        }
    }
    deinit { path.cancel() }
}
#if DEBUG
private struct FixtureRunner: ProbeRunner {
    let mode: String
    func check(epoch: UInt64) async throws -> ProbeSnapshot {
        try await Task.sleep(for: mode == "slow" ? .seconds(30) : .milliseconds(50))
        let evidence = mode == "offline" ? ProbeEvidence(pathUnavailable: true) : ProbeEvidence(mtprotoConfirmed: true, webConfirmed: true, primaryMeasurementsComplete: true)
        return .init(epoch: epoch, startedAt: Date(), finishedAt: Date(), evidence: evidence)
    }
}
#endif
