import SwiftUI
import TgWatchCore

enum DailySummaryText {
    static func title(_ blocks: [(String, DailySummaryStats?)]) -> String {
        if blocks.count == 1, let stats = blocks[0].1, let availability = stats.availability {
            return String(format: NSLocalizedString("dailyTitlePercent", comment: ""), blocks[0].0, availability * 100)
        }
        return NSLocalizedString("dailyTitle", comment: "")
    }
    static func body(_ blocks: [(String, DailySummaryStats?)]) -> String {
        let prepared = Date().formatted(date: .abbreviated, time: .shortened)
        let lines = blocks.map { name, stats -> String in
            guard let stats else { return String(format: NSLocalizedString("dailyNoChecks", comment: ""), name) }
            var parts = [String(format: NSLocalizedString("dailyChecks", comment: ""), name, stats.checks)]
            if stats.outageCount == 0 { parts.append(NSLocalizedString("dailyNoOutages", comment: "")) }
            else if stats.ongoing {
                parts.append(String(format: NSLocalizedString("dailyOngoing", comment: ""), stats.outageCount))
            } else {
                parts.append(String(format: NSLocalizedString("dailyLongest", comment: ""), stats.outageCount, Int(stats.longestOutage / 60)))
            }
            if stats.unknownSeconds >= 60 {
                parts.append(String(format: NSLocalizedString("dailyUnobserved", comment: ""), Int(stats.unknownSeconds / 60)))
            }
            return parts.joined(separator: " ")
        }
        return (lines + [String(format: NSLocalizedString("dailyPrepared", comment: ""), prepared)]).joined(separator: "\n")
    }
}

struct DailySummaryView: View {
    @ObservedObject var catalog: ServiceCatalog
    @State private var enabled: Bool
    @State private var minute: Int
    @State private var denied = false

    init(catalog: ServiceCatalog) {
        self.catalog = catalog
        _enabled = State(initialValue: catalog.dailySummary.enabled)
        _minute = State(initialValue: catalog.dailySummary.minuteOfDay)
    }

    var body: some View {
        Form {
            Section {
                Toggle("dailySummary", isOn: $enabled)
                DatePicker("dailySummaryTime", selection: Binding(get: {
                    Calendar.current.startOfDay(for: Date()).addingTimeInterval(Double(minute * 60))
                }, set: { value in
                    let parts = Calendar.current.dateComponents([.hour, .minute], from: value)
                    minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
                }), displayedComponents: .hourAndMinute)
                Text("dailySummaryHint").font(.caption).foregroundStyle(.secondary)
                Text(DailySummary.timeLabel(minute)).font(.caption).monospacedDigit()
                if denied { Text("notificationDenied").foregroundStyle(.red) }
            }
            Section {
                Button("saveService") {
                    Task {
                        if enabled {
                            let allowed = (try? await MonitorNotifications.shared.authorize()) == true
                            denied = !allowed
                            guard allowed else { return }
                        }
                        catalog.saveDailySummary(.init(enabled: enabled, minuteOfDay: minute))
                    }
                }
            }
        }
        .navigationTitle("dailySummary")
    }
}
