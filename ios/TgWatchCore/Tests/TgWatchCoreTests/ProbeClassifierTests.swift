import XCTest
@testable import TgWatchCore
final class ProbeClassifierTests: XCTestCase {
    func testTruthTableAndIncompleteMeasurements() {
        for mask in 0..<32 {
            let mt = mask & 1 != 0, web = mask & 2 != 0, control = mask & 4 != 0
            let offline = mask & 8 != 0, complete = mask & 16 != 0
            let expected: ProbeStatus
            switch (offline, mt, web, complete, control) {
            case (true, _, _, _, _): expected = .noNetwork
            case (false, true, true, _, _): expected = .ok
            case (false, true, false, _, _), (false, false, true, _, _): expected = .partial
            case (false, false, false, true, true): expected = .down
            default: expected = .unknown
            }
            XCTAssertEqual(classify(ProbeEvidence(mtprotoConfirmed: mt, webConfirmed: web,
                controlConfirmed: control, pathUnavailable: offline, primaryMeasurementsComplete: complete)), expected, "mask \(mask)")
        }
    }
}
