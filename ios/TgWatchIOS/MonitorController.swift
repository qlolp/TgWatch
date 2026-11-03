import Foundation
import SwiftUI
import TgWatchCore
@MainActor final class MonitorController: ObservableObject {
    @Published var history: [HistoryEntry] = []
    @Published private(set) var longTermHistory = LongTermHistory()
    @Published private(set) var minuteBuckets: [HistoryBucket] = []
    @Published private(set) var coarseBuckets: [HistoryBucket] = []
    @Published var historyUnavailable = false
    @Published private(set) var profile: CheckProfile = .economy
    @Published private(set) var intervalSeconds = 60
    @Published private(set) var preferencesUnavailable = false
    let definition: ServiceDefinition
    let dataDirectory: URL
    @Published var alertSettings = AlertSettings()
    @Published var alertError = false
    @Published private(set) var webhookSettings = WebhookSettings()
    @Published private(set) var webhookDelivered: Bool?
    @Published private(set) var webhookError = false
    var onObservationPublished: (() -> Void)?
    private let webhookSender = WebhookSender()
    private var webhookTask: Task<Void, Never>?
    private var webhookGeneration = 0
    private var lastObservedStatus: ProbeStatus?
    private var lastObservation: (date: Date, lifetime: Double)?
    private var alertTransition = AlertTransition()
    private var slowResponseAlert = SlowResponseAlert()
    private let alertClockOrigin = ContinuousClock.now
    private let preferencesURL: URL
    let fixtureMode: Bool
    let session: MonitorSession
    private let store: HistoryStore
    private var networkSubscription: UUID?
    private static let admission = ProbeAdmission(limit: 2)
    private var networkKind = "unknown"
    private var maintenance: [MaintenanceWindow] = []
    private var writeTask: Task<Void, Never>?
    init(definition: ServiceDefinition = .telegram) {
        self.definition = definition
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TgWatch")
        let directory = definition.kind == .telegram ? root : root.appendingPathComponent("targets/\(definition.id)/\(definition.revision.uuidString)")
        var isFixture = false
        var runner: any ProbeRunner = definition.kind == .telegram ? ProbeEngine(transport: AppleProbeTransport()) : ServiceProbeRunner(definition: definition)
        var file = directory.appendingPathComponent("history-v1.json")
        #if DEBUG
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--fixture"), ProcessInfo.processInfo.arguments.count > index + 1 {
            isFixture = true
            runner = FixtureRunner(mode: ProcessInfo.processInfo.arguments[index+1])
            file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("fixture.json")
        }
        #endif
        dataDirectory = file.deletingLastPathComponent()
        fixtureMode = isFixture
        preferencesURL = file.deletingLastPathComponent().appendingPathComponent("preferences.json")
        session = MonitorSession(runner: AdmittedProbeRunner(base: runner, admission: Self.admission))
        store = HistoryStore(url: file)
        if FileManager.default.fileExists(atPath: preferencesURL.path) {
            do {
                let data = try Data(contentsOf: preferencesURL)
                profile = try JSONDecoder().decode(CheckProfile.self, from: data)
            } catch { preferencesUnavailable = true }
        }
        let alertsURL = dataDirectory.appendingPathComponent("alerts.json")
        if FileManager.default.fileExists(atPath: alertsURL.path) {
            do { alertSettings = try JSONDecoder().decode(AlertSettings.self, from: Data(contentsOf: alertsURL)) }
            catch { alertError = true }
        }
        intervalSeconds = profile.rawValue
        let intervalURL = dataDirectory.appendingPathComponent("interval.json")
        if FileManager.default.fileExists(atPath: intervalURL.path) {
            do {
                let value = try JSONDecoder().decode(Int.self, from: Data(contentsOf: intervalURL))
                guard (10...300).contains(value) else { throw CocoaError(.fileReadCorruptFile) }
                intervalSeconds = value
            } catch { preferencesUnavailable = true }
        }
        session.setCheckInterval(seconds: intervalSeconds)
        let hookURL = dataDirectory.appendingPathComponent("webhook.json")
        if FileManager.default.fileExists(atPath: hookURL.path) {
            do { webhookSettings = try JSONDecoder().decode(WebhookSettings.self, from: Data(contentsOf: hookURL)) }
            catch { webhookError = true }
        }
        session.onChange = { [weak self] in
            self?.objectWillChange.send()
            self?.onObservationPublished?() // Includes route/lifecycle/clock invalidation, not only new results.
        }
        session.onObservation = { [weak self] value in self?.save(value) }
        if !isFixture {
            networkSubscription = NetworkPathObserver.shared.hub.subscribe { [weak self] state in
                guard let self else { return }
                self.networkKind = state.kind
                self.resetContinuity()
                self.session.networkChanged(unavailable: state.unavailable)
            }
        }
        Task { await reload() }
    }
    private func save(_ snapshot: ProbeSnapshot) {
        if MaintenanceSchedule.active(maintenance, at: snapshot.finishedAt) {
            resetContinuity()
            return
        }
        let previous = writeTask
        let lifetime = session.resultLifetime
        let event = alertTransition.observe(snapshot.status, at: snapshot.finishedAt, validFor: lifetime)
        if let lastObservation,
           snapshot.finishedAt < lastObservation.date || snapshot.finishedAt.timeIntervalSince(lastObservation.date) > lastObservation.lifetime {
            lastObservedStatus = nil
            slowResponseAlert.resetContinuity()
        }
        lastObservation = (snapshot.finishedAt, lifetime)
        let previousStatus = lastObservedStatus
        lastObservedStatus = snapshot.status
        let elapsed = alertClockOrigin.duration(to: .now).components
        let monotonicSeconds = Double(elapsed.seconds) + Double(elapsed.attoseconds) / 1e18
        let slow = slowResponseAlert.observe(status: snapshot.status, latency: snapshot.latencyMilliseconds,
            threshold: alertSettings.slowThresholdMilliseconds.map(Double.init), uptime: monotonicSeconds)
        if slow, !fixtureMode {
            let title = definition.displayName
            let sound = alertSettings.audible(at: snapshot.finishedAt)
            Task { [weak self] in
                do { try await MonitorNotifications.shared.sendSlowResponse(target: title, sound: sound) }
                catch { self?.alertError = true }
            }
        }
        if !fixtureMode, event != nil || (previousStatus == .noNetwork && snapshot.status == .ok) {
            sendWebhook(snapshot.status, snapshot: snapshot, previous: previousStatus)
        }
        if let event, alertSettings.enabled(event), !fixtureMode {
            let title = definition.displayName
            let sound = alertSettings.audible(at: snapshot.finishedAt)
            Task { [weak self] in
                do { try await MonitorNotifications.shared.send(target: title, status: event, sound: sound) }
                catch { self?.alertError = true }
            }
        }
        let kind = networkKind
        writeTask = Task { [weak self, store] in
            await previous?.value
            do { try await store.append(HistoryEntry(epoch: snapshot.epoch, observedAt: snapshot.finishedAt, status: snapshot.status, validForSeconds: lifetime, latencyMilliseconds: snapshot.latencyMilliseconds, networkKind: kind)) }
            catch { self?.historyUnavailable = true; return }
            await self?.reload()
        }
    }
    func reload() async {
        do {
            history = try await store.load()
            let buckets = await store.storedBuckets()
            minuteBuckets = buckets.minutes
            coarseBuckets = buckets.coarse
            longTermHistory = try await store.longTermHistory()
            historyUnavailable = false
            onObservationPublished?()
        }
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
    func setMaintenance(_ windows: [MaintenanceWindow]) {
        maintenance = MaintenanceSchedule.sanitized(windows) ?? []
    }
    func setMonitoring(_ value: Bool) {
        resetContinuity()
        session.setMonitoring(value)
    }
    func setProfile(_ value: CheckProfile) {
        profile = value
        setInterval(value.rawValue)
        do {
            try FileManager.default.createDirectory(at: preferencesURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(value).write(to: preferencesURL, options: .atomic)
        } catch { preferencesUnavailable = true }
    }

    func setInterval(_ value: Int) {
        intervalSeconds = min(300, max(10, value))
        session.setCheckInterval(seconds: intervalSeconds)
        resetContinuity()
        do {
            try FileManager.default.createDirectory(at: dataDirectory, withIntermediateDirectories: true)
            try JSONEncoder().encode(intervalSeconds).write(to: dataDirectory.appendingPathComponent("interval.json"), options: .atomic)
            preferencesUnavailable = false
        } catch { preferencesUnavailable = true }
    }
    /// Drops a delivery still running on this controller so an endpoint replacement cannot finish it.
    func cancelInFlightWebhook() {
        webhookGeneration += 1
        webhookTask?.cancel()
    }
    func saveWebhook(_ value: WebhookSettings) {
        guard value.valid else { webhookError = true; return }
        do {
            try FileManager.default.createDirectory(at: dataDirectory, withIntermediateDirectories: true)
            let url = dataDirectory.appendingPathComponent("webhook.json")
            try BackupFileStore.writePrivate(JSONEncoder().encode(value), to: url, limit: BackupContents.settingsLimit)
            webhookSettings = value; webhookError = false; webhookDelivered = nil
            webhookGeneration += 1; webhookTask?.cancel()
        } catch { webhookError = true }
    }
    func sendWebhook(_ status: ProbeStatus, snapshot: ProbeSnapshot? = nil, previous: ProbeStatus? = nil) {
        guard webhookSettings.enabled, webhookTask == nil, !fixtureMode else { return }
        let generation = webhookGeneration, settings = webhookSettings, targetID = definition.id
        webhookTask = Task { [weak self, webhookSender] in
            let delivered = (try? await webhookSender.send(settings: settings, event: WebhookEvent(serviceID: targetID, snapshot: snapshot, previous: previous))) == true
            guard let self else { return }
            if generation == self.webhookGeneration && !Task.isCancelled { self.webhookDelivered = delivered }
            self.webhookTask = nil
        }
    }
    func saveAlerts() {
        slowResponseAlert.resetContinuity()
        do {
            try FileManager.default.createDirectory(at: dataDirectory, withIntermediateDirectories: true)
            try JSONEncoder().encode(alertSettings).write(to: dataDirectory.appendingPathComponent("alerts.json"), options: .atomic)
            alertError = false
        } catch { alertError = true }
    }
    func flushHistoryForBackup() async throws {
        await writeTask?.value
        do {
            try await store.flush()
            historyUnavailable = false
        } catch {
            historyUnavailable = true
            throw error
        }
    }

    func stopAndFlush() async {
        session.setMonitoring(false)
        session.setActive(false)
        webhookTask?.cancel()
        await webhookTask?.value
        await writeTask?.value
    }
    func replaceHistory(_ entries: [HistoryEntry]) async throws {
        let wasActive = session.active
        session.setMonitoring(false)
        session.setActive(false)
        defer { session.setActive(wasActive && UIApplication.shared.applicationState == .active) }
        resetContinuity()
        await writeTask?.value
        try await store.replace(entries)
        await reload()
    }
    func resetContinuity() {
        alertTransition = AlertTransition()
        slowResponseAlert.resetContinuity()
        lastObservedStatus = nil
        lastObservation = nil
    }
    deinit {
        if let networkSubscription {
            Task { @MainActor in NetworkPathObserver.shared.hub.unsubscribe(networkSubscription) }
        }
    }
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
