import SwiftUI
import TgWatchCore

struct MaintenanceView: View {
    @ObservedObject var catalog: ServiceCatalog
    @State private var windows: [MaintenanceWindow]
    @State private var start = 23 * 60
    @State private var end = 7 * 60

    init(catalog: ServiceCatalog) {
        self.catalog = catalog
        _windows = State(initialValue: catalog.maintenanceWindows)
    }

    var body: some View {
        Form {
            Section {
                Text("maintenanceHint").font(.caption).foregroundStyle(.secondary)
                ForEach(windows) { window in
                    Toggle(isOn: binding(window.id)) {
                        Text("\(DailySummary.timeLabel(window.startMinuteOfDay))–\(DailySummary.timeLabel(window.endMinuteOfDay == 1440 ? 0 : window.endMinuteOfDay))")
                    }
                }
                .onDelete { windows.remove(atOffsets: $0); catalog.saveMaintenance(windows) }
            }
            if windows.count < MaintenanceSchedule.maxWindows {
                Section("maintenanceAdd") {
                    DatePicker("quietStart", selection: timeBinding($start), displayedComponents: .hourAndMinute)
                    DatePicker("quietEnd", selection: timeBinding($end), displayedComponents: .hourAndMinute)
                    Button("maintenanceAdd") {
                        windows.append(.init(startMinuteOfDay: start, endMinuteOfDay: end))
                        catalog.saveMaintenance(windows)
                    }
                }
            }
        }
        .navigationTitle("maintenanceWindows")
    }

    private func binding(_ id: String) -> Binding<Bool> {
        Binding(get: { windows.first { $0.id == id }?.enabled ?? false }, set: { value in
            guard let index = windows.firstIndex(where: { $0.id == id }) else { return }
            windows[index].enabled = value
            catalog.saveMaintenance(windows)
        })
    }
    private func timeBinding(_ minute: Binding<Int>) -> Binding<Date> {
        Binding(get: {
            Calendar.current.startOfDay(for: Date()).addingTimeInterval(Double(minute.wrappedValue * 60))
        }, set: { value in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: value)
            minute.wrappedValue = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        })
    }
}
