import Foundation
/// Unauthenticated resPQ reachability only; never account access or server authentication.
public enum MTProtoCodec {
    public enum Invalid: Error { case nonce, frame }
    public static func makeRequest(nonce: Data, now: Date = Date()) throws -> Data {
        guard nonce.count == 16 else { throw Invalid.nonce }
        let seconds = max(0, min(now.timeIntervalSince1970, Double(UInt32.max)))
        let whole = UInt64(seconds)
        let fraction = UInt64((seconds - Double(whole)) * 4_294_967_296) & ~3
        var data = Data(repeating: 0, count: 8)
        append(whole << 32 | fraction, bytes: 8, to: &data)
        append(20, bytes: 4, to: &data); append(0xbe7e8ef1, bytes: 4, to: &data)
        data.append(nonce); return data
    }
    private static func append(_ value: UInt64, bytes: Int, to data: inout Data) {
        for i in 0..<bytes { data.append(UInt8(truncatingIfNeeded: value >> (i * 8))) }
    }
    public static func validateResponse(_ data: Data, nonce: Data) -> Bool {
        guard (76...4096).contains(data.count), nonce.count == 16 else { return false }
        let b = [UInt8](data)
        func integer(_ offset: Int, _ count: Int = 4) -> UInt64 {
            (0..<count).reduce(0) { $0 | UInt64(b[offset + $1]) << ($1 * 8) }
        }
        let id = integer(8, 8)
        guard integer(0,8) == 0, id > 0, id <= UInt64(Int64.max), id & 1 == 1,
              integer(16) == b.count - 20, (b.count - 20) % 4 == 0,
              integer(20) == 0x05162463, Data(b[24..<40]) == nonce else { return false }
        let pq = Int(b[56]); guard (1...8).contains(pq) else { return false }
        let padding = (4 - (pq + 1) % 4) % 4, vector = 57 + pq + padding
        guard vector + 8 <= b.count, b[(57+pq)..<vector].allSatisfy({ $0 == 0 }),
              integer(vector) == 0x1cb5c415 else { return false }
        let count = integer(vector + 4), remaining = b.count - vector - 8
        return count > 0 && remaining % 8 == 0 && count == remaining / 8
    }
    public static func frameLength(_ data: Data) throws -> Int {
        let b = [UInt8](data); guard let first = b.first, first <= 127 else { throw Invalid.frame }
        let words: Int
        if first == 127 {
            guard b.count == 4 else { throw Invalid.frame }
            words = Int(b[1]) | Int(b[2]) << 8 | Int(b[3]) << 16
            guard words >= 127 else { throw Invalid.frame }
        } else { words = Int(first) }
        guard (19...1024).contains(words) else { throw Invalid.frame }
        return words * 4
    }
}
