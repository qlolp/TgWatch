import Foundation
public struct ProbeEndpoint: Sendable {
    public enum Group: Sendable { case mtproto, web, control }
    public let name, address: String
    public let group: Group
    public init(name: String, address: String, group: Group) { self.name = name; self.address = address; self.group = group }
    // Same embedded endpoint set as NetworkProbe/MtProtoEndpoints at 8932ef0.
    // Interleave lanes so a blocked IPv6 route cannot starve all web/control observations.
    public static let defaults: [ProbeEndpoint] = {
        var result = [ProbeEndpoint(name: "api.telegram.org", address: "https://api.telegram.org/", group: .web),
                      ProbeEndpoint(name: "Google 204", address: "https://www.gstatic.com/generate_204", group: .control)]
        let addresses = ["149.154.175.50", "2001:0b28:f23d:f001:0000:0000:0000:000a", "149.154.167.51", "2001:067c:04e8:f002:0000:0000:0000:000a", "149.154.175.100", "2001:0b28:f23d:f003:0000:0000:0000:000a", "149.154.167.91", "2001:067c:04e8:f004:0000:0000:0000:000a", "149.154.171.5", "2001:0b28:f23f:f005:0000:0000:0000:000a"]
        result.append(contentsOf: addresses.enumerated().map { ProbeEndpoint(name: "DC\($0.offset/2+1) IPv\($0.offset%2 == 0 ? 4 : 6)", address: $0.element, group: .mtproto) })
        result += [ProbeEndpoint(name: "web.telegram.org", address: "https://web.telegram.org/", group: .web),
                   ProbeEndpoint(name: "core.telegram.org", address: "https://core.telegram.org/", group: .web),
                   ProbeEndpoint(name: "Yandex", address: "https://ya.ru/", group: .control),
                   ProbeEndpoint(name: "Mail.ru", address: "https://mail.ru/", group: .control)]
        return result
    }()
}
public protocol ProbeTransport: Sendable {
    /// Must honor task cancellation and the monotonic deadline, including closing sockets.
    func check(_ endpoint: ProbeEndpoint, deadline: ContinuousClock.Instant) async throws -> Bool
}
public actor ProbeEngine: ProbeRunner {
    public enum Failure: Error { case busy }
    private let transport: any ProbeTransport
    private let endpoints: [ProbeEndpoint]
    private let budget: Duration
    private var busy = false
    public init(transport: any ProbeTransport, endpoints: [ProbeEndpoint] = ProbeEndpoint.defaults, budget: Duration = .seconds(6)) {
        self.transport = transport; self.endpoints = endpoints; self.budget = budget
    }
    public func check(epoch: UInt64) async throws -> ProbeSnapshot {
        guard !busy else { throw Failure.busy }
        busy = true; defer { busy = false }
        try Task.checkCancellation()
        let start = Date(), deadline = ContinuousClock.now.advanced(by: budget)
        let transport = self.transport, endpoints = self.endpoints
        let results: [Int: Bool] = await withTaskGroup(of: (Int, Bool?).self) { group in
            // Timer participates in the group but is not a connection.
            group.addTask { try? await Task.sleep(until: deadline, clock: .continuous); return (-1, nil) }
            func add(_ index: Int) {
                let endpoint = endpoints[index]
                group.addTask {
                    guard !Task.isCancelled, ContinuousClock.now < deadline else { return (index, nil) }
                    do { return (index, try await transport.check(endpoint, deadline: deadline)) }
                    catch is CancellationError { return (index, nil) }
                    catch { return (index, ContinuousClock.now < deadline && !Task.isCancelled ? false : nil) }
                }
            }
            var next = min(4, endpoints.count), remaining = next, values: [Int: Bool] = [:]
            for index in 0..<next { add(index) }
            if remaining == 0 { group.cancelAll(); return values }
            while let (index, result) = await group.next() {
                if index == -1 || Task.isCancelled { group.cancelAll(); break }
                remaining -= 1
                if let result { values[index] = result }
                if next < endpoints.count && ContinuousClock.now < deadline { add(next); next += 1; remaining += 1 }
                if remaining == 0 { group.cancelAll(); break }
            }
            return values
        }
        try Task.checkCancellation()
        var evidence = ProbeEvidence()
        evidence.primaryMeasurementsComplete = !endpoints.isEmpty && endpoints.indices.filter { endpoints[$0].group != .control }.allSatisfy { results[$0] != nil }
        for (i, confirmed) in results where confirmed {
            switch endpoints[i].group {
            case .web: evidence.webConfirmed = true
            case .mtproto: evidence.mtprotoConfirmed = true
            case .control: evidence.controlConfirmed = true
            }
        }
        return ProbeSnapshot(epoch: epoch, startedAt: start, finishedAt: Date(), evidence: evidence)
    }
}
