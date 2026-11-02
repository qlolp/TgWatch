import Foundation
import Testing
@testable import TgWatchCore

struct DiagnosisTests {
    @Test func staleNeverClaimsSuccess() {
        let sample = ProbeSnapshot(epoch: 0, startedAt: .now, finishedAt: .now, evidence: .init(mtprotoConfirmed: true, webConfirmed: true))
        #expect(ConnectionDiagnosis.reason(sample, fresh: false) == .stale)
        #expect(ConnectionDiagnosis.reason(sample, fresh: true) == .healthy)
    }
    @Test func distinguishesOfflineFailurePartialAndUncertainty() {
        for (status, expected): (ProbeStatus, ConnectionDiagnosis) in [(.down, .serviceDown), (.noNetwork, .offline), (.partial, .partial), (.unknown, .inconclusive)] {
            let sample = ProbeSnapshot(epoch: 0, startedAt: .now, finishedAt: .now, evidence: .init(), measuredStatus: status)
            #expect(ConnectionDiagnosis.reason(sample, fresh: true) == expected)
        }
    }
    @Test func comparisonDoesNotRankOfflineAsImprovement() {
        #expect(VPNComparison.outcome(before: .noNetwork, after: .ok) == .inconclusive)
        #expect(VPNComparison.outcome(before: .down, after: .ok) == .improved)
        #expect(VPNComparison.outcome(before: .ok, after: .partial) == .worsened)
        #expect(VPNComparison.outcome(before: .ok, after: .ok) == .unchanged)
    }
}
