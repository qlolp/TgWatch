import XCTest
@testable import TgWatchCore
final class HistoryReportTests: XCTestCase {
    let origin = Date(timeIntervalSince1970: 2_000_000_000)
    let session = UUID()
    func row(_ t: Double, _ status: ProbeStatus, epoch: UInt64 = 1, session: UUID? = nil) -> HistoryEntry {
        HistoryEntry(epoch: epoch, observedAt: origin.addingTimeInterval(t), status: status, sessionID: session ?? self.session)
    }
    func report(_ rows: [HistoryEntry], from: Double = 0, until: Double = 300) -> HistoryReport {
        HistoryReport(entries: rows, from: origin.addingTimeInterval(from), until: origin.addingTimeInterval(until))
    }
    func testCoverageRequiresBracketingSameSessionAndEpoch() {
        let value = report([row(30,.ok), row(60,.down), row(90,.down), row(120,.ok,epoch:2), row(150,.ok,session:UUID())])
        XCTAssertEqual(value.seconds(.ok),30); XCTAssertEqual(value.seconds(.down),30)
        XCTAssertEqual(value.seconds(.unknown),240)
        XCTAssertEqual(value.coveredSeconds,60)
        XCTAssertEqual(report([]).seconds(.unknown),300)
    }
    func testGapsRollbackFutureAndWindowClipping() {
        XCTAssertEqual(report([row(0,.ok),row(121,.down)]).coveredSeconds,0)
        let value = report([row(20,.ok),row(50,.ok),row(10,.noNetwork),row(40,.noNetwork),row(400,.ok)])
        XCTAssertEqual(value.seconds(.ok),0); XCTAssertEqual(value.seconds(.noNetwork),30)
        XCTAssertEqual(report([row(0,.ok),row(60,.ok)],from:20,until:80).seconds(.ok),40)
    }
    func testIncidentsRequireTwoFailuresAndTwoRecoveryChecks() {
        let value = report([row(0,.down),row(30,.down),row(60,.ok),row(90,.ok),row(120,.noNetwork),row(150,.unknown)])
        XCTAssertEqual(value.incidents.count,2)
        XCTAssertTrue(value.incidents[0].confirmed)
        XCTAssertEqual(value.incidents[0].recoveredAt,origin.addingTimeInterval(90))
        XCTAssertFalse(value.incidents[1].confirmed); XCTAssertNil(value.incidents[1].recoveredAt)
        XCTAssertNil(report([row(0,.down),row(30,.down),row(60,.ok),row(90,.ok,epoch:2)]).incidents.first?.recoveredAt)
    }
    func testDuplicatesAndLegacyCannotInventConfirmation() throws {
        let single = row(0,.down)
        XCTAssertFalse(report([single,single]).incidents.first!.confirmed)
        let legacy = HistoryEntry(epoch:1,observedAt:origin,status:.ok)
        XCTAssertEqual(report([legacy,HistoryEntry(epoch:1,observedAt:origin.addingTimeInterval(30),status:.ok)]).coveredSeconds,0)
        let json = try JSONEncoder().encode([legacy])
        let decoded = try JSONDecoder().decode([HistoryEntry].self,from:json)
        XCTAssertNil(decoded[0].sessionID)
        XCTAssertEqual(try JSONDecoder().decode([HistoryEntry].self,from:JSONEncoder().encode([row(0,.down)]))[0].sessionID,session)
    }
    func testRecoveryCanBeConfirmedAfterIntermediateUncertainObservations() {
        for middle in [ProbeStatus.partial,.unknown] {
            let value = report([row(0,.down),row(30,.down),row(60,middle),row(90,.ok),row(120,.ok)])
            XCTAssertEqual(value.incidents.first?.recoveredAt,origin.addingTimeInterval(120))
        }
        let secondEpisode = report([row(0,.down),row(30,.down),row(60,.partial),row(90,.down),row(120,.down),row(150,.ok),row(180,.ok)])
        XCTAssertNil(secondEpisode.incidents[0].recoveredAt)
        XCTAssertEqual(secondEpisode.incidents[1].recoveredAt,origin.addingTimeInterval(180))
        let gap = report([row(0,.down),row(30,.down),row(60,.partial),row(181,.ok),row(211,.ok)])
        XCTAssertNil(gap.incidents.first?.recoveredAt)
    }
    func testUnknownPartialAndRouteChangesSplitEpisodes() {
        for status in [ProbeStatus.unknown,.partial,.noNetwork] {
            let value = report([row(0,.down),row(30,status),row(60,.down),row(90,.ok)])
            XCTAssertTrue(value.incidents.filter { $0.status == .down }.allSatisfy { !$0.confirmed && $0.recoveredAt == nil })
        }
        XCTAssertEqual(report([row(0,.down),row(121,.down)]).incidents.count,2)
        XCTAssertTrue(report([row(0,.down),row(30,.down,session:UUID())]).incidents.allSatisfy { !$0.confirmed })
    }
    func testLegacyAndNewSessionsPersistWithoutRewritingUnsupportedSchema() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
        let file = folder.appendingPathComponent("history.json")
        let legacy = HistoryEntry(epoch:1,observedAt:origin,status:.down)
        try JSONEncoder().encode([legacy]).write(to:file)
        let store = HistoryStore(url:file,now:{ Date(timeIntervalSince1970:2_000_000_030) })
        try await store.append(row(30,.down))
        let decoded = try JSONDecoder().decode([HistoryEntry].self,from:Data(contentsOf:file))
        XCTAssertNil(decoded[0].sessionID); XCTAssertEqual(decoded[1].sessionID,session)
        let unsupported = String(data:try JSONEncoder().encode([legacy]),encoding:.utf8)!.replacingOccurrences(of:"\"schemaVersion\":1",with:"\"schemaVersion\":99")
        let bytes = Data(unsupported.utf8); try bytes.write(to:file)
        do { _ = try await HistoryStore(url:file).load(); XCTFail("unknown schema must fail") } catch {}
        XCTAssertEqual(try Data(contentsOf:file),bytes)
    }
    func testCSVIsBoundedPublicDataAndMarksRollbackExclusion() {
        let value = report([row(40,.ok),row(50,.ok),row(10,.down),row(30,.down)])
        let csv = value.csv
        XCTAssertEqual(csv.components(separatedBy:"\n").count,6)
        XCTAssertTrue(csv.hasPrefix("target_id,observed_at_utc,status,epoch,session_id,in_current_statistics\n"))
        XCTAssertTrue(csv.contains(",false\n")); XCTAssertTrue(csv.contains(",true\n"))
        XCTAssertFalse(csv.contains("https://")); XCTAssertFalse(csv.contains("ip="))
    }
}
