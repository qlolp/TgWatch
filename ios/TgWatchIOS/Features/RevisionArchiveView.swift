import SwiftUI
import TgWatchCore

struct ArchivedRevision: Identifiable {
    let id: String
    let checks: Int
    let from: Date?
    let to: Date?
}

struct RevisionArchiveView: View {
    let controller: MonitorController
    @State private var revisions: [ArchivedRevision] = []
    @State private var failed = false

    var body: some View {
        List {
            Section { Text("revisionArchiveHint").font(.caption).foregroundStyle(.secondary) }
            if revisions.isEmpty { Text("revisionArchiveEmpty") }
            ForEach(revisions) { revision in
                VStack(alignment: .leading, spacing: 4) {
                    Text(revision.id).font(.caption.monospaced()).lineLimit(1)
                    LabeledContent("checksCount", value: revision.checks.formatted())
                    if let from = revision.from, let to = revision.to {
                        Text(from, format: .dateTime.day().month().hour().minute()).font(.caption)
                        Text(to, format: .dateTime.day().month().hour().minute()).font(.caption)
                    }
                }
            }
        }
        .navigationTitle("revisionArchive")
        .overlay { if failed { Text("historyUnavailable").foregroundStyle(.red) } }
        .task { await load() }
    }

    private func load() async {
        guard controller.definition.kind != .telegram else { return }
        let parent = controller.dataDirectory.deletingLastPathComponent()
        let current = controller.definition.revision.uuidString
        let urls = (try? FileManager.default.contentsOfDirectory(at: parent, includingPropertiesForKeys: nil)) ?? []
        var found: [ArchivedRevision] = []
        for url in urls where url.lastPathComponent != current && UUID(uuidString: url.lastPathComponent)?.uuidString == url.lastPathComponent {
            let history = url.appendingPathComponent("history-v1.json")
            guard FileManager.default.fileExists(atPath: history.path) else { continue }
            do {
                let entries = try await HistoryStore(url: history).load()
                found.append(.init(id: url.lastPathComponent, checks: entries.count, from: entries.first?.observedAt, to: entries.last?.observedAt))
            } catch { failed = true }
        }
        revisions = found.sorted { $0.id < $1.id }
    }
}
