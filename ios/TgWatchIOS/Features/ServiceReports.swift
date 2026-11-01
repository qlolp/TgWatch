import SwiftUI
import TgWatchCore

struct AlertSettingsView: View {
    @ObservedObject var controller: MonitorController
    @State private var denied = false
    var body: some View {
        Form {
            Section {
                Button("allowNotifications") {
                    Task {
                        do { denied = try await !MonitorNotifications.shared.authorize() }
                        catch { denied = true }
                    }
                }
                Text("notificationScope").font(.caption).foregroundStyle(.secondary)
                if denied { Text("notificationDenied").foregroundStyle(.red) }
                if controller.alertError { Text("preferencesUnavailable").foregroundStyle(.red) }
            }
            Section("alertEvents") {
                Toggle("alertDown", isOn: $controller.alertSettings.down)
                Toggle("alertPartial", isOn: $controller.alertSettings.partial)
                Toggle("alertRecovery", isOn: $controller.alertSettings.recovery)
                Toggle("alertOffline", isOn: $controller.alertSettings.offline)
                Toggle("alertSound", isOn: $controller.alertSettings.sound)
            }
            Section("quietHours") {
                Toggle("quietHours", isOn: $controller.alertSettings.quietEnabled)
                if controller.alertSettings.quietEnabled {
                    DatePicker("quietStart", selection: timeBinding(\.quietStart), displayedComponents: .hourAndMinute)
                    DatePicker("quietEnd", selection: timeBinding(\.quietEnd), displayedComponents: .hourAndMinute)
                }
                Button("muteHour") { controller.alertSettings.mutedUntil = Date().addingTimeInterval(3600) }
                if let until = controller.alertSettings.mutedUntil, until > Date() {
                    Text(until, format: .dateTime.hour().minute())
                    Button("unmute") { controller.alertSettings.mutedUntil = nil }
                }
            }
        }
        .navigationTitle("alertSettings")
        .onChange(of: controller.alertSettings) { _, _ in controller.saveAlerts() }
    }
    private func timeBinding(_ key: WritableKeyPath<AlertSettings, Int>) -> Binding<Date> {
        Binding(get: {
            Calendar.current.startOfDay(for: Date()).addingTimeInterval(Double(controller.alertSettings[keyPath: key] * 60))
        }, set: { value in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: value)
            controller.alertSettings[keyPath: key] = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        })
    }
}

struct EventJournalView: View {
    @ObservedObject var controller: MonitorController
    @State private var events: [HistoryEntry] = []
    var body: some View {
        List {
            Section { Text("journalHint").font(.caption).foregroundStyle(.secondary) }
            if events.isEmpty { Text("historyEmpty") }
            ForEach(events) { event in
                VStack(alignment: .leading, spacing: 6) {
                    Label(event.status.displayTitle, systemImage: event.status.displaySymbol).foregroundStyle(event.status.displayColor)
                    Text(event.observedAt, format: .dateTime.day().month().hour().minute().second()).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("eventJournal")
        .task(id: controller.history) {
            var previous: HistoryEntry?
            var result: [HistoryEntry] = []
            for entry in controller.history {
                if previous == nil || previous?.status != entry.status ||
                    entry.observedAt.timeIntervalSince(previous?.observedAt ?? entry.observedAt) > (previous?.coverageLifetime ?? 0) {
                    result.append(entry)
                }
                previous = entry
            }
            events = Array(result.suffix(150).reversed())
        }
    }
}

struct LatencyReportView: View {
    @ObservedObject var controller: MonitorController
    @State private var hours = 24
    @State private var values: [Double] = []
    var body: some View {
        List {
            Picker("reportPeriod", selection: $hours) {
                Text("lastHour").tag(1)
                Text("lastDay").tag(24)
                Text("lastWeek").tag(168)
            }.pickerStyle(.segmented)
            Section("latencyReport") {
                LabeledContent("checksCount", value: values.count.formatted())
                if !values.isEmpty {
                    row("latencyMean", value: values.reduce(0, +) / Double(values.count))
                    row("latencyP50", value: percentile(0.5))
                    row("latencyP95", value: percentile(0.95))
                } else { Text("insufficientData") }
                Text("latencyHint").font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("latencyReport")
        .task(id: LatencyRequest(entries: controller.history, hours: hours)) {
            let cutoff = Date().addingTimeInterval(-Double(hours) * 3600)
            values = controller.history.filter { $0.observedAt >= cutoff && $0.observedAt <= Date() }
                .compactMap(\.latencyMilliseconds).filter { $0.isFinite && $0 >= 0 }.sorted()
        }
    }
    private func percentile(_ value: Double) -> Double { values[max(0, Int(ceil(Double(values.count) * value)) - 1)] }
    private func row(_ title: LocalizedStringKey, value: Double) -> some View {
        LabeledContent(title) { Text(value, format: .number.precision(.fractionLength(0))) + Text(" ms") }
    }
}

private struct LatencyRequest: Equatable { let entries: [HistoryEntry]; let hours: Int }

struct ShareStatusView: View {
    @ObservedObject var controller: MonitorController
    @State private var text = ""
    var body: some View {
        List {
            Section("sharePreview") { Text(text) }
            ShareLink(item: text) { Label("shareStatus", systemImage: "square.and.arrow.up") }
        }
        .navigationTitle("shareStatus")
        .onAppear {
            let status = controller.session.isFresh() ? controller.session.snapshot?.status ?? .unknown : .unknown
            text = controller.definition.displayName + "\n" + NSLocalizedString("status.\(status.rawValue)", comment: "") + "\n" + Date().formatted()
        }
    }
}
