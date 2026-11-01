import SwiftUI
import TgWatchCore

struct MonitorView: View {
    @ObservedObject var controller: MonitorController

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if controller.fixtureMode {
                        Label("TEST FIXTURE — no live measurements", systemImage: "testtube.2")
                            .font(.caption).foregroundStyle(.orange)
                    }
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        let snapshot = controller.session.snapshot
                        let fresh = controller.session.isFresh()
                        StatusCard(
                            snapshot: snapshot,
                            fresh: fresh,
                            checking: controller.session.checking,
                            noNetwork: controller.session.pathUnavailable,
                            check: controller.session.checkNow,
                            cancel: controller.session.cancel
                        )
                    }
                    MonitoringCard(
                        profile: Binding(get: { controller.profile }, set: controller.setProfile),
                        preferencesUnavailable: controller.preferencesUnavailable,
                        monitoring: Binding(
                            get: { controller.session.monitoring },
                            set: controller.session.setMonitoring
                        )
                    )
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        DiagnosticsCard(
                            evidence: controller.session.snapshot?.evidence,
                            fresh: controller.session.isFresh()
                        )
                    }
                    if controller.historyUnavailable {
                        Label("historyUnavailable", systemImage: "exclamationmark.triangle")
                            .font(.footnote).foregroundStyle(.red)
                    }
                }
                .padding(20)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("TgWatch")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        HistoryReportView(controller: controller)
                    } label: {
                        Label("historyReport", systemImage: "chart.bar.xaxis")
                    }
                    .accessibilityIdentifier("historyReport")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        HistoryView(controller: controller)
                    } label: {
                        Label("history", systemImage: "clock.arrow.circlepath")
                    }
                    .accessibilityIdentifier("history")
                }
            }
        }
        .tint(.blue)
    }
}

private struct StatusCard: View {
    let snapshot: ProbeSnapshot?
    let fresh: Bool
    let checking: Bool
    let noNetwork: Bool
    let check: () -> Void
    let cancel: () -> Void

    private var status: ProbeStatus? {
        noNetwork ? .noNetwork : (fresh ? snapshot?.status : nil)
    }

    var body: some View {
        VStack(spacing: 20) {
            Text("connectionOverview")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.secondary)
            ZStack {
                Circle().fill((status?.displayColor ?? .blue).opacity(0.10))
                Circle().stroke((status?.displayColor ?? .blue).opacity(0.18), lineWidth: 1)
                    .padding(8)
                if checking {
                    ProgressView().controlSize(.large).tint(.blue)
                } else {
                    Image(systemName: status?.displaySymbol ?? "paperplane.fill")
                        .font(.system(size: 36, weight: .medium))
                        .foregroundStyle(status?.displayColor ?? .blue)
                }
            }
            .frame(width: 96, height: 96)
            .accessibilityHidden(true)
            VStack(spacing: 8) {
                Text(checking ? "checking" : status?.displayTitle ?? "noFreshData")
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("status")
                if let snapshot {
                    Text(fresh ? "lastCheck" : "lastCheckStale")
                        .font(.caption).foregroundStyle(.secondary)
                    Text(snapshot.finishedAt, format: .dateTime.hour().minute().second())
                        .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                } else {
                    Text(noNetwork ? "connectToInternet" : "startCheckHint")
                        .font(.subheadline).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            Button(action: checking ? cancel : check) {
                Label(checking ? "cancel" : "checkNow", systemImage: checking ? "xmark" : "arrow.clockwise")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 30)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 16))
            .accessibilityIdentifier(checking ? "cancelCheck" : "checkNow")
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        .background {
            RoundedRectangle(cornerRadius: 28)
                .fill(Color(uiColor: .secondarySystemGroupedBackground))
                .overlay(alignment: .top) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(status?.displayColor ?? .blue)
                        .frame(width: 40, height: 4).padding(.top, 10)
                }
        }
    }
}

private struct MonitoringCard: View {
    @Binding var profile: CheckProfile
    let preferencesUnavailable: Bool
    @Binding var monitoring: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: $monitoring) {
                Label("autoMonitoring", systemImage: "waveform.path")
                    .font(.headline)
            }
            .accessibilityLabel(Text("monitorWhileOpen"))
            Picker("checkFrequency", selection: $profile) {
                ForEach(CheckProfile.allCases) { value in
                    Text(value.title).tag(value)
                }
            }
            .pickerStyle(.menu)
            .accessibilityIdentifier("checkFrequency")
            if preferencesUnavailable {
                Label("preferencesUnavailable", systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(.red)
            }
            Divider()
            Label {
                Text("foregroundExplanation").accessibilityIdentifier("foregroundExplanation")
            } icon: {
                Image(systemName: "info.circle")
            }
            .font(.footnote).foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
    }
}

private struct DiagnosticsCard: View {
    let evidence: ProbeEvidence?
    let fresh: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("diagnostics").font(.title3.bold())
            if evidence != nil && !fresh {
                Label("lastCheckStale", systemImage: "clock.badge.exclamationmark")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            VStack(spacing: 0) {
                DiagnosticRow(title: "MTProto", subtitle: "mtprotoDescription", symbol: "paperplane",
                              confirmed: evidence?.mtprotoConfirmed, fresh: fresh)
                Divider().padding(.leading, 56)
                DiagnosticRow(title: "telegramWeb", subtitle: "webDescription", symbol: "globe",
                              confirmed: evidence?.webConfirmed, fresh: fresh)
                Divider().padding(.leading, 56)
                DiagnosticRow(title: "controlEndpoint", subtitle: "controlDescription", symbol: "network",
                              confirmed: evidence?.controlConfirmed, fresh: fresh)
            }
            .padding(.horizontal, 16)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
            if let evidence {
                Label(evidence.primaryMeasurementsComplete ? "measurementsComplete" : "measurementsIncomplete",
                      systemImage: evidence.primaryMeasurementsComplete ? "checkmark.circle" : "info.circle")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Text("reachabilityOnly")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct DiagnosticRow: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    let symbol: String
    let confirmed: Bool?
    let fresh: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(.blue)
                .frame(width: 40, height: 40)
                .background(.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.headline)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
                Label(confirmed.map { $0 ? LocalizedStringKey("confirmed") : "unconfirmed" } ?? "notChecked",
                      systemImage: confirmed.map { $0 ? "checkmark.circle.fill" : "questionmark.circle" } ?? "minus.circle")
                    .font(.subheadline)
                    .foregroundStyle(fresh && confirmed == true ? Color.green : Color.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 16)
    }
}

extension ProbeStatus {
    var displayTitle: LocalizedStringKey {
        switch self {
        case .ok: "status.ok"
        case .partial: "status.partial"
        case .down: "status.down"
        case .noNetwork: "status.noNetwork"
        case .unknown: "status.unknown"
        }
    }

    var displayColor: Color {
        switch self {
        case .ok: .green
        case .partial: .orange
        case .down: .red
        case .noNetwork: .secondary
        case .unknown: .secondary
        }
    }

    var displaySymbol: String {
        switch self {
        case .ok: "checkmark"
        case .partial: "exclamationmark"
        case .down: "xmark"
        case .noNetwork: "wifi.slash"
        case .unknown: "questionmark"
        }
    }
}
