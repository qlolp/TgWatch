import XCTest
@testable import TgWatchCore
final class ConnectionAlertTests: XCTestCase {
    let start = Date(timeIntervalSince1970: 2_000_000_000)
    func testDebounceDeduplicationAndConfirmedRecovery() {
        var policy = ConnectionAlertPolicy()
        XCTAssertNil(policy.observe(.down, at: start, epoch: 1))
        XCTAssertEqual(policy.observe(.down, at: start.addingTimeInterval(30), epoch: 1), .telegramLost)
        policy.confirmSubmission(.telegramLost)
        XCTAssertNil(policy.observe(.down, at: start.addingTimeInterval(60), epoch: 1))
        XCTAssertNil(policy.observe(.partial, at: start.addingTimeInterval(90), epoch: 1))
        XCTAssertNil(policy.observe(.ok, at: start.addingTimeInterval(120), epoch: 1))
        XCTAssertEqual(policy.observe(.ok, at: start.addingTimeInterval(150), epoch: 1), .recovered)
        policy.confirmSubmission(.recovered)
        XCTAssertNil(policy.observe(.ok, at: start.addingTimeInterval(180), epoch: 1))
    }
    func testUnknownGapRollbackAndEpochCannotCompleteConfirmation() {
        for change in ["unknown", "gap", "rollback", "epoch"] {
            var policy = ConnectionAlertPolicy()
            XCTAssertNil(policy.observe(.down, at: start, epoch: 1))
            if change == "unknown" { XCTAssertNil(policy.observe(.unknown, at: start.addingTimeInterval(10), epoch: 1)) }
            let at = change == "gap" ? start.addingTimeInterval(121) : change == "rollback" ? start.addingTimeInterval(-1) : start.addingTimeInterval(30)
            XCTAssertNil(policy.observe(.down, at: at, epoch: change == "epoch" ? 2 : 1), change)
        }
    }
    func testNoNetworkIsDistinctAndDuplicateTimestampCannotConfirm() {
        var policy = ConnectionAlertPolicy()
        XCTAssertNil(policy.observe(.noNetwork, at: start, epoch: 1))
        XCTAssertNil(policy.observe(.noNetwork, at: start, epoch: 1))
        XCTAssertEqual(policy.observe(.noNetwork, at: start.addingTimeInterval(30), epoch: 1), .networkLost)
        policy.reset()
        XCTAssertNil(policy.observe(.ok, at: start.addingTimeInterval(60), epoch: 2))
        XCTAssertNil(policy.observe(.ok, at: start.addingTimeInterval(90), epoch: 2))
    }
}
