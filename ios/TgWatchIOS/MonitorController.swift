import Foundation
import SwiftUI
import Network
import UserNotifications
import TgWatchCore
@MainActor final class MonitorController: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published var history: [HistoryEntry] = []
    @Published var historyUnavailable = false
    @Published var alertsEnabled = false
    @Published var notificationPermission = "permission.unknown"
    @Published var notificationFailure = false
    @Published var serviceSettingsUnavailable = false
    let country: CountrySession
    let services: SelectedServices
    private var alertPolicy = ConnectionAlertPolicy()
    private let delivery = AlertDelivery(scheduler: AppleAlertScheduler())
    private var notificationsAllowed = false
    private let preferenceFile: URL
    private let serviceSettingsStore: ServiceSettingsStore
    private let historySessionID = UUID()
    let fixtureMode: Bool
    let session: MonitorSession
    private let store: HistoryStore
    private let path = NWPathMonitor()
    private var writeTask: Task<Void, Never>?
    override init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TgWatch")
        preferenceFile = directory.appendingPathComponent("notification-preference.json")
        var serviceSettingsFile=directory.appendingPathComponent("service-settings-v1.json")
        var isFixture = false
        var serviceTransport: any ServiceTransport = AppleServiceTransport()
        var countryProvider: any CountryLookup = CloudflareCountryLookup()
        var runner: any ProbeRunner = ProbeEngine(transport: AppleProbeTransport())
        var file = directory.appendingPathComponent("history-v1.json")
        #if DEBUG
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--fixture"), ProcessInfo.processInfo.arguments.count > index + 1 {
            isFixture = true
            serviceTransport = FixtureServiceTransport(mode: ProcessInfo.processInfo.arguments[index+1])
            countryProvider = CountryFixtureRunner(mode: ProcessInfo.processInfo.arguments[index+1])
            runner = FixtureRunner(mode: ProcessInfo.processInfo.arguments[index+1])
            var fixtureID=UUID()
            if let key=ProcessInfo.processInfo.arguments.firstIndex(of:"--fixture-store"),
               ProcessInfo.processInfo.arguments.count > key+1,
               let supplied=UUID(uuidString:ProcessInfo.processInfo.arguments[key+1]) { fixtureID=supplied }
            let fixtureFolder=FileManager.default.temporaryDirectory.appendingPathComponent("tgwatch-fixture-"+fixtureID.uuidString)
            file = fixtureFolder.appendingPathComponent("fixture.json")
            serviceSettingsFile=fixtureFolder.appendingPathComponent("service-settings-v1.json")
            if ProcessInfo.processInfo.arguments[index+1] == "settings-corrupt", !FileManager.default.fileExists(atPath:serviceSettingsFile.path) {
                try? FileManager.default.createDirectory(at:fixtureFolder,withIntermediateDirectories:true)
                try? Data("{broken".utf8).write(to:serviceSettingsFile)
            }
        }
        #endif
        fixtureMode = isFixture
        let settingsStore=ServiceSettingsStore(url:serviceSettingsFile)
        serviceSettingsStore=settingsStore
        let savedSettings: ServiceSettings
        var settingsLoadFailed=false
        do { savedSettings=try settingsStore.load() }
        catch { savedSettings = .defaults; settingsLoadFailed=true }
        country = CountrySession(provider: countryProvider)
        services = SelectedServices(transport: serviceTransport,settings:savedSettings)
        session = MonitorSession(runner: runner, interval: .seconds(30)); store = HistoryStore(url: file)
        super.init()
        serviceSettingsUnavailable=settingsLoadFailed
        if !isFixture, let handle = try? FileHandle(forReadingFrom: preferenceFile) {
            defer { try? handle.close() }
            let data = (try? handle.read(upToCount: 65)) ?? Data()
            if data.count <= 64 {
            alertsEnabled = (try? JSONDecoder().decode(Bool.self, from: data)) ?? false
            }
        }
        services.onSettingsChange = { [weak self] value in
            guard let self else { return }
            do { try self.serviceSettingsStore.save(value); self.serviceSettingsUnavailable=false }
            catch { self.serviceSettingsUnavailable=true }
        }
        services.onChange = { [weak self] in self?.objectWillChange.send() }
        services.onObservation = { [weak self] target, value in self?.save(value,target:target) }
        country.onChange = { [weak self] in self?.objectWillChange.send() }
        UNUserNotificationCenter.current().delegate = self
        delivery.onSubmitted = { [weak self] event in self?.alertPolicy.confirmSubmission(event) }
        delivery.onFailure = { [weak self] in self?.notificationFailure = true; self?.alertPolicy.reset() }
        session.onChange = { [weak self] in self?.objectWillChange.send() }
        session.onObservation = { [weak self] value in self?.save(value); self?.considerAlert(value) }
        path.pathUpdateHandler = { [weak self] value in
            let unavailable = value.status == .unsatisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                // Every delivered path update may change routing, even with identical interface names.
                self.delivery.invalidate()
                self.session.networkChanged(unavailable: unavailable)
                self.country.networkChanged(unavailable: unavailable)
                self.services.networkChanged(unavailable: unavailable)
            }
        }
        if !isFixture { path.start(queue: DispatchQueue(label: "ru.tgwatch.ios.path")) }
        Task {
            #if DEBUG
            if isFixture, ProcessInfo.processInfo.arguments.contains("history") {
                let base = Date().addingTimeInterval(-180)
                for (index,status) in [ProbeStatus.down,.down,.ok,.ok,.noNetwork].enumerated() {
                    try? await store.append(HistoryEntry(epoch:1,observedAt:base.addingTimeInterval(Double(index)*30),status:status,sessionID:historySessionID))
                }
            }
            #endif
            await reload()
        }
    }
    func resetServiceSettings() {
        do { try serviceSettingsStore.reset(); services.resetSettings(); serviceSettingsUnavailable=false }
        catch { services.setAutomatic(false); serviceSettingsUnavailable=true }
    }
    func setActive(_ active: Bool) {
        session.setActive(active); country.setActive(active); services.setActive(active)
        if !active {
            alertPolicy.reset()
            delivery.invalidate()
        } else {
            if alertsEnabled { session.setMonitoring(true) }
            Task { await refreshNotificationPermission() }
        }
    }
    func setAlerts(_ enabled: Bool) {
        alertsEnabled = enabled; alertPolicy.reset(); notificationFailure = false
        if enabled { session.setMonitoring(true) }
        else { delivery.invalidate() }
        guard !fixtureMode else { return }
        do {
            try FileManager.default.createDirectory(at: preferenceFile.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(enabled).write(to: preferenceFile, options: .atomic)
        } catch { notificationFailure = true }
    }
    func requestAlerts() {
        guard !fixtureMode else { return }
        Task {
            do {
                let allowed = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
                await refreshNotificationPermission()
                if allowed { setAlerts(true) }
            } catch { notificationFailure = true }
        }
    }
    private func refreshNotificationPermission() async {
        guard !fixtureMode else { notificationPermission = "permission.fixture"; return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let allowed = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
        if notificationsAllowed != allowed { alertPolicy.reset(); delivery.invalidate() }
        notificationsAllowed = allowed
        switch settings.authorizationStatus {
        case .authorized: notificationPermission = "permission.allowed"
        case .provisional: notificationPermission = "permission.quiet"
        case .denied: notificationPermission = "permission.denied"
        default: notificationPermission = "permission.unknown"
        }
    }
    private func considerAlert(_ snapshot: ProbeSnapshot) {
        guard alertsEnabled, notificationsAllowed, !fixtureMode, session.isFresh() else { alertPolicy.reset(); return }
        guard let event = alertPolicy.observe(snapshot.status, at: snapshot.finishedAt, epoch: snapshot.epoch) else { return }
        delivery.submit(event: event, observedAt: snapshot.finishedAt) { [weak self] in
            guard let self else { return false }
            return self.alertsEnabled && self.notificationsAllowed && self.session.active && self.session.isFresh()
                && self.session.snapshot?.epoch == snapshot.epoch && self.session.snapshot?.finishedAt == snapshot.finishedAt
        }
    }
    func clockChanged() {
        delivery.invalidate(); alertPolicy.reset(); session.clockChanged(); services.clockChanged()
        country.networkChanged(unavailable: country.unavailable)
    }
    func setMonitoring(_ enabled: Bool) {
        if !enabled { setAlerts(false) }
        session.setMonitoring(enabled)
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let id = notification.request.identifier
        Task { @MainActor [weak self] in
            completionHandler(self?.delivery.canPresent(id: id) == true ? [.banner, .list, .sound] : [])
        }
    }
    private func save(_ snapshot: ProbeSnapshot, target: ServiceTarget = .telegram) {
        let sessionID = historySessionID
        let previous = writeTask
        writeTask = Task { [weak self, store] in
            await previous?.value
            do { try await store.append(HistoryEntry(epoch: snapshot.epoch, observedAt: snapshot.finishedAt, status: snapshot.status, sessionID: sessionID, target: target == .telegram ? nil : target, service: snapshot.evidence.service)) }
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
private struct FixtureServiceTransport: ServiceTransport {
    let mode: String
    func probe(target: ServiceTarget, deadline: ContinuousClock.Instant) async throws -> ServiceEvidence {
        try await Task.sleep(for: mode == "services-slow" && target == .matrix ? .seconds(300) : .milliseconds(50))
        return .init(target:target,outcome:target == .matrix ? .matrixVersions : .httpResponse,httpCode:target == .matrix ? 200 : 401)
    }
    func control(deadline: ContinuousClock.Instant) async throws -> Bool { true }
}
private struct CountryFixtureRunner: CountryLookup {
    let mode: String
    func country() async throws -> String? {
        try await Task.sleep(for: .milliseconds(25))
        return mode == "offline" ? nil : "DE"
    }
}
private struct FixtureRunner: ProbeRunner {
    let mode: String
    func check(epoch: UInt64) async throws -> ProbeSnapshot {
        try await Task.sleep(for: mode == "slow" ? .seconds(300) : .milliseconds(50))
        let evidence = mode == "offline" ? ProbeEvidence(pathUnavailable: true) : ProbeEvidence(mtprotoConfirmed: true, webConfirmed: true, primaryMeasurementsComplete: true)
        return .init(epoch: epoch, startedAt: Date(), finishedAt: Date(), evidence: evidence)
    }
}
#endif

@MainActor private final class AppleAlertScheduler: AlertScheduler {
    func add(id: String, event: ConnectionAlert, observedAt: Date) async throws {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("alert." + event.rawValue, comment: "")
        content.body = NSLocalizedString("alert.observed", comment: "") + " " + observedAt.formatted(date: .omitted, time: .shortened)
        content.sound = .default
        try await UNUserNotificationCenter.current().add(.init(identifier: id, content: content, trigger: nil))
    }
    func remove(id: String) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
    }
}
