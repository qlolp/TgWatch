import Foundation
import SwiftUI
import Network
import TgWatchCore
@MainActor final class MonitorController: ObservableObject {
    @Published var history: [HistoryEntry] = []
    @Published var historyUnavailable = false
    @Published private(set) var profile: CheckProfile = .economy
    @Published private(set) var preferencesUnavailable = false
    private let preferencesURL: URL
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
        preferencesURL = file.deletingLastPathComponent().appendingPathComponent("preferences.json")
        session = MonitorSession(runner: runner); store = HistoryStore(url: file)
        if FileManager.default.fileExists(atPath: preferencesURL.path) {
            do {
                let data = try Data(contentsOf: preferencesURL)
                profile = try JSONDecoder().decode(CheckProfile.self, from: data)
            } catch { preferencesUnavailable = true }
        }
        session.setCheckInterval(seconds: profile.rawValue)
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
        let lifetime = session.resultLifetime
        writeTask = Task { [weak self, store] in
            await previous?.value
            do { try await store.append(HistoryEntry(epoch: snapshot.epoch, observedAt: snapshot.finishedAt, status: snapshot.status, validForSeconds: lifetime)) }
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
    func setProfile(_ value: CheckProfile) {
        profile = value
        session.setCheckInterval(seconds: value.rawValue)
        do {
            try FileManager.default.createDirectory(at: preferencesURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(value).write(to: preferencesURL, options: .atomic)
            preferencesUnavailable = false
        } catch { preferencesUnavailable = true }
    }

    deinit { path.cancel() }
}
enum CheckProfile: Int, Codable, CaseIterable, Identifiable {
    case economy = 60
    case balanced = 30
    case frequent = 15

    var id: Int { rawValue }
    var title: LocalizedStringKey {
        switch self {
        case .economy: "profileEconomy"
        case .balanced: "profileBalanced"
        case .frequent: "profileFrequent"
        }
    }
}

#if DEBUG
private struct FixtureRunner: ProbeRunner {
    let mode: String
    func check(epoch: UInt64) async throws -> ProbeSnapshot {
        try await Task.sleep(for: mode == "slow" ? .seconds(300) : .milliseconds(50))
        let evidence = mode == "offline" ? ProbeEvidence(pathUnavailable: true) : ProbeEvidence(mtprotoConfirmed: true, webConfirmed: true, primaryMeasurementsComplete: true)
        return .init(epoch: epoch, startedAt: Date(), finishedAt: Date(), evidence: evidence)
    }
}
#endif
