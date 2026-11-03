import SwiftUI
import Observation
import TgWatchCore

private struct VPNMeasurement {
    let snapshot: ProbeSnapshot
    let address: EgressAddress?
}

@MainActor @Observable private final class VPNComparisonController {
    var before: VPNMeasurement?
    var after: VPNMeasurement?
    var busy = false
    var failed = false
    private var generation = 0
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var networkSubscription: UUID?
    private let fixture: Bool

    init(fixture: Bool) {
        self.fixture = fixture
        if !fixture {
            networkSubscription = NetworkPathObserver.shared.hub.subscribe { [weak self] _ in self?.cancel() }
        }
    }
    func cancel() {
        generation += 1
        if busy { failed = true }
        busy = false
        task?.cancel()
    }
    func measure(afterVPN: Bool) {
        cancel()
        if afterVPN {
            guard let before, (0...1800).contains(Date().timeIntervalSince(before.snapshot.finishedAt)) else {
                self.before = nil; after = nil; failed = true; return
            }
        } else { before = nil; after = nil }
        let generation = generation
        busy = true; failed = false
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let result: VPNMeasurement
                if fixture {
                    try await Task.sleep(for: .milliseconds(400))
                    let snapshot = ProbeSnapshot(epoch: 0, startedAt: .now, finishedAt: .now,
                        evidence: .init(mtprotoConfirmed: afterVPN, webConfirmed: true), latencyMilliseconds: afterVPN ? 150 : 1200)
                    result = VPNMeasurement(snapshot: snapshot, address: try EgressAddress.parse(Data("ip=203.0.113.42\nloc=DE\n".utf8)))
                } else {
                    async let snapshot = ProbeEngine(transport: AppleProbeTransport()).check(epoch: 0)
                    async let address = try? EgressLookup().fetch()
                    result = try await VPNMeasurement(snapshot: snapshot, address: address)
                }
                guard !Task.isCancelled, self.generation == generation else { return }
                if afterVPN { after = result } else { before = result }
                busy = false
            } catch {
                guard !Task.isCancelled, self.generation == generation else { return }
                busy = false; failed = true
            }
        }
    }
    deinit {
        task?.cancel()
        if let networkSubscription {
            Task { @MainActor in NetworkPathObserver.shared.hub.unsubscribe(networkSubscription) }
        }
    }
}

struct VPNComparisonView: View {
    @State private var model: VPNComparisonController
    @Environment(\.scenePhase) private var phase
    @Environment(\.locale) private var locale
    init(fixture: Bool) { _model = State(initialValue: VPNComparisonController(fixture: fixture)) }

    var body: some View {
        List {
            Section { Text("vpnComparisonHint").foregroundStyle(.secondary) }
            Section("vpnBefore") {
                Button("vpnMeasureBefore") { model.measure(afterVPN: false) }.disabled(model.busy)
                if let sample = model.before { measurement(sample) }
            }
            Section("vpnAfter") {
                Button("vpnMeasureAfter") { model.measure(afterVPN: true) }.disabled(model.busy || model.before == nil)
                if let sample = model.after { measurement(sample) }
            }
            if model.busy { ProgressView("checking"); Button("cancel", action: model.cancel) }
            if model.failed { Text("vpnComparisonFailed").foregroundStyle(.orange) }
            if let before = model.before, let after = model.after {
                Section("vpnResult") {
                    Text(LocalizedStringKey("vpnOutcome." + VPNComparison.outcome(before: before.snapshot.status, after: after.snapshot.status).rawValue))
                    if before.snapshot.status == .ok, after.snapshot.status == .ok,
                       let first = before.snapshot.latencyMilliseconds, let second = after.snapshot.latencyMilliseconds {
                        LabeledContent("vpnLatencyChange", value: String(format: "%+.0f ms", second - first))
                    }
                    if let first = before.address, let second = after.address {
                        Text(first.ip == second.ip ? "vpnIPUnchanged" : "vpnIPChanged")
                    }
                    Text("vpnResultHint").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("vpnComparison")
        .onChange(of: phase) { _, value in if value != .active { model.cancel() } }
        .onDisappear { model.cancel() }
    }
    private func measurement(_ value: VPNMeasurement) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(value.snapshot.status.displayTitle, systemImage: value.snapshot.status.displaySymbol)
                .foregroundStyle(value.snapshot.status.displayColor)
            if let latency = value.snapshot.latencyMilliseconds { Text("\(latency.formatted(.number.precision(.fractionLength(0)))) ms") }
            if let address = value.address {
                Text(address.flag + " " + (address.countryCode.flatMap { locale.localizedString(forRegionCode: $0) } ?? String(localized: "egressUnknownCountry")))
                Text(address.ip).font(.caption.monospaced()).textSelection(.enabled)
            } else { Text("egressFailed").foregroundStyle(.secondary) }
            Text(value.snapshot.finishedAt, format: .dateTime.hour().minute().second()).font(.caption)
        }
    }
}
