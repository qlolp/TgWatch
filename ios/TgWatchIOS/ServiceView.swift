import SwiftUI
import TgWatchCore
struct ServiceView: View {
    @ObservedObject var controller: MonitorController
    var body: some View {
        List {
            Section {
                Picker("historyTarget", selection:Binding(get:{controller.services.target},set:controller.services.select)) {
                    Text("Matrix").tag(ServiceTarget.matrix); Text("WhatsApp Web").tag(ServiceTarget.whatsapp)
                }.pickerStyle(.segmented).accessibilityIdentifier("serviceTarget")
                Text(LocalizedStringKey(controller.services.target == .matrix ? "matrixMeaning" : "whatsappMeaning"))
                Text("servicePrivacy").font(.footnote).foregroundStyle(.secondary)
            }
            Section("serviceResult") {
                TimelineView(.periodic(from:.now,by:1)) { _ in
                    let session=controller.services.session
                    if session.isFresh(), let snapshot=session.snapshot {
                        Text(LocalizedStringKey(controller.services.target.statusKey(snapshot.status))).font(.headline).accessibilityIdentifier("serviceStatus")
                        Text(snapshot.finishedAt.formatted(date:.abbreviated,time:.standard)).font(.caption)
                        if let evidence=snapshot.evidence.service {
                            Text(LocalizedStringKey("serviceOutcome."+evidence.outcome.rawValue))
                            if let code=evidence.httpCode { Text("HTTP \(code)") }
                            if evidence.status != .ok {
                                Text(LocalizedStringKey(evidence.controlConfirmed ? "serviceControlConfirmed" : "serviceControlUnknown"))
                            }
                        }
                    } else { Text("noFreshData").accessibilityIdentifier("serviceStatus") }
                }
                if controller.services.session.checking {
                    ProgressView("checking")
                    Button("cancel",action:controller.services.session.cancel).accessibilityIdentifier("cancelService")
                } else { Button("checkNow",action:controller.services.session.checkNow).accessibilityIdentifier("checkService") }
            }
            Section("serviceSchedule") {
                Picker("serviceInterval",selection:Binding(get:{controller.services.intervalSeconds},set:controller.services.setInterval)) {
                    Text("service60").tag(60); Text("service120").tag(120); Text("service300").tag(300)
                }
                Toggle("serviceAuto",isOn:Binding(get:{controller.services.session.monitoring},set:controller.services.session.setMonitoring))
                Text("serviceLimits").font(.footnote).foregroundStyle(.secondary)
            }
        }.navigationTitle("serviceChecks")
            .toolbar { NavigationLink("history") { HistoryView(controller:controller,target:controller.services.target) }.accessibilityIdentifier("serviceHistory") }
            .onAppear { controller.services.setVisible(true) }
            .onDisappear { controller.services.setVisible(false) }
    }
}
