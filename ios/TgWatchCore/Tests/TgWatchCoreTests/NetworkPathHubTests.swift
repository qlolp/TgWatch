import Testing
@testable import TgWatchCore

@MainActor struct NetworkPathHubTests {
    @Test func replaysLatestAndDeliversRepeatedRouteEvents() {
        let hub = NetworkPathHub()
        var first: [NetworkPathState] = []
        let token = hub.subscribe { first.append($0) }
        #expect(first.isEmpty)
        hub.publish(unavailable: false, kind: "wifi")
        var second: [NetworkPathState] = []
        _ = hub.subscribe { second.append($0) }
        #expect(second == first)
        hub.publish(unavailable: false, kind: "wifi")
        #expect(first.count == 2)
        #expect(first[0].generation != first[1].generation)
        #expect(second == first)
        hub.unsubscribe(token)
        hub.publish(unavailable: true, kind: "wifi")
        #expect(first.count == 2)
        #expect(second.count == 3)
        #expect(second.last?.unavailable == true)
        #expect(second.last?.kind == "unavailable")
    }

    @Test func normalizesUnknownNetworkWithoutInventingConnectivity() {
        let hub = NetworkPathHub()
        var state: NetworkPathState?
        _ = hub.subscribe { state = $0 }
        hub.publish(unavailable: false, kind: "unrecognized")
        #expect(state?.kind == "unknown")
        #expect(state?.unavailable == false)
    }
}
