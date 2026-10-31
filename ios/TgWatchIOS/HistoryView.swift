import SwiftUI
import TgWatchCore

struct HistoryView: View {
    @ObservedObject var controller: MonitorController
    @Environment(\.calendar) private var calendar
    @State private var confirming = false
    @State private var days: [HistoryDay] = []

    var body: some View {
        List {
            Section {
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
