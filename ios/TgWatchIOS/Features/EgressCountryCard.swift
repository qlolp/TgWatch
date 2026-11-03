import SwiftUI
import Observation
import Network
import TgWatchCore

@MainActor @Observable final class EgressController {
    private(set) var address: EgressAddress?
    private(set) var checkedAt: Date?
    private(set) var checking = false
    private(set) var failed = false
    private var active = false
    private var offline = false
    private var generation = 0
    private var lastAttempt: ContinuousClock.Instant?
    @ObservationIgnored private var task: Task<Void, Never>?
    private let path = NWPathMonitor()
    private let fixture: Bool
    @ObservationIgnored var onChange: (() -> Void)?

    init() {
        #if DEBUG
        fixture = ProcessInfo.processInfo.arguments.contains("--fixture")
        #else
        fixture = false
        #endif
        path.pathUpdateHandler = { [weak self] path in
            let offline = path.status == .unsatisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.offline = offline
                self.refresh()
            }
        }
        if !fixture { path.start(queue: DispatchQueue(label: "ru.tgwatch.egress")) }
    }

    func setActive(_ value: Bool) {
        guard value != active else { return }
        active = value
        refresh()
    }

    func refresh() {
        generation += 1
        let generation = generation
        task?.cancel()
        address = nil
        checkedAt = nil
        onChange?()
        checking = false
        failed = false
        guard active else { return }
        guard !offline else { failed = true; return }
        checking = true
        task = Task { [weak self] in
            guard let self else { return }
            do {
                // Debounce route changes and prevent repeated taps from flooding the endpoint.
                let earliest = self.lastAttempt?.advanced(by: .seconds(5)) ?? .now
                try await ContinuousClock().sleep(until: max(earliest, .now.advanced(by: .seconds(1))))
                try Task.checkCancellation()
                self.lastAttempt = .now
                let result: EgressAddress
                if self.fixture {
                    result = try EgressAddress.parse(Data("ip=203.0.113.42\nloc=DE\n".utf8))
                } else {
                    result = try await EgressLookup().fetch()
                }
                guard !Task.isCancelled, generation == self.generation, self.active else { return }
                self.address = result
                self.checkedAt = Date()
                self.onChange?()
                self.checking = false
            } catch {
                guard !Task.isCancelled, generation == self.generation, self.active else { return }
                self.failed = true
                self.checking = false
            }
            // Some VPNs change their exit without an NWPath update. Recheck while foregrounded.
            do { try await Task.sleep(for: .seconds(60)) } catch { return }
            guard !Task.isCancelled, generation == self.generation, self.active else { return }
            self.refresh()
        }
    }

    deinit { task?.cancel(); path.cancel() }
}

struct EgressCountryCard: View {
    @Environment(EgressController.self) private var controller
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("egressTitle", systemImage: "network").font(.headline)
                Spacer()
                Button(action: controller.refresh) {
                    Image(systemName: "arrow.clockwise").frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("egressRefresh"))
                .accessibilityIdentifier("egressRefresh")
                .disabled(controller.checking)
            }
            TimelineView(.periodic(from: .now, by: 1)) { context in
                if let address = controller.address, let date = controller.checkedAt,
                   (0...90).contains(context.date.timeIntervalSince(date)) {
                    HStack(alignment: .top, spacing: 12) {
                        Text(address.flag).font(.largeTitle).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(address.countryCode.flatMap { locale.localizedString(forRegionCode: $0) } ?? String(localized: "egressUnknownCountry"))
                                .font(.title3.bold())
                            Text(address.ip).font(.subheadline.monospaced()).textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                            Text(date, format: .dateTime.hour().minute().second()).font(.caption).foregroundStyle(.secondary)
                        }
                    }.accessibilityIdentifier("egressResult")
                } else if controller.checking {
                    ProgressView("egressChecking")
                } else {
                    Label(controller.failed ? "egressFailed" : "egressNotChecked", systemImage: "globe")
                        .foregroundStyle(.secondary)
                }
            }
            Text("egressHint").font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("egressCard")
    }
}
