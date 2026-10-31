import Foundation
@MainActor public protocol AlertScheduler: AnyObject {
    func add(id: String, event: ConnectionAlert, observedAt: Date) async throws
    func remove(id: String)
}
/// Owns delayed notification submissions across lifecycle/route/preference changes.
@MainActor public final class AlertDelivery {
    private let scheduler: any AlertScheduler
    private var currentID: String?
    private var eligible: (() -> Bool)?
    public var onFailure: (() -> Void)?
    public var onSubmitted: ((ConnectionAlert) -> Void)?
    public init(scheduler: any AlertScheduler) { self.scheduler = scheduler }
    public func invalidate() {
        if let currentID { scheduler.remove(id: currentID) }
        currentID = nil; eligible = nil
    }
    public func canPresent(id: String) -> Bool { currentID == id && eligible?() == true }
    public func submit(event: ConnectionAlert, observedAt: Date, eligible: @escaping () -> Bool) {
        invalidate()
        guard eligible() else { return }
        let id = "tgwatch.connection." + UUID().uuidString
        currentID = id; self.eligible = eligible
        Task { [weak self, scheduler] in
            guard let self, self.canPresent(id: id) else { return }
            do {
                try await scheduler.add(id: id, event: event, observedAt: observedAt)
                if !self.canPresent(id: id) { scheduler.remove(id: id) }
                else { self.onSubmitted?(event) }
            } catch {
                if self.currentID == id { self.invalidate(); self.onFailure?() }
            }
        }
    }
}
