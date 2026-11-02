import Foundation
import WatchConnectivity
import SwiftUI
import Observation

@MainActor
@Observable
final class WatchSessionManager: NSObject, WCSessionDelegate {
    var snapshot: CompanionSnapshot?
    var isReachable = false
    var isRefreshing = false
    var statusMessage: String?

    override init() {
        super.init()
        if WCSession.isSupported() {
            let session = WCSession.default
            session.delegate = self
            session.activate()
        }
    }

    func requestRefresh() {
        guard WCSession.isSupported() else {
            statusMessage = "WatchConnectivity unsupported"
            return
        }

        let session = WCSession.default
        guard session.activationState == .activated else {
            statusMessage = "Session activating..."
            return
        }

        guard session.isReachable else {
            statusMessage = "iPhone unreachable. Open app on iPhone."
            return
        }

        isRefreshing = true
        statusMessage = nil

        session.sendMessage(["action": "refresh"]) { [weak self] reply in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isRefreshing = false
                if let data = reply["snapshot"] as? Data,
                   let decoded = try? JSONDecoder().decode(CompanionSnapshot.self, from: data) {
                    self.snapshot = decoded
                    self.statusMessage = nil
                }
            }
        } errorHandler: { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isRefreshing = false
                self.statusMessage = "Refresh failed: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - WCSessionDelegate

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        Task { @MainActor in
            self.isReachable = session.isReachable
            if let data = session.receivedApplicationContext["snapshot"] as? Data,
               let decoded = try? JSONDecoder().decode(CompanionSnapshot.self, from: data) {
                self.snapshot = decoded
            }
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in
            self.isReachable = session.isReachable
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String : Any]) {
        if let data = applicationContext["snapshot"] as? Data,
           let decoded = try? JSONDecoder().decode(CompanionSnapshot.self, from: data) {
            Task { @MainActor in
                self.snapshot = decoded
            }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String : Any]) {
        if let data = message["snapshot"] as? Data,
           let decoded = try? JSONDecoder().decode(CompanionSnapshot.self, from: data) {
            Task { @MainActor in
                self.snapshot = decoded
            }
        }
    }
}
