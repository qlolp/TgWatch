import SwiftUI
import TgWatchCore
struct MonitorView: View {
    @ObservedObject var controller: MonitorController
    @Environment(\.locale) private var locale
    private var tint: Color {
        guard controller.session.isFresh() else { return .secondary }
        switch controller.session.snapshot?.status {
        case .ok: return .green
        case .partial: return .orange
        case .down, .noNetwork: return .red
        default: return .secondary
        }
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if controller.fixtureMode { Text("TEST FIXTURE — no live measurements").font(.caption).foregroundStyle(.orange) }
                    VStack(alignment: .leading, spacing: 14) {
                        Label("connectionOverview", systemImage: "waveform.path.ecg").font(.headline)
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            let fresh = controller.session.isFresh()
                            HStack(alignment: .top, spacing: 14) {
                                Image(systemName: fresh ? "antenna.radiowaves.left.and.right" : "questionmark.circle")
                                    .font(.largeTitle).foregroundStyle(tint).accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(LocalizedStringKey(fresh ? "status.\(controller.session.snapshot!.status.rawValue)" : "noFreshData"))
                                        .font(.title2.bold()).accessibilityIdentifier("status")
                                    if let snapshot = controller.session.snapshot {
                                        Text(snapshot.finishedAt, style: .time).font(.subheadline).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                        if controller.session.checking {
                            ProgressView("checking")
                            Button("cancel", action: controller.session.cancel).buttonStyle(.bordered).accessibilityIdentifier("cancelCheck")
                        } else {
                            Button(action: controller.session.checkNow) { Label("checkNow", systemImage: "arrow.clockwise").frame(maxWidth: .infinity) }
                                .buttonStyle(.borderedProminent).controlSize(.large).accessibilityIdentifier("checkNow")
                        }
                        Toggle("monitorWhileOpen", isOn: Binding(get: { controller.session.monitoring }, set: controller.setMonitoring))
                        Text("foregroundExplanation").font(.footnote).foregroundStyle(.secondary).accessibilityIdentifier("foregroundExplanation")
                    }.card()
                    countryCard
                    NavigationLink { ServiceView(controller:controller) } label: {
                        Label("serviceChecks", systemImage:"network").frame(maxWidth:.infinity,alignment:.leading)
                    }.accessibilityIdentifier("serviceChecks").card()
                    VStack(alignment: .leading, spacing: 12) {
                        Label("connectionAlerts", systemImage: "bell.badge").font(.headline)
                        Text(LocalizedStringKey(controller.notificationPermission)).font(.subheadline).accessibilityIdentifier("notificationPermission")
                        Toggle("alertsWhileOpen", isOn: Binding(get: { controller.alertsEnabled }, set: controller.setAlerts))
                        Button("allowNotifications", action: controller.requestAlerts).disabled(controller.fixtureMode)
                        Text("alertLimits").font(.footnote).foregroundStyle(.secondary)
                        if controller.notificationFailure { Text("notificationFailure").foregroundStyle(.red) }
                    }.card()
                    if let evidence = controller.session.snapshot?.evidence {
                        VStack(alignment: .leading, spacing: 12) {
                            Label("evidence", systemImage: "list.bullet.clipboard").font(.headline)
                            evidenceRow("MTProto", confirmed: evidence.mtprotoConfirmed)
                            evidenceRow("telegramWeb", confirmed: evidence.webConfirmed)
                            evidenceRow("controlEndpoint", confirmed: evidence.controlConfirmed)
                            Text(evidence.primaryMeasurementsComplete ? "measurementsComplete" : "measurementsIncomplete").font(.footnote)
                            Text("reachabilityOnly").font(.footnote).foregroundStyle(.secondary)
                        }.card()
                    }
                    if controller.historyUnavailable { Text("historyUnavailable").foregroundStyle(.red) }
                }.padding()
            }.background(Color(uiColor: .systemGroupedBackground))
                .navigationTitle("TgWatch")
                .toolbar { ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink("history") { HistoryView(controller: controller) }.accessibilityIdentifier("history")
                } }
        }.tint(.blue)
    }
    private var countryCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("egressCountry", systemImage: "globe.europe.africa").font(.headline)
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                if let iso = controller.country.iso {
                    HStack(alignment: .top) {
                        Text(EgressCountry.flag(iso)).font(.largeTitle).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(locale.localizedString(forRegionCode: iso) ?? iso).font(.title3.bold()).accessibilityIdentifier("countryName")
                            Text(controller.country.isFresh() ? "countryVerified" : "countryStale").font(.caption).foregroundStyle(.secondary)
                            if let at = controller.country.checkedAt { Text(at, style: .time).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                } else {
                    Text(controller.country.checking ? "countryChecking" : "countryUnknown")
                        .foregroundStyle(.secondary).accessibilityIdentifier("countryUnknown")
                }
            }
            Button("refreshCountry", action: controller.country.refresh).disabled(controller.country.checking).accessibilityIdentifier("refreshCountry")
            Text("countryPrivacy").font(.footnote).foregroundStyle(.secondary)
        }.card()
    }
    private func evidenceRow(_ name: LocalizedStringKey, confirmed: Bool) -> some View {
        HStack(alignment: .top) {
            Image(systemName: confirmed ? "checkmark.circle.fill" : "minus.circle").foregroundStyle(confirmed ? Color.green : Color.secondary).accessibilityHidden(true)
            VStack(alignment: .leading) { Text(name); Text(confirmed ? "confirmed" : "unconfirmed").font(.caption).foregroundStyle(.secondary) }
        }
    }
}
private extension View {
    func card() -> some View {
        self.frame(maxWidth: .infinity, alignment: .leading).padding(18)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
    }
}
