import Foundation
import Testing
@testable import TgWatchCore

struct LongTermHistoryTests {
    private func entry(_ time: Double, _ status: ProbeStatus = .ok, lifetime: Double = 120) -> HistoryEntry {
        HistoryEntry(epoch: 0, observedAt: Date(timeIntervalSince1970: time), status: status, validForSeconds: lifetime)
    }

    @Test func midnightSplitsCoverageAndGapsRemainUnknown() {
        var history = LongTermHistory()
        history.append(entry(86390))
        history.append(entry(86420, .down))
        let report = history.report(days: 7, at: Date(timeIntervalSince1970: 86450))
        #expect(report.days.count == 2)
        #expect(report.days[0].ok == 10)
        #expect(report.days[1].ok == 20)
        #expect(report.days[1].down == 30)
        #expect(report.checks == 2)
        #expect(report.availability == 0.5)
        #expect(report.unknown == report.to.timeIntervalSince(report.from) - 60)
        let later = history.report(days: 7, at: Date(timeIntervalSince1970: 87000))
        #expect(later.days.reduce(0) { $0 + $1.observed } == 150)
        #expect(history.report(days: 7, at: Date(timeIntervalSince1970: 86450)).days == report.days)
    }

    @Test func retentionIsBoundedAndRollbackStartsNewSegment() {
        var history = LongTermHistory()
        for day in 0..<100 { history.append(entry(Double(day) * 86400)) }
        #expect(history.days.count == 90)
        #expect(history.valid)
        #expect(history.report(days: 90, at: Date(timeIntervalSince1970: 99 * 86400 + 60)).checks == 90)
        #expect(history.report(days: 30, at: Date(timeIntervalSince1970: 99 * 86400 + 60)).checks == 30)
        #expect(history.report(days: 30, at: Date(timeIntervalSince1970: 98 * 86400)).checks == 0)
        history.append(entry(98 * 86400, .down))
        #expect(history.days.count == 1)
        #expect(history.days[0].checks == 1)
    }

    @Test func rawPruningDoesNotEraseLongTermTotalsAndReloadDoesNotDoubleCount() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let now = Date(timeIntervalSince1970: 10 * 86400 + 60)
        let store = HistoryStore(url: url, limit: 1, now: { now })
        try await store.append(entry(0))
        try await store.append(entry(10 * 86400))
        let raw = try await store.load()
        #expect(raw.count == 1)
        let archive = try await store.longTermHistory()
        #expect(archive.report(days: 30, at: now).checks == 2)
        let loaded = HistoryStore(url: url, now: { now })
        let restored = try await loaded.longTermHistory()
        #expect(restored.report(days: 30, at: now).days == archive.report(days: 30, at: now).days)
        try await loaded.replace([entry(10 * 86400, .down)])
        let replacement = try await loaded.longTermHistory()
        #expect(replacement.report(days: 30, at: now).checks == 1)
        try await loaded.clear()
        let empty = try await loaded.longTermHistory()
        #expect(empty.days.isEmpty)
    }

    @Test func legacyMigrationUsesOldRecordsBeforeRawRetentionPrunesThem() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let now = Date(timeIntervalSince1970: 10 * 86400 + 60)
        try JSONEncoder().encode([entry(0), entry(10 * 86400)]).write(to: url)
        let store = HistoryStore(url: url, now: { now })
        let raw = try await store.load()
        #expect(raw.count == 1)
        let archive = try await store.longTermHistory()
        #expect(archive.report(days: 30, at: now).checks == 2)
    }
}
