import Foundation
import Testing
@testable import TgWatchCore

struct ParityCompletionTests {
    let now = Date(timeIntervalSince1970: 1_700_000_000)

    @Test func agedChecksBecomeMinuteThenFiveMinuteBuckets() {
        let recent = HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-3600), status: .ok, latencyMilliseconds: 20)
        let week = HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-10 * 86_400), status: .down)
        let quarter = HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-40 * 86_400), status: .partial)
        let ancient = HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-100 * 86_400), status: .ok)
        let rolled = HistoryRollup.roll(entries: [recent, week, quarter, ancient], now: now)
        #expect(rolled.raw.map(\.status) == [.ok])
        #expect(rolled.minutes.count == 1)
        #expect(rolled.minutes[0].fail == 1)
        #expect(rolled.minutes[0].resolution == HistoryRollup.minute)
        #expect(rolled.coarse.count == 1)
        #expect(rolled.coarse[0].partial == 1)
        #expect(rolled.coarse[0].resolution == HistoryRollup.coarse)
        let again = HistoryRollup.roll(entries: rolled.raw, minutes: rolled.minutes, coarse: rolled.coarse, now: now)
        #expect(again.minutes[0].fail == 1)
        #expect(again.coarse[0].partial == 1)
    }

    @Test func clockRollbackDropsPreviousBuckets() {
        let future = HistoryEntry(epoch: 1, observedAt: now, status: .ok)
        let past = HistoryEntry(epoch: 2, observedAt: now.addingTimeInterval(-40 * 86_400), status: .down)
        let kept = HistoryBucket(start: HistoryRollup.align(now.addingTimeInterval(-86_400), resolution: 60), resolution: 60, ok: 4)
        let rolled = HistoryRollup.roll(entries: [future, past], minutes: [kept], now: now)
        #expect(rolled.raw.isEmpty)
        #expect(rolled.minutes.isEmpty)
        #expect(rolled.coarse.count == 1)
        #expect(rolled.coarse[0].fail == 1)
    }

    @Test func maintenanceSkipsEqualEndpointsAndOvernight() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        // 2023-11-14 22:13:20 UTC is a Tuesday. Monday is day 0.
        let tuesday = Date(timeIntervalSince1970: 1_700_000_000)
        let overnight = MaintenanceWindow(id: "night", startMinuteOfDay: 23 * 60, endMinuteOfDay: 7 * 60, daysMask: 1 << 1)
        #expect(MaintenanceSchedule.active([overnight], at: tuesday, calendar: calendar) == false)
        let evening = calendar.date(bySettingHour: 23, minute: 15, second: 0, of: tuesday)!
        #expect(MaintenanceSchedule.active([overnight], at: evening, calendar: calendar))
        let noon = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: tuesday)!
        #expect(!MaintenanceSchedule.active([overnight], at: noon, calendar: calendar))
        let disabled = MaintenanceWindow(id: "off", startMinuteOfDay: 10, endMinuteOfDay: 10)
        #expect(!MaintenanceSchedule.active([disabled], at: evening, calendar: calendar))
        #expect(MaintenanceSchedule.sanitized([overnight, overnight]) == nil)
    }

    @Test func dailySummaryOmitsEmptyDaysAndSchedulesTomorrow() {
        let empty = HistoryAnalytics(entries: [], from: now.addingTimeInterval(-86400), to: now)
        #expect(DailySummary.stats(analytics: empty, latencies: []) == nil)
        let entries = [HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-120), status: .down, validForSeconds: 60, latencyMilliseconds: nil),
                       HistoryEntry(epoch: 1, observedAt: now.addingTimeInterval(-60), status: .ok, validForSeconds: 60, latencyMilliseconds: 80)]
        let analytics = HistoryAnalytics(entries: entries, from: now.addingTimeInterval(-86400), to: now)
        let stats = DailySummary.stats(analytics: analytics, latencies: [80])
        #expect(stats?.checks == 2)
        #expect(stats?.averageLatencyMilliseconds == 80)
        #expect(DailySummary.due(force: false, optedIn: true, monitoring: true, deliveredToday: true) == false)
        #expect(DailySummary.due(force: true, optedIn: false, monitoring: false, deliveredToday: true))
        #expect(DailySummary.timeLabel(21 * 60) == "21:00")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let next = DailySummary.nextDate(after: now, minuteOfDay: 0, calendar: calendar)
        #expect(next > now)
    }

    @Test func ntfyPresetAndGroupedCsv() throws {
        let anonymous = NtfyPreset.url(server: nil, topic: "tg_watch", token: nil)
        #expect(anonymous == "https://ntfy.sh/tg_watch")
        #expect(WebhookAddress.isValid(anonymous!))
        let authed = NtfyPreset.url(server: "https://ntfy.example", topic: "ops", token: "tk_abc")
        #expect(authed?.contains("auth=") == true)
        #expect(NtfyPreset.url(server: "https://evil.example/hook", topic: "ops", token: nil) == nil)
        #expect(NtfyPreset.url(server: nil, topic: "bad topic", token: nil) == nil)
        let row = "2023-11-14T22:13:20.000Z,2023-11-14T22:14:20.000Z,OK,60000,10,0,true"
        let csv = HistoryCSV.header + "\n" + row + ",matrix,1\n" + row + ",telegram,1\n"
        let grouped = try HistoryCSV.parseGrouped(Data(csv.utf8))
        #expect(Set(grouped.keys) == ["matrix", "telegram"])
        #expect(grouped["matrix"]?.first?.latencyMilliseconds == 10)
        let mixed = HistoryCSV.header + "\n" + row + ",matrix,1\n" + row + ",matrix,2\n"
        #expect(throws: HistoryCSV.Failure.self) { try HistoryCSV.parseGrouped(Data(mixed.utf8)) }
    }

    @Test func networkBreakdownIgnoresUnknownLabels() {
        let wifi = HistoryEntry(epoch: 1, observedAt: now, status: .ok, networkKind: "wifi")
        let blank = HistoryEntry(epoch: 1, observedAt: now, status: .ok, networkKind: "carrier")
        #expect(wifi.networkKind == "wifi")
        #expect(blank.networkKind == nil)
        let counts = NetworkBreakdown(entries: [wifi, blank]).counts
        #expect(counts["wifi"] == 1)
        #expect(counts["unknown"] == 1)
    }
}
