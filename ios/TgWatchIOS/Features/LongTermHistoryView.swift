import SwiftUI
import TgWatchCore

struct LongTermHistoryView: View {
    @ObservedObject var controller: MonitorController
    @State private var days = 30
    @State private var now = Date()

    private var report: LongTermReport { controller.longTermHistory.report(days: days, at: now) }

    var body: some View {
        List {
            Section {
                Picker("reportPeriod", selection: $days) {
                    Text("lastWeek").tag(7)
                    Text("lastMonth").tag(30)
                    Text("lastQuarter").tag(90)
                }.pickerStyle(.segmented)
                Text("longTermHint").font(.caption).foregroundStyle(.secondary)
                if controller.historyUnavailable { Text("historyUnavailable").foregroundStyle(.red) }
            }
            Section("reportSummary") {
                LabeledContent("checksCount", value: report.checks.formatted())
                LabeledContent("availability") {
                    if let availability = report.availability {
                        Text(availability, format: .percent.precision(.fractionLength(1)))
                    } else { Text("insufficientData") }
                }
                LabeledContent("observationCoverage") {
                    Text(report.coverage, format: .percent.precision(.fractionLength(1)))
                }
                LabeledContent("status.unknown") {
                    Text(Duration.seconds(report.unknown), format: .units(allowed: [.days, .hours, .minutes], width: .abbreviated))
                }
            }
            Section("dailyTotals") {
                if report.days.isEmpty { Text("historyEmpty") }
                ForEach(report.days.sorted { $0.day > $1.day }) { day in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(day.day, format: Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: .gmt))
                            .font(.headline)
                        LabeledContent("checksCount", value: day.checks.formatted())
                        LabeledContent("availability") {
                            if let availability = day.availability {
                                Text(availability, format: .percent.precision(.fractionLength(1)))
                            } else { Text("insufficientData") }
                        }
                        LabeledContent("downtimeDuration") {
                            Text(Duration.seconds(day.down), format: .time(pattern: .hourMinuteSecond))
                        }
                    }.font(.subheadline)
                }
            }
        }
        .navigationTitle("longTermTitle")
        .onChange(of: controller.longTermHistory, initial: true) { _, _ in now = Date() }
        .onChange(of: days) { _, _ in now = Date() }
        .refreshable { await controller.reload(); now = Date() }
    }
}
