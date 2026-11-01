import SwiftUI
import TgWatchCore
import Charts
import UniformTypeIdentifiers

struct HistoryView: View {
    @ObservedObject var controller: MonitorController
    @Environment(\.calendar) private var calendar
    @State private var confirming = false
    @State private var days: [HistoryDay] = []

    var body: some View {
        List {
            Section {
                NavigationLink {
                    HistoryReportView(controller: controller)
                } label: {
                    Label("historyReport", systemImage: "chart.bar.xaxis")
                }
                .accessibilityIdentifier("historyReport")
                NavigationLink("importCSV") { HistoryTransferView(controller: controller) }
                Label("historyExplanation", systemImage: "clock")
                    .font(.subheadline).foregroundStyle(.secondary)
                    .padding(.vertical, 8)
                if controller.historyUnavailable {
                    Label("historyUnavailable", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
            if controller.history.isEmpty {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "clock.badge.checkmark")
                            .font(.largeTitle).foregroundStyle(.blue)
                            .accessibilityHidden(true)
                        Text("historyEmpty").font(.headline)
                            .accessibilityIdentifier("historyEmpty")
                        Text("historyEmptyHint")
                            .font(.subheadline).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 28)
                }
            }
            ForEach(days) { day in
                Section {
                    ForEach(day.entries) { entry in
                        HistoryEntryRow(entry: entry)
                    }
                } header: {
                    Text(day.id, format: .dateTime.day().month(.wide).year())
                        .font(.subheadline.weight(.semibold))
                        .textCase(nil)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("history")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("clearHistory", systemImage: "trash", role: .destructive) {
                    confirming = true
                }
                .disabled(controller.history.isEmpty && !controller.historyUnavailable)
                .accessibilityIdentifier("clearHistory")
            }
        }
        .alert("clearHistoryQuestion", isPresented: $confirming) {
            Button("clearHistory", role: .destructive, action: controller.clearHistory)
            Button("cancel", role: .cancel) {}
        }
        .onChange(of: controller.history, initial: true) { _, entries in
            regroup(entries)
        }
        .onChange(of: calendar) { _, _ in
            regroup(controller.history)
        }
    }

    private func regroup(_ entries: [HistoryEntry]) {
        let grouped = Dictionary(grouping: entries) { calendar.startOfDay(for: $0.observedAt) }
        days = grouped.keys.sorted(by: >).map {
            HistoryDay(id: $0, entries: Array((grouped[$0] ?? []).reversed()))
        }
    }
}

private struct HistoryDay: Identifiable {
    let id: Date
    let entries: [HistoryEntry]
}

private struct HistoryEntryRow: View {
    let entry: HistoryEntry

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: entry.status.displaySymbol)
                .font(.subheadline.bold())
                .foregroundStyle(entry.status.displayColor)
                .frame(width: 32, height: 32)
                .background(entry.status.displayColor.opacity(0.12), in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(entry.status.displayTitle)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                Text(entry.observedAt, format: .dateTime.hour().minute().second())
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("historyEntry")
    }
}

struct HistoryReportView: View {
    @ObservedObject var controller: MonitorController
    @State private var rangeDays = 1
    @State private var result: HistoryReportResult?
    @State private var exporting = false
    @State private var exportFailed = false
    @State private var exportText = ""
    @State private var exportDays = 1
    @State private var reportGeneration: UInt64 = 0

    var body: some View {
        List {
            Section {
                Picker("reportPeriod", selection: $rangeDays) {
                    Text("lastDay").tag(1)
                    Text("lastWeek").tag(7)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("reportPeriod")
            }
            if let result {
                HistorySummarySection(result: result)
                Section("stateChart") {
                    Chart(result.slices) { slice in
                        BarMark(x: .value("Time", slice.date), y: .value("Minutes", slice.seconds / 60),
                                width: .ratio(0.9))
                            .foregroundStyle(slice.status.displayColor)
                    }
                    .chartXScale(domain: result.analytics.from...result.analytics.to)
                    .chartYAxis(.hidden)
                    .chartXAxis { AxisMarks(values: .automatic(desiredCount: 4)) }
                    .frame(height: 160)
                    .accessibilityLabel(Text("stateChart"))
                    Text("chartExplanation").font(.caption).foregroundStyle(.secondary)
                    ForEach(reportStatuses, id: \.rawValue) { status in
                        HStack {
                            Label(status.displayTitle, systemImage: status.displaySymbol)
                                .foregroundStyle(status.displayColor)
                            Spacer()
                            Text(Duration.seconds(result.analytics.duration(status)),
                                 format: .time(pattern: .hourMinuteSecond))
                                .monospacedDigit()
                        }
                        .font(.subheadline)
                    }
                }
                Section {
                    Button {
                        exportText = result.csv
                        exportDays = rangeDays
                        exporting = true
                    } label: {
                        Label("exportCSV", systemImage: "square.and.arrow.up")
                    }
                    .accessibilityIdentifier("exportCSV")
                    Text("csvExplanation").font(.caption).foregroundStyle(.secondary)
                    Text("summaryAsOf").font(.caption).foregroundStyle(.secondary)
                    Text(result.analytics.to, format: .dateTime.day().month().hour().minute())
                        .font(.caption).foregroundStyle(.secondary)
                }
            } else {
                ProgressView("preparingReport")
            }
        }
        .navigationTitle("historyReport")
        .task(id: HistoryReportRequest(entries: controller.history, days: rangeDays)) {
            await prepare()
        }
        .refreshable { await prepare() }
        .fileExporter(isPresented: $exporting,
                      document: HistoryCSVDocument(text: exportText),
                      contentType: .commaSeparatedText,
                      defaultFilename: "TgWatch-\(controller.definition.id)-\(exportDays)d") { outcome in
            if case .failure = outcome { exportFailed = true }
        }
        .alert("exportFailed", isPresented: $exportFailed) {
            Button("dismiss", role: .cancel) {}
        }
    }

    private func prepare() async {
        reportGeneration &+= 1
        let generation = reportGeneration
        result = nil
        let entries = controller.history
        let days = rangeDays
        let targetID = controller.definition.id
        let prepared = await Task.detached(priority: .userInitiated) {
            let now = Date()
            let analytics = HistoryAnalytics(entries: entries, from: now.addingTimeInterval(-Double(days) * 86400), to: now)
            return HistoryReportResult(analytics: analytics, slices: analytics.chartSlices(count: days == 1 ? 24 : 28),
                                       csv: HistoryCSV.export(analytics, targetID: targetID, entries: entries))
        }.value
        guard !Task.isCancelled, generation == reportGeneration else { return }
        result = prepared
    }
}

private let reportStatuses: [ProbeStatus] = [.ok, .partial, .down, .noNetwork, .unknown]

private struct HistoryReportRequest: Equatable {
    let entries: [HistoryEntry]
    let days: Int
}

private struct HistoryReportResult: Sendable {
    let analytics: HistoryAnalytics
    let slices: [HistoryChartSlice]
    let csv: String
}

private struct HistorySummarySection: View {
    let result: HistoryReportResult

    var body: some View {
        Section("reportSummary") {
            LabeledContent("availability") {
                if let value = result.analytics.availability {
                    Text(value, format: .percent.precision(.fractionLength(1)))
                        .font(.title2.bold())
                } else {
                    Text("insufficientData").foregroundStyle(.secondary)
                }
            }
            LabeledContent("observationCoverage") {
                Text(result.analytics.coverage, format: .percent.precision(.fractionLength(1)))
            }
            LabeledContent("checksCount", value: result.analytics.checks.formatted())
            LabeledContent("outagesCount", value: result.analytics.outageCount.formatted())
            LabeledContent("longestOutage") {
                Text(Duration.seconds(result.analytics.longestOutage), format: .time(pattern: .hourMinuteSecond))
            }
            Text("availabilityExplanation").font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct HistoryCSVDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    let text: String

    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}
