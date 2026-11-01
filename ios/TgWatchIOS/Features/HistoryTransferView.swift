import SwiftUI
import UniformTypeIdentifiers
import TgWatchCore

struct HistoryTransferView: View {
    @ObservedObject var controller: MonitorController
    @State private var importing = false
    @State private var preview: [HistoryEntry]?
    @State private var confirming = false
    @State private var busy = false
    @State private var failed = false
    @State private var completed = false
    var body: some View {
        Form {
            Section {
                Text(controller.definition.displayName).font(.headline)
                Text("csvImportHint").font(.subheadline)
                Button("importCSV") { importing = true }.disabled(busy)
                if busy { ProgressView() }
                if completed { Label("importComplete", systemImage: "checkmark.circle") }
            }
            if let preview {
                Section("importPreview") {
                    LabeledContent("checksCount", value: preview.count.formatted())
                    if let first = preview.first, let last = preview.last {
                        Text(first.observedAt, format: .dateTime.day().month().year().hour().minute())
                        Text(last.observedAt, format: .dateTime.day().month().year().hour().minute())
                    }
                    Text("importReplaceHint").foregroundStyle(.secondary)
                    Button("replaceHistory", role: .destructive) { confirming = true }.disabled(busy)
                }
            }
        }
        .navigationTitle("importCSV")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .plainText]) { result in
            guard case .success(let url) = result else { return }
            busy = true; completed = false; preview = nil
            let targetID = controller.definition.id
            Task {
                do {
                    preview = try await Task.detached {
                        let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        let handle = try FileHandle(forReadingFrom: url)
                        defer { try? handle.close() }
                        let data = try handle.read(upToCount: 8 * 1024 * 1024 + 1) ?? Data()
                        return try HistoryCSV.parse(data, targetID: targetID)
                    }.value
                } catch { failed = true }
                busy = false
            }
        }
        .confirmationDialog("replaceHistory", isPresented: $confirming, titleVisibility: .visible) {
            Button("replaceHistory", role: .destructive) {
                guard let entries = preview else { return }
                busy = true
                Task {
                    do { try await controller.replaceHistory(entries); preview = nil; completed = true }
                    catch { failed = true }
                    busy = false
                }
            }
        } message: { Text(controller.definition.displayName + " — " + NSLocalizedString("importReplaceHint", comment: "")) }
        .alert("importFailed", isPresented: $failed) { Button("dismiss", role: .cancel) {} }
    }
}
