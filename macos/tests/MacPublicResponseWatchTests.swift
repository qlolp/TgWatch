import Foundation

@main struct MacPublicResponseWatchTests {
    static var assertions = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) {
        assertions += 1
        if !value() { FileHandle.standardError.write(Data((message + "\n").utf8)); exit(1) }
    }
    static let context = "0123456789abcdef0123456789abcdef"
    static func reading(_ status: RegionalMenuStatus = .webReachable, at: Double, receipt: Double,
                        context: String? = MacPublicResponseWatchTests.context, confirmed: Bool = false) -> RegionalMenuReading {
        RegionalMenuReading(service: .gemini, status: status, httpCode: 200, country: "DE", checkedAt: at,
                            regionConfirmed: confirmed, contextID: context, receivedUptime: receipt)
    }
    static func armed() -> MacPublicResponseWatch {
        MacPublicResponseWatch.arm(reading(.regionRestricted, at: 900, receipt: 0.9, confirmed: true),
                                   now: 1_000, uptime: 1)!
    }
    static func main() {
        expect(MacPublicResponseWatch.arm(reading(at: 900, receipt: 0.9), now: 1_000, uptime: 1) == nil,
               "Public response is not a confirmed refusal")
        expect(MacPublicResponseWatch.arm(reading(.regionRestricted, at: 900, receipt: 0.9),
                                         now: 1_000, uptime: 1) == nil, "Unconfirmed refusal cannot arm")
        var watch = armed()
        let first = reading(at: 2_000, receipt: 2)
        watch.observe(first, generation: 1, now: 2_000, uptime: 2)
        watch.observe(first, generation: 1, now: 17_000, uptime: 17)
        expect(!watch.ready(context: context, generation: 1, now: 17_000, uptime: 17), "Duplicate cannot advance")
        watch.observe(reading(at: 17_000, receipt: 17), generation: 1, now: 17_000, uptime: 17)
        expect(watch.ready(context: context, generation: 1, now: 17_000, uptime: 17), "Two fresh responses")
        expect(watch.ready(context: context, generation: 1, now: 18_000, uptime: 18), "Blocked delivery stays ready")
        expect(!watch.ready(context: context, generation: 2, now: 18_000, uptime: 18), "Replaced process")
        expect(!watch.ready(context: "abcdef0123456789abcdef0123456789", generation: 1,
                            now: 18_000, uptime: 18), "Replaced connection")
        watch.observe(reading(at: 18_000, receipt: 18), generation: 2, now: 18_000, uptime: 18)
        expect(!watch.ready(context: context, generation: 2, now: 18_000, uptime: 18), "New process starts a new pair")
        watch.observe(reading(at: 33_000, receipt: 33), generation: 2, now: 33_000, uptime: 33)
        expect(watch.ready(context: context, generation: 2, now: 33_000, uptime: 33), "New process independently confirms")
        watch.resetEvidence()
        expect(!watch.ready(context: context, generation: 2, now: 33_000, uptime: 33), "Stop clears positive evidence")
        for status in [RegionalMenuStatus.unknown, .authRequired, .challenge, .rateLimited, .networkError, .regionRestricted] {
            var item = armed()
            item.observe(first, generation: 1, now: 2_000, uptime: 2)
            item.observe(reading(status, at: 17_000, receipt: 17), generation: 1, now: 17_000, uptime: 17)
            expect(!item.ready(context: context, generation: 1, now: 17_000, uptime: 17), "No \(status) recovery")
        }
        var stale = armed()
        stale.observe(first, generation: 1, now: 2_000, uptime: 2)
        stale.observe(reading(at: 605_000, receipt: 605), generation: 1, now: 605_000, uptime: 605)
        expect(!stale.ready(context: context, generation: 1, now: 605_000, uptime: 605), "Both observations must be fresh")
        stale.observe(reading(at: 620_000, receipt: 620), generation: 1, now: 620_000, uptime: 620)
        expect(stale.ready(context: context, generation: 1, now: 620_000, uptime: 620), "Fresh replacement pair")
        expect(!stale.ready(context: context, generation: 1, now: 1_221_000, uptime: 1221), "Stale proof cannot deliver")
        var clock = armed()
        clock.observe(first, generation: 1, now: 2_000, uptime: 2)
        clock.observe(reading(at: 1_900, receipt: 1.9), generation: 1, now: 1_900, uptime: 1.9)
        expect(!clock.active(now: 3_000, uptime: 3), "Clock reversal ends a live goal")
        expect(!armed().active(now: 86_401_000, uptime: 86401), "24h expiry")
        let intent = armed().intent
        let data = MacWatchIntentCodec.encode([intent])!
        let text = String(data: data, encoding: .utf8)!
        expect(!text.contains(context) && !text.contains("positive") && !text.contains("generation"), "Intent-only persistence")
        let restored = MacWatchIntentCodec.decode(data, now: 10_000)
        expect(restored == [intent], "Intent retained")
        let restart = MacPublicResponseWatch(intent: restored[0], now: 10_000, uptime: 10)!
        expect(!restart.ready(context: context, generation: 1, now: 17_000, uptime: 17), "Restart has no old positive proof")
        expect(MacWatchIntentCodec.decode(data, now: intent.deadline).isEmpty, "Expired intent not restored")
        expect(MacWatchIntentCodec.decode(Data(repeating: 32, count: 4_097), now: 10_000).isEmpty, "Bounded preferences")
        expect(MacWatchIntentCodec.encode([intent, intent]) == nil, "One fixed intent per service")
        let fence = MacConnectionFence()
        let captured = fence.capture()
        fence.changed()
        expect(!fence.matches(captured), "A network event fences callbacks before the main queue catches up")
        print("MacPublicResponseWatch: \(assertions) assertions passed")
    }
}
