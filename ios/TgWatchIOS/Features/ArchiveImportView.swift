import SwiftUI
import UniformTypeIdentifiers
import TgWatchCore

struct ArchiveImportView: View {
    @ObservedObject var catalog: ServiceCatalog
    @State private var importing = false
    @State private var grouped: [String: [HistoryEntry]]?
    @State private var skipped: [String] = []
    @State private var busy = false
    @State private var failed = false
    @State private var completed = false
    @State private var confirming = false

    var body: some View {
        Form {
            Section {
                Text("archiveImportHint").font(.subheadline)
                Button("importCSV") { importing = true }.disabled(busy)
                if busy { ProgressView() }
                if completed { Label("importComplete", systemImage: "checkmark.circle") }
            }
            if let grouped {
                Section("importPreview") {
                    ForEach(grouped.keys.sorted(), id: \.self) { id in
                        LabeledContent(catalog.monitors.first { $0.definition.id == id }?.definition.displayName ?? id,
                                       value: (grouped[id]?.count ?? 0).formatted())
                    }
                    if !skipped.isEmpty {
                        Text(String(format: NSLocalizedString("archiveSkipped", comment: ""), skipped.joined(separator: ", ")))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Button("replaceHistory", role: .destructive) { confirming = true }.disabled(busy || grouped.isEmpty)
                }
            }
        }
        .navigationTitle("archiveImport")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .plainText]) { result in
            guard case .success(let url) = result else { return }
            busy = true; completed = false; grouped = nil; skipped = []
            let known = Set(catalog.monitors.map(\.definition.id))
            Task {
                do {
                    let parsed = try await Task.detached {
                        let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        let handle = try FileHandle(forReadingFrom: url)
                        defer { try? handle.close() }
                        let data = try handle.read(upToCount: 8 * 1024 * 1024 + 1) ?? Data()
                        return try HistoryCSV.parseGrouped(data)
                    }.value
                    skipped = parsed.keys.filter { !known.contains($0) }.sorted()
                    grouped = parsed.filter { known.contains($0.key) }
                } catch { failed = true }
                busy = false
            }
        }
        .confirmationDialog("replaceHistory", isPresented: $confirming, titleVisibility: .visible) {
            Button("replaceHistory", role: .destructive) {
                guard let grouped else { return }
                busy = true
                Task {
                    do {
                        for (id, entries) in grouped {
                            guard let monitor = catalog.monitors.first(where: { $0.definition.id == id }) else { continue }
                            try await monitor.replaceHistory(entries)
                        }
                        self.grouped = nil
                        completed = true
                    } catch { failed = true }
                    busy = false
                }
            }
        } message: { Text("importReplaceHint") }
        .alert("importFailed", isPresented: $failed) { Button("dismiss", role: .cancel) {} }
    }
}
