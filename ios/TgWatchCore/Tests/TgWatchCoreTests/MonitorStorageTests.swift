import Foundation
import Testing
@testable import TgWatchCore

struct MonitorStorageTests {
    @Test func rootsTargetsAndRevisionsRemainIsolated() {
        let root = URL(fileURLWithPath: "/fixtures/one")
        let other = URL(fileURLWithPath: "/fixtures/two")
        let definition = ServiceDefinition(id: "custom", kind: .custom)
        let next = ServiceDefinition(id: "custom", kind: .custom)
        #expect(MonitorStorage.directory(for: .telegram, root: root) == root)
        #expect(MonitorStorage.directory(for: definition, root: root).path ==
            "/fixtures/one/targets/custom/\(definition.revision.uuidString)")
        #expect(MonitorStorage.directory(for: next, root: root) != MonitorStorage.directory(for: definition, root: root))
        #expect(MonitorStorage.directory(for: definition, root: other) != MonitorStorage.directory(for: definition, root: root))
    }

    @Test func captureIncludesHistoryFromSharedRoot() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let definitions = ServiceDefinition.defaults
        try BackupFileStore.writePrivate(BackupContents.encodedManifest(definitions),
            to: root.appendingPathComponent("services-v1.json"), limit: BackupContents.manifestLimit)
        let entry = HistoryEntry(epoch: 1, observedAt: Date(), status: .ok)
        for definition in definitions {
            let file = MonitorStorage.directory(for: definition, root: root).appendingPathComponent("history-v1.json")
            let store = HistoryStore(url: file)
            try await store.append(entry)
            try await store.flush()
        }
        let files = try BackupFileStore.capture(at: root)
        let contents = try BackupContents.validateFiles(files)
        #expect(contents.historyEntries == definitions.count)
        #expect(files["history-v1.json"] != nil)
    }
}
