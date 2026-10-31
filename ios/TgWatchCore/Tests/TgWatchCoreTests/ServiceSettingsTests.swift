import XCTest
@testable import TgWatchCore
final class ServiceSettingsTests: XCTestCase {
    func testMissingFileMigratesPreviousSessionOnlyVersionToSafeDefaults() throws {
        let folder=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        let url=folder.appendingPathComponent("settings.json"), store=ServiceSettingsStore(url:folder.appendingPathComponent("settings.json"))
        XCTAssertEqual(try store.load(),.defaults)
        XCTAssertFalse(FileManager.default.fileExists(atPath:url.path))
    }
    func testValidatedSettingsSurviveNewStoreInstance() throws {
        let folder=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        let url=folder.appendingPathComponent("settings.json")
        let desired=ServiceSettings(target:.whatsapp,intervalSeconds:300,automatic:true)
        try ServiceSettingsStore(url:url).save(desired)
        XCTAssertEqual(try ServiceSettingsStore(url:url).load(),desired)
    }
    func testMalformedAmbiguousAndFutureSettingsArePreservedUntilExplicitReset() throws {
        let folder=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
        let url=folder.appendingPathComponent("settings.json"), store=ServiceSettingsStore(url:folder.appendingPathComponent("settings.json"))
        let valid=#"{"schemaVersion":1,"target":"matrix","intervalSeconds":60,"automatic":false}"#
        let inputs=[Data("{broken".utf8),Data([0xff]),Data(valid.replacingOccurrences(of:"\"automatic\":false",with:"\"unused\":false").utf8),Data(valid.replacingOccurrences(of:"false",with:"null").utf8),
                    Data(valid.replacingOccurrences(of:"matrix",with:"telegram").utf8),
                    Data(valid.replacingOccurrences(of:":60",with:":0").utf8),
                    Data(valid.replacingOccurrences(of:"Version\":1",with:"Version\":2").utf8),
                    Data(valid.replacingOccurrences(of:"false",with:"false,\"automatic\":true").utf8),
                    Data(valid.replacingOccurrences(of:"false",with:"false,\"extra\":1").utf8),
                    Data(repeating:65,count:1025)]
        for bytes in inputs {
            try bytes.write(to:url)
            XCTAssertThrowsError(try store.load())
            XCTAssertThrowsError(try store.save(.defaults))
            XCTAssertEqual(try Data(contentsOf:url),bytes)
            try store.reset(); XCTAssertEqual(try store.load(),.defaults)
        }
    }
    func testInvalidWriteAndIOFailureCannotReplacePriorSettings() throws {
        let folder=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        let url=folder.appendingPathComponent("settings.json"), store=ServiceSettingsStore(url:folder.appendingPathComponent("settings.json"))
        try store.save(.defaults); let bytes=try Data(contentsOf:url)
        XCTAssertThrowsError(try store.save(.init(target:.telegram,intervalSeconds:1,automatic:true)))
        XCTAssertEqual(try Data(contentsOf:url),bytes)
        let blocked=folder.appendingPathComponent("file"); try Data([1]).write(to:blocked)
        XCTAssertThrowsError(try ServiceSettingsStore(url:blocked.appendingPathComponent("settings.json")).save(.defaults))
        XCTAssertEqual(try Data(contentsOf:blocked),Data([1]))
    }
}
private actor SettingsTransport: ServiceTransport {
    private var calls=0
    func probe(target: ServiceTarget, deadline: ContinuousClock.Instant) async throws -> ServiceEvidence {
        calls += 1; return .init(target:target,outcome:target == .matrix ? .matrixVersions : .httpResponse)
    }
    func control(deadline: ContinuousClock.Instant) async throws -> Bool { true }
    func count() -> Int { calls }
}
extension ServiceSettingsTests {
    @MainActor func testRestoredAutomaticModeCannotRunHiddenOrRestoreFreshResult() async throws {
        let transport=SettingsTransport()
        let subject=SelectedServices(transport:transport,settings:.init(target:.whatsapp,intervalSeconds:300,automatic:true))
        XCTAssertEqual(subject.target,.whatsapp); XCTAssertEqual(subject.intervalSeconds,300)
        XCTAssertTrue(subject.session.monitoring); XCTAssertNil(subject.session.snapshot)
        subject.setActive(true)
        try await Task.sleep(for:.milliseconds(20)); let hidden=await transport.count(); XCTAssertEqual(hidden,0)
        subject.setVisible(true)
        let deadline=ContinuousClock.now.advanced(by:.seconds(2))
        while subject.session.snapshot == nil && ContinuousClock.now < deadline { await Task.yield() }
        XCTAssertEqual(subject.session.snapshot?.evidence.service?.target,.whatsapp)
        subject.setActive(false); XCTAssertNil(subject.session.snapshot)
    }
    @MainActor func testOnlyExplicitValidSettingChangesEmitPersistence() {
        let subject=SelectedServices(transport:SettingsTransport())
        var saved:[ServiceSettings]=[]; subject.onSettingsChange = { saved.append($0) }
        subject.setActive(true); subject.networkChanged(unavailable:true); subject.clockChanged()
        subject.select(.telegram); subject.setInterval(0)
        XCTAssertTrue(saved.isEmpty)
        subject.select(.whatsapp); subject.setInterval(120); subject.setAutomatic(true)
        XCTAssertEqual(saved.last,.init(target:.whatsapp,intervalSeconds:120,automatic:true))
        subject.resetSettings(); XCTAssertEqual(saved.last,.defaults)
        XCTAssertFalse(subject.session.monitoring)
    }
}
