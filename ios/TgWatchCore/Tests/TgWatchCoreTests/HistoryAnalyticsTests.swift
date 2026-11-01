import Foundation
import Testing
@testable import TgWatchCore

struct HistoryAnalyticsTests {
    let start = Date(timeIntervalSince1970: 1_700_000_000)

    @Test func missingTimeIsNotAvailability() {
        let entries = [
            HistoryEntry(epoch: 1, observedAt: start.addingTimeInterval(60), status: .ok),
            HistoryEntry(epoch: 1, observedAt: start.addingTimeInterval(120), status: .down),
            HistoryEntry(epoch: 1, observedAt: start.addingTimeInterval(180), status: .noNetwork)
        ]
        let result = HistoryAnalytics(entries: entries, from: start, to: start.addingTimeInterval(360))
        #expect(result.duration(.ok) == 60)
        #expect(result.duration(.down) == 60)
        #expect(result.duration(.noNetwork) == 120)
        #expect(result.duration(.unknown) == 120)
        #expect(result.availability == 0.5)
        #expect(abs(result.coverage - 2.0 / 3.0) < 0.00001)
        #expect(result.checks == 3)
    }

    @Test func gapsSplitOutagesAndWindowClipsHistory() {
        let entries = [
            HistoryEntry(epoch: 1, observedAt: start.addingTimeInterval(-20), status: .down),
            HistoryEntry(epoch: 1, observedAt: start.addingTimeInterval(20), status: .down),
            HistoryEntry(epoch: 1, observedAt: start.addingTimeInterval(300), status: .down)
        ]
        let result = HistoryAnalytics(entries: entries, from: start, to: start.addingTimeInterval(350))
        #expect(result.outageCount == 2)
        #expect(result.longestOutage == 140)
        #expect(result.duration(.unknown) == 160)
        #expect(result.checks == 2)
    }

    @Test func emptyAndRollbackAreUnknown() {
        let empty = HistoryAnalytics(entries: [], from: start, to: start.addingTimeInterval(86400))
        #expect(empty.availability == nil)
        #expect(empty.coverage == 0)
        let entries = [
            HistoryEntry(epoch: 1, observedAt: start.addingTimeInterval(100), status: .ok),
            HistoryEntry(epoch: 2, observedAt: start.addingTimeInterval(10), status: .down)
        ]
        let result = HistoryAnalytics(entries: entries, from: start, to: start.addingTimeInterval(60))
        #expect(result.duration(.ok) == 0)
        #expect(result.duration(.down) == 50)
        #expect(result.checks == 1)
    }

    @Test func profileLifetimeAndChartPreserveCoverage() {
        let entries = [HistoryEntry(epoch: 1, observedAt: start, status: .ok, validForSeconds: 30)]
        let result = HistoryAnalytics(entries: entries, from: start, to: start.addingTimeInterval(180))
        #expect(result.duration(.ok) == 30)
        #expect(result.duration(.unknown) == 150)
        let slices = result.chartSlices(count: 7)
        #expect(abs(slices.reduce(0) { $0 + $1.seconds } - 180) < 0.00001)
        #expect(abs(slices.filter { $0.status == .ok }.reduce(0) { $0 + $1.seconds } - 30) < 0.00001)
    }

    @Test func csvUsesUTCAndUnknownGaps() {
        let result = HistoryAnalytics(entries: [.init(epoch: 1, observedAt: start, status: .down)],
                                      from: start, to: start.addingTimeInterval(180))
        #expect(result.csv.hasPrefix("from_utc,until_utc,status,duration_ms,latency_ms\n"))
        #expect(result.csv.contains("Z,TG_DOWN,120000,-1"))
        #expect(result.csv.contains("Z,UNKNOWN,60000,-1"))
    }
}
