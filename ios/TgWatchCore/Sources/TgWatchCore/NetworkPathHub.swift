import Foundation

public struct NetworkPathState: Sendable, Equatable {
    public let unavailable: Bool
    public let kind: String
    public let generation: UInt64
}

/// Every route event matters, including events with unchanged interface names.
@MainActor public final class NetworkPathHub {
    private var receivers: [UUID: (NetworkPathState) -> Void] = [:]
    private var latest: NetworkPathState?
    private var generation: UInt64 = 0

    public init() {}

    public func subscribe(_ receive: @escaping (NetworkPathState) -> Void) -> UUID {
        let id = UUID()
        receivers[id] = receive
        if let latest { receive(latest) }
        return id
    }

    public func unsubscribe(_ id: UUID) { receivers.removeValue(forKey: id) }

    public func publish(unavailable: Bool, kind: String) {
        generation &+= 1
        let state = NetworkPathState(unavailable: unavailable,
            kind: unavailable ? NetworkKind.unavailable.rawValue : (NetworkKind.normalize(kind) ?? NetworkKind.unknown.rawValue),
            generation: generation)
        latest = state
        for receive in Array(receivers.values) { receive(state) }
    }
}
