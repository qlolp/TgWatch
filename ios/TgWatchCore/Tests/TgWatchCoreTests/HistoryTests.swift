import XCTest
@testable import TgWatchCore
final class HistoryTests: XCTestCase {
    let now = Date()
    func testFreshnessGapsAndEpochsDoNotInventUptime() {
        let entries = [HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-300), status: .ok),
                       HistoryEntry(epoch: 2, observedAt: now.addingTimeInterval(-10), status: .down)]
        let intervals = HistoryCoverage.intervals(entries: entries, now: now)
        XCTAssertEqual(intervals.map(\.status), [.ok, .unknown, .down])
        XCTAssertEqual(intervals[0].to.timeIntervalSince(intervals[0].from), 120)
        XCTAssertFalse(HistoryCoverage.isFresh(entries[0], now: now))
        XCTAssertFalse(HistoryCoverage.isFresh(entries[1], now: now.addingTimeInterval(-20)))
        XCTAssertTrue(HistoryCoverage.isFresh(entries[1], now: now))
    }
    func testRollbackNeverCreatesNegativeIntervals() {
        let entries = [HistoryEntry(epoch: 1, observedAt: now, status: .ok),
                       HistoryEntry(epoch: 2, observedAt: now.addingTimeInterval(-20), status: .partial)]
        let intervals = HistoryCoverage.intervals(entries: entries, now: now.addingTimeInterval(-10))
        XCTAssertTrue(intervals.allSatisfy { $0.to >= $0.from })
        XCTAssertFalse(intervals.contains { $0.status == .ok })
    }
    func testExpiredHistoryIsPrunedOnLoadWithoutNewMeasurement() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let old = HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-8*86400), status: .ok)
        try JSONEncoder().encode([old]).write(to: file)
        let values = try await HistoryStore(url: file).load()
        XCTAssertTrue(values.isEmpty)
    }
    func testAtomicPersistenceAndRetention() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("history.json")
        let store = HistoryStore(url: file, limit: 3)
        for i in 0..<5 { try await store.append(HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(Double(i)), status: .ok)) }
        let loaded = try await HistoryStore(url: file).load()
        XCTAssertEqual(loaded.count, 3); XCTAssertEqual(loaded.first?.observedAt, now.addingTimeInterval(2))
        try await store.append(HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(8*86400), status: .down))
        let retained = try await store.load(); XCTAssertEqual(retained.count, 1)
        try await store.clear(); let cleared = try await store.load(); XCTAssertTrue(cleared.isEmpty)
    }
    func testCorruptionPreservedAndDoesNotBecomeEmptyHistory() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let broken = Data("{broken".utf8); try broken.write(to: file)
        let store = HistoryStore(url: file)
        do { _ = try await store.load(); XCTFail("corruption must be visible") } catch {}
        do { try await store.append(HistoryEntry(epoch: 1, observedAt: now, status: .ok)); XCTFail("do not overwrite corrupt file") } catch {}
        XCTAssertEqual(try Data(contentsOf: file), broken)
        try await store.clear(); let cleared = try await store.load(); XCTAssertTrue(cleared.isEmpty)
    }
    func testFailedWriteKeepsDirtyEntryForRetry() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data([1]).write(to: folder) // Parent path is a file: deterministic write failure.
        let store = HistoryStore(url: folder.appendingPathComponent("history.json"))
        do { try await store.append(HistoryEntry(epoch: 1, observedAt: now, status: .ok)); XCTFail("write must fail") } catch {}
        try FileManager.default.removeItem(at: folder)
        try await store.append(HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(1), status: .down))
        let entries = try await store.load(); XCTAssertEqual(entries.map(\.status), [.ok, .down])
    }
}
