import Foundation
import Testing
@testable import TgWatchCore

struct AlertAndOutageTests {
    @Test func slowAlertRequiresConsecutiveChecksAndHysteresis() {
        var alert = SlowResponseAlert()
        func check(_ latency: Double?, _ time: Double) -> Bool {
            alert.observe(status: .ok, latency: latency, threshold: 1000, uptime: time)
        }
        #expect(!check(1000, 0))
        #expect(!check(nil, 10))
        #expect(!check(1100, 20))
        #expect(!check(1100, 30))
        #expect(check(1100, 40))
        #expect(!check(800, 1900))
        #expect(!check(1100, 1910))
        #expect(!check(1100, 1920))
        #expect(!check(1100, 1930))
        #expect(!check(500, 1940))
        #expect(!check(1100, 1950))
        #expect(!check(1100, 1960))
        #expect(check(1100, 1970))
    }

    @Test func gapsAndSettingsResetStreakButPreserveCooldown() {
        var alert = SlowResponseAlert()
        func observe(status: ProbeStatus, latency: Double?, threshold: Double?, uptime: Double) -> Bool {
            alert.observe(status: status, latency: latency, threshold: threshold, uptime: uptime)
        }
        for time in [0.0, 10] { #expect(!(observe(status: .ok, latency: 1500, threshold: 1000, uptime: time))) }
        alert.resetContinuity()
        #expect(!(observe(status: .ok, latency: 1500, threshold: 1000, uptime: 20)))
        #expect(!(observe(status: .ok, latency: 1500, threshold: 1000, uptime: 30)))
        #expect((observe(status: .ok, latency: 1500, threshold: 1000, uptime: 40)))
        alert.resetContinuity()
        for time in [50.0, 60, 70] { #expect(!(observe(status: .ok, latency: 1500, threshold: 1000, uptime: time))) }
        #expect(!(observe(status: .ok, latency: 1500, threshold: nil, uptime: 1800)))
        #expect(!(observe(status: .ok, latency: 1500, threshold: 1000, uptime: 1840)))
        #expect(!(observe(status: .unknown, latency: 1500, threshold: 1000, uptime: 1850)))
        #expect(!(observe(status: .ok, latency: .nan, threshold: 1000, uptime: 1860)))
        #expect(!(observe(status: .ok, latency: 1500, threshold: 1000, uptime: 1870)))
        #expect(!(observe(status: .ok, latency: 1500, threshold: 1000, uptime: 1880)))
        #expect((observe(status: .ok, latency: 1500, threshold: 1000, uptime: 1890)))
    }

    private func entry(_ second: Double, _ status: ProbeStatus) -> HistoryEntry {
        HistoryEntry(epoch: 0, observedAt: Date(timeIntervalSince1970: second), status: status, validForSeconds: 15)
    }
    private func report(_ entries: [HistoryEntry], from: Double = 0, to: Double = 60) -> HistoryAnalytics {
        HistoryAnalytics(entries: entries, from: Date(timeIntervalSince1970: from), to: Date(timeIntervalSince1970: to))
    }

    @Test func partialContinuesOutageAndCompleteRecoveryCountsInMean() {
        let value = report([entry(0, .ok), entry(10, .down), entry(20, .partial), entry(30, .ok)])
        #expect(value.outageEpisodes.count == 1)
        #expect(value.outageEpisodes.first?.duration == 20)
        #expect(value.outageEpisodes.first?.ending == .recovered)
        #expect(value.meanTimeToRecovery == 20)
    }

    @Test func gapsOfflineAndClippedStartsNeverImplyMeasuredRecovery() {
        let value = report([entry(0, .down), entry(10, .ok), entry(20, .down), entry(30, .noNetwork), entry(40, .down), entry(50, .ok)])
        #expect(value.outageEpisodes.count == 3)
        #expect(value.outageEpisodes[1].ending == .interrupted)
        #expect(value.meanTimeToRecovery == nil)
        let gap = report([entry(0, .ok), entry(10, .down), entry(40, .ok)])
        #expect(gap.outageEpisodes.first?.ending == .interrupted)
        #expect(gap.outageEpisodes.first?.duration == 15)
        #expect(gap.meanTimeToRecovery == nil)
        let clipped = report([entry(0, .ok), entry(10, .down), entry(20, .ok)], from: 15)
        #expect(clipped.meanTimeToRecovery == nil)
        let partialBoundary = report([entry(0, .ok), entry(10, .down), entry(20, .partial), entry(30, .down), entry(40, .ok)], from: 25)
        #expect(partialBoundary.outageEpisodes.count == 1)
        #expect(partialBoundary.outageEpisodes.first?.duration == 15)
        #expect(partialBoundary.meanTimeToRecovery == nil)
    }

    @Test func clockRollbackAndPendingRecovery() {
        let value = report([entry(0, .ok), entry(10, .down), entry(20, .ok), entry(5, .down)], to: 15)
        #expect(value.outageEpisodes.count == 1)
        #expect(value.outageEpisodes.first?.ending == .ongoing)
        #expect(value.meanTimeToRecovery == nil)
    }
}
