import SwiftUI

struct ContentView: View {
    @State private var sessionManager = WatchSessionManager()

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                List {
                    if let snapshot = sessionManager.snapshot, !snapshot.items.isEmpty {
                        ForEach(snapshot.items) { item in
                            itemRow(item, stale: snapshot.isStale(item, at: context.date))
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("No data yet")
                                .font(.headline)
                            Text("Open TgWatch on iPhone to sync reachability status.")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 8)
                    }

                    Section {
                        Button {
                            sessionManager.requestRefresh()
                        } label: {
                            HStack {
                                Spacer()
                                if sessionManager.isRefreshing {
                                    ProgressView()
                                } else {
                                    Label("Refresh", systemImage: "arrow.clockwise")
                                }
                                Spacer()
                            }
                        }
                        .disabled(sessionManager.isRefreshing)

                        if let message = sessionManager.statusMessage {
                            Text(message)
                                .font(.caption2)
                                .foregroundStyle(.orange)
                        }
                    }
                }
            }
            .navigationTitle("TgWatch")
        }
    }

    private func itemRow(_ item: CompanionSnapshot.Item, stale: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(item.displayName)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                if let flag = item.countryFlag {
                    Text(flag)
                }
            }

            HStack(spacing: 6) {
                Image(systemName: iconName(for: item.statusRaw))
                    .foregroundStyle(stale ? .gray : statusColor(for: item.statusRaw))
                Text(statusTitle(for: item.statusRaw))
                    .font(.subheadline)

                Spacer()

                if let latency = item.latencyMilliseconds {
                    Text("\(Int(latency)) ms")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }

            HStack {
                Text(item.observedAt, format: .dateTime.hour().minute().second())
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                Spacer()

                if stale {
                    Text("Stale")
                        .font(.caption2.bold())
                        .foregroundStyle(.orange)
                }
            }
        }
        .padding(.vertical, 2)
    }

    private func iconName(for statusRaw: String) -> String {
        switch statusRaw {
        case "ok": return "checkmark.circle.fill"
        case "slow": return "tortoise.fill"
        case "partial": return "exclamationmark.triangle.fill"
        case "down": return "xmark.circle.fill"
        case "noNetwork": return "wifi.slash"
        default: return "questionmark.circle"
        }
    }

    private func statusColor(for statusRaw: String) -> Color {
        switch statusRaw {
        case "ok": return .green
        case "slow": return .yellow
        case "partial": return .orange
        case "down": return .red
        case "noNetwork": return .gray
        default: return .secondary
        }
    }

    private func statusTitle(for statusRaw: String) -> String {
        switch statusRaw {
        case "ok": return "Available"
        case "slow": return "Slow"
        case "partial": return "Partial"
        case "down": return "Down"
        case "noNetwork": return "Offline"
        default: return "Unknown"
        }
    }
}

#Preview {
    ContentView()
}
