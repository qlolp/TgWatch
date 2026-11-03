import WidgetKit
import SwiftUI

struct Provider: TimelineProvider {
    typealias Entry = StatusEntry

    private var appGroupURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.ru.tgwatch.ios")?
            .appendingPathComponent("companion_snapshot.json")
    }

    private func loadSnapshot() -> CompanionSnapshot? {
        guard let url = appGroupURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(CompanionSnapshot.self, from: data)
    }

    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: Date(), snapshot: nil, isStale: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        let snapshot = loadSnapshot()
        let isStale = snapshot.flatMap { value in value.telegramItem.map { value.isStale($0, at: Date()) } } ?? true
        completion(StatusEntry(date: Date(), snapshot: snapshot, isStale: isStale))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        let snapshot = loadSnapshot()
        let now = Date()

        if let snapshot, let item = snapshot.telegramItem {
            let countryDates = snapshot.items.compactMap { $0.countryValidUntil }
                .filter { $0 >= now }.map { $0.addingTimeInterval(0.001) }
            let dates = Set(countryDates + snapshot.items.filter { !snapshot.isStale($0, at: now) }
                .map { $0.validUntil.addingTimeInterval(0.001) }).sorted()
            let entries = ([now] + dates).map { date in
                StatusEntry(date: date, snapshot: snapshot, isStale: snapshot.isStale(item, at: date))
            }
            completion(Timeline(entries: entries, policy: .after((dates.last ?? now).addingTimeInterval(60))))
            return
        }

        let entry = StatusEntry(date: now, snapshot: snapshot, isStale: true)
        let timeline = Timeline(entries: [entry], policy: .after(now.addingTimeInterval(300)))
        completion(timeline)
    }
}

struct StatusEntry: TimelineEntry {
    let date: Date
    let snapshot: CompanionSnapshot?
    let isStale: Bool
}

struct TgWatchWidgetEntryView : View {
    @Environment(\.widgetFamily) private var family
    var entry: Provider.Entry

    var body: some View {
        switch family {
        case .systemMedium:
            mediumView
        default:
            smallView
        }
    }

    private var item: CompanionSnapshot.Item? {
        entry.snapshot?.telegramItem
    }

    private var smallView: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("Telegram", systemImage: "paperplane.fill")
                    .font(.caption.bold())
                Spacer()
                if let flag = item?.freshCountryFlag(at: entry.date) {
                    Text(flag).font(.caption)
                }
            }

            Spacer()

            if let item {
                HStack(spacing: 6) {
                    Image(systemName: iconName(for: item.statusRaw))
                        .foregroundStyle(statusColor(for: item.statusRaw))
                    Text(statusTitle(for: item.statusRaw))
                        .font(.headline)
                        .minimumScaleFactor(0.8)
                }

                if let latency = item.latencyMilliseconds {
                    Text("\(Int(latency)) ms")
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("No data")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            HStack {
                if let item {
                    Text(item.observedAt, format: .dateTime.hour().minute())
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if entry.isStale {
                    Text("Stale")
                        .font(.caption2.bold())
                        .padding(.horizontal, 4)
                        .padding(.vertical, 2)
                        .background(.orange.opacity(0.2), in: Capsule())
                        .foregroundStyle(.orange)
                }
            }
        }
    }

    private var mediumView: some View {
        HStack(alignment: .top, spacing: 16) {
            smallView
                .frame(maxWidth: .infinity, alignment: .leading)

            Divider()

            VStack(alignment: .leading, spacing: 6) {
                Text("Favorites")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)

                let favorites = entry.snapshot?.items.filter { $0.id != "telegram" } ?? []
                if favorites.isEmpty {
                    Text("No other favorites")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                } else {
                    ForEach(favorites.prefix(3)) { fav in
                        HStack(spacing: 6) {
                            let stale = entry.snapshot?.isStale(fav, at: entry.date) ?? true
                            Circle()
                                .fill(stale ? .gray : statusColor(for: fav.statusRaw))
                                .frame(width: 8, height: 8)
                            Text(fav.displayName)
                                .font(.caption)
                                .lineLimit(1)
                            Spacer()
                            if stale { Text("Stale").font(.caption2).foregroundStyle(.orange) }
                            if let ms = fav.latencyMilliseconds, !stale {
                                Text("\(Int(ms))ms")
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
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

    private func statusTitle(for statusRaw: String) -> LocalizedStringKey {
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

struct TgWatchWidget: Widget {
    let kind: String = "TgWatchWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: Provider()) { entry in
            TgWatchWidgetEntryView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("TgWatch")
        .description("Telegram and services reachability status.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
