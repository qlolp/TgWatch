import Foundation
import Testing
@testable import TgWatchCore

struct HistoryCSVTests {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    @Test func targetExportRoundTripsWithoutFillingUnknownGaps() throws {
        let entries = [HistoryEntry(epoch: 1, observedAt: start, status: .ok, validForSeconds: 60, latencyMilliseconds: 42)]
        let report = HistoryAnalytics(entries: entries, from: start, to: start.addingTimeInterval(180))
        let data = Data(HistoryCSV.export(report, targetID: "matrix", entries: entries).utf8)
        let imported = try HistoryCSV.parse(data, targetID: "matrix")
        #expect(imported.count == 1)
        #expect(imported[0].coverageLifetime == 60)
        #expect(imported[0].latencyMilliseconds == 42)
        #expect(throws: HistoryCSV.Failure.self) { try HistoryCSV.parse(data, targetID: "telegram") }
    }
    @Test func invalidDurationAndMixedTargetsAreRejected() {
        let row = "2023-11-14T22:13:20.000Z,2023-11-14T22:14:20.000Z,OK,60000,-1"
        #expect(throws: HistoryCSV.Failure.self) {
            try HistoryCSV.parse(Data((HistoryCSV.legacyHeader + "\n" + row.replacingOccurrences(of: "60000", with: "1")).utf8), targetID: "telegram")
        }
        let csv = HistoryCSV.header + "\n" + row + ",0,true,matrix,1\n" + row + ",0,true,telegram,1\n"
        #expect(throws: HistoryCSV.Failure.self) { try HistoryCSV.parse(Data(csv.utf8), targetID: "matrix") }
    }
    @Test func emptyLatencyIsUnavailableBeforeValidation() throws {
        let row = "2023-11-14T22:13:20.000Z,2023-11-14T22:14:20.000Z,DOWN,60000,,0,true,matrix,1"
        let unknown = "2023-11-14T22:14:20.000Z,2023-11-14T22:15:20.000Z,UNKNOWN,60000,,0,true,matrix,1"
        let imported = try HistoryCSV.parse(Data((HistoryCSV.header + "\n" + row + "\n" + unknown + "\n").utf8), targetID: "matrix")
        #expect(imported.count == 1)
        #expect(imported[0].status == .down)
        #expect(imported[0].latencyMilliseconds == nil)
        let invalid = HistoryCSV.header + "\n" + row.replacingOccurrences(of: ",,", with: ",nope,") + "\n"
        #expect(throws: HistoryCSV.Failure.self) { try HistoryCSV.parse(Data(invalid.utf8), targetID: "matrix") }
    }
    @Test func legacyTelegramStillImports() throws {
        let report = HistoryAnalytics(entries: [.init(epoch: 1, observedAt: start, status: .down)], from: start, to: start.addingTimeInterval(120))
        #expect(try HistoryCSV.parse(Data(report.csv.utf8), targetID: "telegram").first?.status == .down)
    }
}
