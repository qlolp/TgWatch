import SwiftUI
import TgWatchCore

struct FavoritesView: View {
    @ObservedObject var catalog: ServiceCatalog
    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            VStack(alignment: .leading, spacing: 12) {
                Label("favorites", systemImage: "star.fill").font(.headline)
                if catalog.favoriteIDs.isEmpty { Text("favoritesHint").font(.caption).foregroundStyle(.secondary) }
                ForEach(sortedFavorites, id: \.definition.id) { monitor in
                    NavigationLink {
                        ServiceDetailView(controller: monitor, catalog: catalog)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            ServiceRow(controller: monitor)
                            if let latency = monitor.session.snapshot?.latencyMilliseconds, monitor.session.isFresh() {
                                Text("\(latency.formatted(.number.precision(.fractionLength(0)))) ms").font(.caption.monospacedDigit())
                            }
                            if let lastDown = monitor.history.last(where: { $0.status == .down }) {
                                Text("lastOutage") + Text(" ") + Text(lastDown.observedAt, format: .dateTime.day().month().hour().minute())
                            }
                        }.font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                    }.buttonStyle(.plain)
                    Divider()
                }
            }.padding(16)
                .background(.background, in: RoundedRectangle(cornerRadius: 20))
        }
    }
    private var sortedFavorites: [MonitorController] {
        catalog.monitors.filter { catalog.favoriteIDs.contains($0.definition.id) }.sorted {
            let left = priority($0), right = priority($1)
            return left == right ? $0.definition.id < $1.definition.id : left < right
        }
    }
    private func priority(_ monitor: MonitorController) -> Int {
        guard monitor.session.isFresh(), let status = monitor.session.snapshot?.status else { return 3 }
        switch status {
        case .down: return 0
        case .partial: return 1
        case .noNetwork: return 2
        case .unknown: return 3
        case .ok: return 4
        }
    }
}
