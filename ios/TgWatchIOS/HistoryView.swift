import SwiftUI
import TgWatchCore
struct HistoryView: View {
    @ObservedObject var controller: MonitorController
    @State private var confirming = false
    var body: some View {
        List {
            Text("historyExplanation")
            if controller.historyUnavailable { Text("historyUnavailable").foregroundStyle(.red) }
            if controller.history.isEmpty { Text("historyEmpty") }
            ForEach(controller.history.reversed()) { entry in
                VStack(alignment: .leading) {
                    Text(LocalizedStringKey("status.\(entry.status.rawValue)"))
                    Text(entry.observedAt, style: .date)
                    Text(entry.observedAt, style: .time)
                }.accessibilityElement(children: .combine).accessibilityIdentifier("historyEntry")
            }
            Button("clearHistory", role: .destructive) { confirming = true }.accessibilityIdentifier("clearHistory")
        }.navigationTitle("history")
            .confirmationDialog("clearHistoryQuestion", isPresented: $confirming, titleVisibility: .visible) {
                Button("clearHistory", role: .destructive, action: controller.clearHistory)
                Button("cancel", role: .cancel) {}
            }
    }
}
