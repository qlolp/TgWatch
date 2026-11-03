import Foundation
import Network
import TgWatchCore

/// The single live path observer shared by service, exit and VPN measurements.
@MainActor final class NetworkPathObserver {
    static let shared = NetworkPathObserver()
    let hub = NetworkPathHub()
    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let unavailable = path.status == .unsatisfied
            let kind = Self.networkKind(of: path)
            Task { @MainActor [weak self] in
                self?.hub.publish(unavailable: unavailable, kind: kind)
            }
        }
        monitor.start(queue: DispatchQueue(label: "ru.tgwatch.shared-path"))
    }

    nonisolated private static func networkKind(of path: NWPath) -> String {
        if path.status == .unsatisfied { return NetworkKind.unavailable.rawValue }
        if path.usesInterfaceType(.wifi) { return NetworkKind.wifi.rawValue }
        if path.usesInterfaceType(.cellular) { return NetworkKind.cellular.rawValue }
        if path.usesInterfaceType(.wiredEthernet) { return NetworkKind.wired.rawValue }
        return NetworkKind.other.rawValue
    }

    deinit { monitor.cancel() }
}
