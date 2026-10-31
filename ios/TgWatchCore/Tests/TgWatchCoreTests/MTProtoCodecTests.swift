import XCTest
@testable import TgWatchCore
final class MTProtoCodecTests: XCTestCase {
    let nonce = Data(0..<16)
    // Literal resPQ from JVM MtProtoFixtures at 8932ef0. No parser computes expectations.
    static let hex = "000000000000000001000000000000004000000063241605000102030405060708090a0b0c0d0e0f28292a2b2c2d2e2f30313233343536370817ed48941a08f98100000015c4b51c01000000216be86c022bb4c3"
    var valid: Data { Self.responseBytes }
    static var responseBytes: Data { Data(stride(from: 0, to: hex.count, by: 2).map { i in
        let a = hex.index(hex.startIndex, offsetBy: i); let b = hex.index(a, offsetBy: 2)
        return UInt8(hex[a..<b], radix: 16)!
    }) }
    func testCompleteResponseAndNotificationID() {
        XCTAssertTrue(MTProtoCodec.validateResponse(valid, nonce: nonce))
        var notification = valid; notification[8] = 3
        XCTAssertTrue(MTProtoCodec.validateResponse(notification, nonce: nonce))
    }
    func testRejectsEveryTruncationAndCorruptFields() {
        for n in 0..<valid.count { XCTAssertFalse(MTProtoCodec.validateResponse(valid.prefix(n), nonce: nonce)) }
        for (offset, value): (Int, UInt8) in [(0,1),(8,0),(8,2),(15,255),(16,0),(20,0),(24,99),(56,0),(56,9),(56,254),(56,255),(65,1),(67,1),(68,0),(72,0),(72,2),(75,127)] {
            var bad = valid; bad[offset] = value
            XCTAssertFalse(MTProtoCodec.validateResponse(bad, nonce: nonce), "offset \(offset)")
        }
        XCTAssertFalse(MTProtoCodec.validateResponse(valid, nonce: nonce.dropLast()))
        XCTAssertFalse(MTProtoCodec.validateResponse(Data(repeating: 0, count: 4097), nonce: nonce))
    }
    func testMaximumFingerprintVectorAndTrailingData() {
        // JVM fixture: four-byte pq + canonical padding + 503 fingerprints = 4096 bytes.
        var max = Data(valid.prefix(56))
        max.append(contentsOf: [4,1,1,1,1,0,0,0,21,196,181,28,247,1,0,0])
        max.append(Data(repeating: 1, count: 503*8))
        max[16] = 236; max[17] = 15
        XCTAssertEqual(max.count, 4096)
        XCTAssertTrue(MTProtoCodec.validateResponse(max, nonce: nonce))
        max.append(0); XCTAssertFalse(MTProtoCodec.validateResponse(max, nonce: nonce))
        var trailing = valid + Data(repeating: 0, count: 4); trailing[16] = 68
        XCTAssertFalse(MTProtoCodec.validateResponse(trailing, nonce: nonce))
    }
    func testRequestIsUnauthenticatedAndRequiresNonce() throws {
        let request = try MTProtoCodec.makeRequest(nonce: nonce, now: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(request.count, 40)
        XCTAssertEqual(Array(request.prefix(24)), [0,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,20,0,0,0,241,142,126,190])
        XCTAssertEqual(request.suffix(16), nonce)
        XCTAssertThrowsError(try MTProtoCodec.makeRequest(nonce: Data()))
    }
    func testFrameRejectsReservedAndNonCanonicalLengths() throws {
        XCTAssertEqual(try MTProtoCodec.frameLength(Data([21])), 84)
        XCTAssertEqual(try MTProtoCodec.frameLength(Data([127,127,0,0])), 508)
        XCTAssertEqual(try MTProtoCodec.frameLength(Data([127,0,4,0])), 4096)
        for header in [[0],[18],[128],[255],[127,21,0,0],[127,1,4,0]] {
            XCTAssertThrowsError(try MTProtoCodec.frameLength(Data(header.map(UInt8.init))))
        }
    }
}
