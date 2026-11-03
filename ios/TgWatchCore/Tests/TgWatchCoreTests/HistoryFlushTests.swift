import Foundation
import Testing
@testable import TgWatchCore

struct HistoryFlushTests {
    @Test func flushRetriesDirtyHistoryWithoutAnotherMeasurement() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data([1]).write(to: folder)
        let url = folder.appendingPathComponent("history.json")
        let store = HistoryStore(url: url)
        let entry = HistoryEntry(epoch: 1, observedAt: Date(), status: .down)
        do {
            try await store.append(entry)
            Issue.record("Writing below a regular file must fail")
        } catch {}
        try FileManager.default.removeItem(at: folder)
        try await store.flush()
        let restored = try await HistoryStore(url: url).load()
        #expect(restored == [entry])
    }

    @Test func flushDoesNotOverwriteCorruptHistory() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let original = Data("{broken".utf8)
        try original.write(to: url)
        do {
            try await HistoryStore(url: url).flush()
            Issue.record("Corrupt history must prevent export")
        } catch {}
        #expect(try Data(contentsOf: url) == original)
    }
}
