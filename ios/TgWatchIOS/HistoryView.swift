import SwiftUI
import UniformTypeIdentifiers
import TgWatchCore
struct HistoryView: View {
    @ObservedObject var controller: MonitorController
    @State private var confirming = false
    @State private var days = 1
    @State private var target: ServiceTarget
    @State private var exporting = false
    @State private var exportFailed = false
    @State private var document = HistoryCSVDocument()
    init(controller: MonitorController, target: ServiceTarget = .telegram) {
        self.controller=controller; _target=State(initialValue:target)
    }
    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let report = HistoryReport(entries: controller.history, from: context.date.addingTimeInterval(-Double(days)*86400), until: context.date, target:target)
            List {
                Section {
                    Picker("historyTarget", selection:$target) {
                        ForEach(ServiceTarget.allCases,id:\.rawValue) { Text($0.name).tag($0) }
                    }.accessibilityIdentifier("historyTarget")
                    Picker("historyWindow", selection: $days) {
                        Text("history24h").tag(1); Text("history7d").tag(7)
                    }.pickerStyle(.segmented).accessibilityIdentifier("historyWindow")
                    NavigationLink { IncidentHistoryView(report: report) } label: {
                        Label("incidents", systemImage: "exclamationmark.bubble")
                    }.accessibilityIdentifier("incidents")
                    Text("historyExplanation").font(.footnote)
                }
                if controller.historyUnavailable { Text("historyUnavailable").foregroundStyle(.red) }
                Section("sampledCoverage") {
                    Text((report.coveredSeconds / (Double(days)*86400)).formatted(.percent.precision(.fractionLength(1))))
                        .font(.title2.bold()).accessibilityIdentifier("coverageValue")
                    Text("coverageExplanation").font(.footnote)
                    ForEach([ProbeStatus.ok,.partial,.down,.noNetwork,.unknown], id: \.rawValue) { status in
                        LabeledContent { Text(durationText(report.seconds(status))).monospacedDigit() }
                            label: { Text(LocalizedStringKey(report.target.statusKey(status))) }
                    }
                }
                Section("observations") {
                    Text("observationLimit \(report.observations.count)").font(.footnote)
                    if report.observations.isEmpty { Text("historyEmpty").accessibilityIdentifier("historyEmpty") }
                    ForEach(report.observations.suffix(200).reversed()) { entry in
                        VStack(alignment: .leading) {
                            Text(LocalizedStringKey(report.target.statusKey(entry.status)))
                            if let service=entry.service { Text(LocalizedStringKey("serviceOutcome."+service.outcome.rawValue)).font(.caption) }
                            Text(entry.observedAt.formatted(date: .abbreviated, time: .standard)).font(.caption)
                        }.accessibilityElement(children: .combine).accessibilityIdentifier("historyEntry")
                    }
                }
                if exportFailed { Text("historyExportFailed").foregroundStyle(.red) }
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { document = HistoryCSVDocument(text: report.csv); exporting = true } label: {
                        Label("exportHistory", systemImage: "square.and.arrow.up")
                    }.disabled(controller.historyUnavailable || report.observations.isEmpty).accessibilityIdentifier("exportHistory")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) { confirming = true } label: { Label("clearHistory", systemImage: "trash") }
                        .accessibilityIdentifier("clearHistory")
                }
            }
        }.navigationTitle("history")
            .alert("clearHistoryQuestion", isPresented: $confirming) {
                Button("clearHistory", role: .destructive, action: controller.clearHistory)
                Button("cancel", role: .cancel) {}
            }
            .fileExporter(isPresented: $exporting, document: document, contentType: .commaSeparatedText,
                          defaultFilename: "tgwatch-\(target.rawValue)-history") { result in
                if case .failure = result { exportFailed = true } else { exportFailed = false }
            }
    }
}
private struct IncidentHistoryView: View {
    let report: HistoryReport
    var body: some View {
        List {
            Text(report.target.name).font(.headline)
            Text(LocalizedStringKey(report.target == .telegram ? "incidentExplanation" : "serviceIncidentExplanation")).font(.footnote)
            if report.incidents.isEmpty { Text("noIncidents").accessibilityIdentifier("noIncidents") }
            ForEach(report.incidents.reversed()) { incident in
                VStack(alignment: .leading, spacing: 8) {
                    Text(LocalizedStringKey(report.target.statusKey(incident.status))).font(.headline)
                    Text(LocalizedStringKey(report.target == .telegram ? (incident.confirmed ? "incidentConfirmed" : "incidentUnconfirmed") : (incident.confirmed ? "serviceIncidentConfirmed" : "serviceIncidentUnconfirmed")))
                    LabeledContent("firstObserved", value: incident.startedAt.formatted(date: .abbreviated, time: .standard))
                    LabeledContent(LocalizedStringKey(report.target == .telegram ? "lastFailure" : "serviceLastFailure"), value: incident.lastObservedAt.formatted(date: .abbreviated, time: .standard))
                    if let recovery = incident.recoveredAt {
                        Text(LocalizedStringKey(report.target == .telegram ? "incidentRecovered" : "serviceIncidentRecovered")).foregroundStyle(.green)
                        Text(recovery.formatted(date: .abbreviated, time: .standard))
                    } else { Text("recoveryNotObserved").foregroundStyle(.secondary) }
                }.accessibilityIdentifier("incidentEntry")
            }
        }.navigationTitle("incidents")
    }
}
private func durationText(_ seconds: TimeInterval) -> String {
    let value = max(0,Int(seconds))
    return String(format:"%02d:%02d:%02d",value/3600,value/60%60,value%60)
}
private struct HistoryCSVDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    var text = ""
    init(text: String = "") { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, let text = String(data:data,encoding:.utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = text
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}
