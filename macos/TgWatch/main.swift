import AppKit
import ServiceManagement

@MainActor final class RegionalMenuApp: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    private var model = RegionalMenuModel()
    private var engine: RegionalEngineProcess?
    private var notifications: RegionalNativeNotifications?
    private var services = Set(RegionalMenuService.allCases)
    private var monitoring = false
    private var generation: UInt64 = 0
    private var timer: Timer?
    private var menuOpen = false
    private var terminating = false
    private var statusText = tr("Monitoring stopped")
    private var loginStatus: SMAppService.Status = .notRegistered
    private let defaults = UserDefaults.standard
    private let smoke = CommandLine.arguments.contains("--smoke-test")
    private let dateFormatter: DateFormatter = {
        let value = DateFormatter(); value.dateStyle = .short; value.timeStyle = .medium; return value
    }()

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.accessory)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.title = "TgWatch"
        statusItem.button?.image = NSImage(systemSymbolName: "network", accessibilityDescription: "TgWatch")
        statusItem.button?.imagePosition = .imageLeading
        if smoke { smokeTest(); return }
        if let saved = defaults.array(forKey: "profiles") as? [String] {
            services = Set(saved.compactMap(RegionalMenuService.init(rawValue:)))
        }
        guard let script = Bundle.main.url(forResource: "regional-services", withExtension: "py") else {
            statusText = tr("Bundled engine missing. Rebuild the app."); rebuildMenu(); return
        }
        engine = RegionalEngineProcess(script: script, onLine: { [weak self] data, generation in
            DispatchQueue.main.async { self?.receive(data, generation: generation) }
        }, onState: { [weak self] state, generation in
            DispatchQueue.main.async { self?.engineState(state, generation: generation) }
        })
        notifications = RegionalNativeNotifications(defaults: defaults, isCurrent: { [weak self] reading, generation in
            guard let self, self.monitoring, self.generation == generation,
                let latest = self.current(reading.service) else { return false }
            return latest.contextID == reading.contextID && latest.status == .regionRestricted && latest.regionConfirmed
        })
        notifications?.onChange = { [weak self] in self?.rebuildMenu() }
        notifications?.onDenied = { [weak self] in
            self?.message(tr("Notifications blocked"), tr("Allow TgWatch notifications in macOS System Settings, then enable them here."))
        }
        notifications?.refresh()
        loginStatus = SMAppService.mainApp.status
        monitoring = defaults.bool(forKey: "monitoring.enabled") && !services.isEmpty
        if monitoring { restart() } else { rebuildMenu() }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            DispatchQueue.main.async { self?.rebuildMenu() }
        }
    }
    private func current(_ service: RegionalMenuService) -> RegionalMenuReading? {
        model.current(service, now: Date().timeIntervalSince1970 * 1_000, uptime: ProcessInfo.processInfo.systemUptime)
    }
    private func receive(_ data: Data, generation: UInt64) {
        guard monitoring, generation == self.generation,
            model.ingest(data, generation: generation, now: Date().timeIntervalSince1970 * 1_000,
                         uptime: ProcessInfo.processInfo.systemUptime) else { return }
        for service in services {
            if let reading = current(service) { notifications?.consider(reading, generation: generation) }
        }
        rebuildMenu()
    }
    private func engineState(_ state: RegionalEngineState, generation: UInt64) {
        guard generation == self.generation else { return }
        if terminating {
            if case .stopped = state { NSApplication.shared.reply(toApplicationShouldTerminate: true) }
            return
        }
        switch state {
        case .starting: statusText = tr("Finding Python / starting checks…")
        case .running: statusText = tr("Monitoring public websites")
        case .stopped: statusText = tr("Monitoring stopped")
        case .missingRuntime, .failed:
            monitoring = false
            defaults.set(false, forKey: "monitoring.enabled")
            model.replace(generation: generation, services: [])
            statusText = state == .missingRuntime ? tr("Python 3.10+ is required") : tr("Checks stopped; retry or open Help")
            if case .missingRuntime = state {
                message(tr("Python 3.10+ is required"),
                    tr("Install a compatible Python from python.org or Homebrew, then choose Start monitoring. The app does not install runtimes or developer tools automatically."),
                    openPython: true)
            }
        }
        rebuildMenu()
    }
    private func restart() {
        generation &+= 1
        model.replace(generation: generation, services: monitoring ? services : [])
        engine?.replace(services: monitoring ? services : [], generation: generation)
        statusText = monitoring ? tr("Starting fresh checks…") : tr("Monitoring stopped")
        rebuildMenu()
    }
    private func item(_ menu: NSMenu, _ title: String, action: Selector? = nil,
                      represented: String? = nil, checked: Bool = false, enabled: Bool = true) {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self; item.representedObject = represented
        item.isEnabled = enabled; item.state = checked ? .on : .off
        menu.addItem(item)
    }
    private func rebuildMenu() {
        guard !menuOpen, statusItem != nil else { return }
        let menu = NSMenu(); menu.autoenablesItems = false; menu.delegate = self
        item(menu, statusText, enabled: false)
        item(menu, tr("Public sites · account features unverified"), enabled: false)
        menu.addItem(.separator())
        for service in RegionalMenuService.allCases { profile(service, menu: menu) }
        menu.addItem(.separator())
        item(menu, monitoring ? tr("Stop monitoring") : tr("Start monitoring"), action: #selector(toggleMonitoring),
             checked: monitoring, enabled: monitoring || !services.isEmpty)
        item(menu, tr("Check now"), action: #selector(checkNow), enabled: monitoring)
        let notificationTitle = notifications?.optedIn == true && notifications?.authorized != true ?
            tr("Notifications blocked by macOS") : tr("Notifications")
        item(menu, notificationTitle, action: #selector(toggleNotifications),
             checked: notifications?.optedIn == true && notifications?.authorized == true)
        item(menu, loginStatus == .requiresApproval ? tr("Login approval required…") : tr("Launch at login"),
             action: #selector(toggleLogin), checked: loginStatus == .enabled)
        item(menu, tr("System Settings"), action: #selector(openSettings))
        item(menu, tr("Help"), action: #selector(openHelp))
        menu.addItem(.separator())
        item(menu, tr("Quit TgWatch"), action: #selector(quit))
        statusItem.menu = menu
    }
    private func profile(_ service: RegionalMenuService, menu: NSMenu) {
        let reading = current(service)
        let status = monitoring && services.contains(service) ? reading.map {
            tr($0.status.title) + ($0.regionConfirmed ? " · " + tr("confirmed") : "")
        } ?? tr("Unverified") : tr("off")
        let parent = NSMenuItem(title: service.title + " — " + status, action: nil, keyEquivalent: "")
        let detail = NSMenu(); detail.autoenablesItems = false
        item(detail, tr("Include when monitoring"), action: #selector(toggleProfile), represented: service.rawValue,
             checked: services.contains(service))
        if let reading {
            item(detail, tr("Checked: ") + dateFormatter.string(from: Date(timeIntervalSince1970: reading.checkedAt / 1_000)),
                 enabled: false)
            if let code = reading.httpCode { item(detail, "HTTP \(code)", enabled: false) }
            if let country = reading.country {
                let flag = String(String.UnicodeScalarView(country.unicodeScalars.compactMap { UnicodeScalar(127_397 + $0.value) }))
                item(detail, flag + " " + country + " · " + tr("Cloudflare context only"), enabled: false)
            } else { item(detail, tr("Country unverified"), enabled: false) }
        }
        item(detail, tr("Open in browser"), action: #selector(openService), represented: service.rawValue)
        parent.submenu = detail
        menu.addItem(parent)
    }
    func menuWillOpen(_ menu: NSMenu) {
        menuOpen = true
        if !smoke { loginStatus = SMAppService.mainApp.status; notifications?.refresh() }
    }
    func menuDidClose(_ menu: NSMenu) { menuOpen = false; rebuildMenu() }
    @objc private func toggleMonitoring() {
        monitoring.toggle()
        defaults.set(monitoring, forKey: "monitoring.enabled")
        restart()
    }
    @objc private func checkNow() { if monitoring { restart() } }
    @objc private func toggleProfile(_ sender: NSMenuItem) {
        guard let raw = sender.representedObject as? String, let service = RegionalMenuService(rawValue: raw) else { return }
        if services.contains(service) { services.remove(service) } else { services.insert(service) }
        defaults.set(services.map(\.rawValue).sorted(), forKey: "profiles")
        if services.isEmpty { monitoring = false; defaults.set(false, forKey: "monitoring.enabled") }
        restart()
    }
    @objc private func openService(_ sender: NSMenuItem) {
        guard let raw = sender.representedObject as? String, let service = RegionalMenuService(rawValue: raw) else { return }
        NSWorkspace.shared.open(service.address)
    }
    @objc private func toggleNotifications() { notifications?.toggle() }
    @objc private func toggleLogin() {
        do {
            switch SMAppService.mainApp.status {
            case .enabled: try SMAppService.mainApp.unregister()
            case .requiresApproval: SMAppService.openSystemSettingsLoginItems()
            default: try SMAppService.mainApp.register()
            }
            loginStatus = SMAppService.mainApp.status
        } catch {
            message(tr("Launch at login unavailable"), tr("Move TgWatch.app to Applications, then retry. macOS may require approval in Login Items."))
            loginStatus = SMAppService.mainApp.status
        }
        rebuildMenu()
    }
    @objc private func openSettings() { NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/System Settings.app")) }
    @objc private func openHelp() { NSWorkspace.shared.open(URL(string: "https://github.com/qlolp/TgWatch/blob/main/docs/macos.md")!) }
    @objc private func quit() { NSApplication.shared.terminate(nil) }
    private func message(_ title: String, _ detail: String, openPython: Bool = false) {
        let alert = NSAlert(); alert.messageText = title; alert.informativeText = detail
        alert.addButton(withTitle: tr("OK"))
        if openPython { alert.addButton(withTitle: tr("Python download page")) }
        if alert.runModal() == .alertSecondButtonReturn {
            NSWorkspace.shared.open(URL(string: "https://www.python.org/downloads/macos/")!)
        }
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard engine != nil else { return .terminateNow }
        terminating = true; monitoring = false; timer?.invalidate(); generation &+= 1
        model.replace(generation: generation, services: [])
        engine?.replace(services: [], generation: generation)
        return .terminateLater
    }
    private func smokeTest() {
        rebuildMenu()
        let profiles = statusItem.menu!.items.filter { $0.submenu != nil }
        precondition(profiles.count == 5 && profiles.allSatisfy { $0.submenu!.items.count >= 2 })
        let result: [String: Any] = ["profiles": profiles.count, "native_menu": true, "monitoring": monitoring,
                                   "notification_request": false, "login_registration": false]
        let data = try! JSONSerialization.data(withJSONObject: result, options: .sortedKeys)
        if let index = CommandLine.arguments.firstIndex(of: "--smoke-report"), index + 1 < CommandLine.arguments.count {
            try! data.write(to: URL(fileURLWithPath: CommandLine.arguments[index + 1]), options: .atomic)
        }
        FileHandle.standardOutput.write(data + Data([10]))
        NSApplication.shared.terminate(nil)
    }
}

@main struct TgWatchEntry {
    @MainActor static func main() {
        let application = NSApplication.shared
        let delegate = RegionalMenuApp()
        application.delegate = delegate
        withExtendedLifetime(delegate) { application.run() }
    }
}
