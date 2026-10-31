import XCTest
@testable import TgWatchCore
final class ServiceTests: XCTestCase {
    func testMatrixRequiresStrictCompleteVersionsEvidence() {
        func result(_ text: String, code: Int = 200, complete: Bool = true) -> ServiceOutcome {
            ServicePolicy.evaluate(target:.matrix,code:code,body:Data(text.utf8),complete:complete)
        }
        XCTAssertEqual(result("{\"versions\":[\"v1.11\"],\"unstable_features\":{\"x\":true}}"),.matrixVersions)
        for body in ["<html>login</html>","{}","{\"versions\":[]}","{\"versions\":[1]}","{\"versions\":[\"  \" ]}","{\"versions\":[\"v1\"],\"versions\":[\"v2\"]}","{\"versions\":[\"v1\"],\"extra\":{\"x\":1,\"x\":2}}","{\"versions\":[\"v1\"]} trailing"] {
            XCTAssertEqual(result(body),.invalidPayload,body)
        }
        XCTAssertEqual(result("{\"versions\":[\"v1\"]}",complete:false),.invalidPayload)
        XCTAssertEqual(result("{\"versions\":[\"v1\"]}",code:302),.httpError)
        XCTAssertEqual(result("{\"versions\":[\"v1\"]}",code:429),.httpError)
        XCTAssertEqual(result(String(repeating:" ",count:65537)),.invalidPayload)
        XCTAssertEqual(ServicePolicy.evaluate(target:.matrix,code:200,body:Data([255]),complete:true),.invalidPayload)
        XCTAssertEqual(result("{\"versions\":[\""+String(repeating:"a",count:129)+"\"]}"),.invalidPayload)
    }
    func testMatrixRejectsEscapedDuplicateKeysAndResourceExhaustion() {
        let duplicate = #"{"versions":["v1"],"ver\u0073ions":["v2"]}"#
        let deep = "{\"versions\":[\"v1\"],\"x\":"+String(repeating:"[",count:66)+"0"+String(repeating:"]",count:66)+"}"
        for body in [duplicate,deep,"{\"versions\":[\"v1\"],\"x\":01}","{\"versions\":[\"v1\"],}"] {
            XCTAssertEqual(ServicePolicy.evaluate(target:.matrix,code:200,body:Data(body.utf8),complete:true),.invalidPayload)
        }
    }
    func testWhatsAppResponseDoesNotInventAccountOrRegionAvailability() {
        for code in [200,302,401,403,429,499] {
            XCTAssertEqual(ServicePolicy.evaluate(target:.whatsapp,code:code,body:Data(),complete:false),.httpResponse)
        }
        for code in [0,500,503] {
            XCTAssertEqual(ServicePolicy.evaluate(target:.whatsapp,code:code,body:Data(),complete:true),.httpError)
        }
        XCTAssertEqual(classify(.init(service:.init(target:.matrix,outcome:.invalidPayload,httpCode:200,controlConfirmed:false))),.unknown)
        XCTAssertEqual(classify(.init(service:.init(target:.matrix,outcome:.invalidPayload,httpCode:200,controlConfirmed:true))),.down)
        XCTAssertEqual(classify(.init(pathUnavailable:true,service:.init(target:.whatsapp,outcome:.httpResponse))),.noNetwork)
        XCTAssertEqual(classify(.init(service:.init(target:.matrix,outcome:.matrixVersions))),.ok)
    }
    func testTypedHistoryUsesVersionTwoSoOlderAppsFailClosed() async throws {
        let now=Date(), row=HistoryEntry(epoch:1,observedAt:Date(),status:.ok,target:.matrix,service:.init(target:.matrix,outcome:.matrixVersions))
        XCTAssertEqual(row.schemaVersion,2)
        let folder=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        let store=HistoryStore(url:folder.appendingPathComponent("history.json"))
        try await store.append(HistoryEntry(epoch:1,observedAt:now,status:.ok))
        try await store.append(row)
        let values=try await store.load(); XCTAssertEqual(values.map(\.targetID),[.telegram,.matrix])
        XCTAssertFalse(values.allSatisfy { $0.schemaVersion == 1 },"Old reader must reject mixed history rather than call every row Telegram")
    }
    func testTargetHistoryCannotCrossContaminateStatisticsOrExport() {
        let now=Date(), session=UUID()
        let rows = [HistoryEntry(epoch:1,observedAt:now.addingTimeInterval(-60),status:.down,sessionID:session,target:.matrix),
                    HistoryEntry(epoch:1,observedAt:now.addingTimeInterval(-30),status:.down,sessionID:session,target:.matrix),
                    HistoryEntry(epoch:1,observedAt:now.addingTimeInterval(-20),status:.ok,sessionID:session)]
        let tg=HistoryReport(entries:rows,from:now.addingTimeInterval(-100),until:now)
        XCTAssertEqual(tg.observations.count,1); XCTAssertTrue(tg.incidents.isEmpty)
        let matrix=HistoryReport(entries:rows,from:now.addingTimeInterval(-100),until:now,target:.matrix)
        XCTAssertEqual(matrix.seconds(.down),30); XCTAssertEqual(matrix.incidents.count,1)
        XCTAssertTrue(matrix.csv.contains("\nmatrix,")); XCTAssertFalse(matrix.csv.contains("\ntelegram,"))
        XCTAssertEqual(rows.last?.targetID,.telegram)
    }
}
