import SwiftUI
import TgWatchCore
struct MonitorView: View {
    @ObservedObject var controller: MonitorController
    var body: some View {
        NavigationStack {
            List {
                if controller.fixtureMode { Text("TEST FIXTURE — no live measurements").foregroundStyle(.orange) }
                Section {
                    Text("foregroundExplanation").accessibilityIdentifier("foregroundExplanation")
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        let snapshot = controller.session.snapshot
                        let fresh = controller.session.isFresh()
                        Text(LocalizedStringKey(fresh ? "status.\(snapshot!.status.rawValue)" : "noFreshData"))
                            .font(.title2).accessibilityIdentifier("status")
                        if let snapshot {
                            Text(snapshot.finishedAt, style: .time)
                            Text(snapshot.finishedAt, style: .relative)
                        }
                    }
                    if controller.session.pathUnavailable { Text("status.noNetwork") }
                    if controller.session.checking {
                        ProgressView("checking")
                        Button("cancel", action: controller.session.cancel).accessibilityIdentifier("cancelCheck")
                    } else {
                        Button("checkNow", action: controller.session.checkNow).accessibilityIdentifier("checkNow")
                    }
                    Toggle("monitorWhileOpen", isOn: Binding(get: { controller.session.monitoring }, set: controller.session.setMonitoring))
                }
                if let evidence = controller.session.snapshot?.evidence {
                    Section("evidence") {
                        evidenceRow("MTProto", confirmed: evidence.mtprotoConfirmed)
                        evidenceRow("Telegram web", confirmed: evidence.webConfirmed)
                        evidenceRow("Control", confirmed: evidence.controlConfirmed)
                        Text(evidence.primaryMeasurementsComplete ? "measurementsComplete" : "measurementsIncomplete")
                        Text("reachabilityOnly").font(.footnote)
                    }
                }
                Section {
                    NavigationLink("history") { HistoryView(controller: controller) }.accessibilityIdentifier("history")
                    if controller.historyUnavailable { Text("historyUnavailable").foregroundStyle(.red) }
                }
            }
            .navigationTitle("TgWatch")
        }
    }
    private func evidenceRow(_ name: String, confirmed: Bool) -> some View {
        VStack(alignment: .leading) { Text(name); Text(confirmed ? "confirmed" : "unconfirmed").foregroundStyle(.secondary) }
    }
}
