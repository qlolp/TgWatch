import Foundation
import Testing
@testable import TgWatchCore

enum BackupTestFixture {
    static func payload(definitions: [ServiceDefinition] = [.telegram], history: [HistoryEntry] = []) throws -> EncryptedBackupPayload {
        var files = ["services-v1.json": try BackupContents.encodedManifest(definitions),
                     "favorites.json": try JSONEncoder().encode(Set(["telegram"]))]
        for definition in definitions {
            let prefix = BackupContents.prefix(for: definition)
            for (name, data) in try BackupContents.defaultDirectoryFiles() { files[prefix + name] = data }
        }
        files["history-v1.json"] = try JSONEncoder().encode(history)
        return replacing(try base(files), files: files)
    }
    private static func base(_ files: [String: Data]) throws -> EncryptedBackupPayload {
        let result = try BackupContents.validateFiles(files)
        return .init(preview: .init(createdAt: Date(timeIntervalSince1970: 1_700_000_000), appVersion: "0.1.0",
            serviceCount: result.definitions.count, serviceNames: result.definitions.map(\.id),
            totalHistoryEntries: result.historyEntries), files: files)
    }
    static func replacing(_ payload: EncryptedBackupPayload, files: [String: Data]) -> EncryptedBackupPayload {
        .init(preview: payload.preview, files: files)
    }
    static func temporaryRoot() throws -> (parent: URL, root: URL) {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("tgwatch-restore-test-\(UUID().uuidString)")
        let root = parent.appendingPathComponent("TgWatch")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data("original-user-data".utf8).write(to: root.appendingPathComponent("sentinel.txt"))
        return (parent, root)
    }
}

@Suite struct BackupRestoreTests {
    @Test func rejectsEmptyMalformedAndIncompleteArchives() throws {
        let payload = try BackupTestFixture.payload()
        var malformed = payload.files; malformed["services-v1.json"] = Data("not-json".utf8)
        var missing = payload.files; missing.removeValue(forKey: "history-v1.json")
        var favorites = payload.files; favorites["favorites.json"] = Data("[\"missing-service\"]".utf8)
        for files in [[:], malformed, missing, favorites] {
            #expect(throws: (any Error).self) { try BackupContents.validate(BackupTestFixture.replacing(payload, files: files)) }
        }
    }

    @Test func everyCurrentTargetRequiresCompleteFiles() throws {
        let custom = ServiceDefinition(id: "custom", kind: .custom, address: "https://example.test/health")
        let payload = try BackupTestFixture.payload(definitions: [.telegram, custom])
        #expect(try BackupContents.validate(payload).definitions.count == 2)
        for name in BackupContents.directoryFiles {
            var files = payload.files; files.removeValue(forKey: BackupContents.prefix(for: custom) + name)
            #expect(throws: (any Error).self) { try BackupContents.validate(BackupTestFixture.replacing(payload, files: files)) }
        }
    }

    @Test func rejectsInvalidHistoryAndSettingsBeforeRestore() throws {
        let payload = try BackupTestFixture.payload()
        var badAlerts = AlertSettings(); badAlerts.quietStart = 1440
        let cases: [(String, Data)] = [
            ("history-v1.json", Data("{}".utf8)),
            ("history-v1.json", Data("[{\"schemaVersion\":99}]".utf8)),
            ("preferences.json", Data("42".utf8)),
            ("interval.json", Data("0".utf8)),
            ("alerts.json", try JSONEncoder().encode(badAlerts)),
            ("webhook.json", try JSONEncoder().encode(WebhookSettings(enabled: true, address: "http://example.test")))
        ]
        let temp = try BackupTestFixture.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: temp.parent) }
        for (name, data) in cases {
            var files = payload.files; files[name] = data
            #expect(throws: (any Error).self) { try BackupFileStore.restore(BackupTestFixture.replacing(payload, files: files), at: temp.root) }
            #expect(try String(contentsOf: temp.root.appendingPathComponent("sentinel.txt"), encoding: .utf8) == "original-user-data")
            #expect(try FileManager.default.contentsOfDirectory(atPath: temp.parent.path) == ["TgWatch"])
        }
    }

    @Test func previewCountsMustMatchValidatedContent() throws {
        let payload = try BackupTestFixture.payload(history: [.init(epoch: 1, observedAt: Date(), status: .ok)])
        #expect(try BackupContents.validate(payload).historyEntries == 1)
        let wrong = EncryptedBackupPayload(preview: .init(appVersion: "0.1.0", serviceCount: 1,
            serviceNames: ["Telegram"], totalHistoryEntries: 99), files: payload.files)
        #expect(throws: (any Error).self) { try BackupContents.validate(wrong) }
    }

    @Test func manifestWriterAndReaderShareSizeBound() throws {
        var definition = ServiceDefinition.telegram
        definition.address = String(repeating: "a", count: BackupContents.manifestLimit)
        #expect(throws: (any Error).self) { try BackupContents.encodedManifest([definition]) }
        #expect(throws: (any Error).self) { try BackupContents.decodeManifest(Data(repeating: 32, count: BackupContents.manifestLimit + 1)) }
    }

    @Test func exportUsesDefaultsOnlyForMissingFilesAndRejectsCorruption() throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: parent) }
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        let manifest = try BackupContents.encodedManifest([.telegram])
        try manifest.write(to: parent.appendingPathComponent("services-v1.json"))
        let files = try BackupFileStore.capture(at: parent)
        #expect(try BackupContents.validateFiles(files).historyEntries == 0)
        let historyURL = parent.appendingPathComponent("history-v1.json")
        try Data("broken".utf8).write(to: historyURL)
        #expect(throws: (any Error).self) { try BackupFileStore.capture(at: parent) }
        #expect(try Data(contentsOf: historyURL) == Data("broken".utf8))
    }

    @Test func exportRejectsSymlinksAndDirectoriesInPlaceOfFiles() throws {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: parent) }
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        try BackupContents.encodedManifest([.telegram]).write(to: parent.appendingPathComponent("services-v1.json"))
        let history = parent.appendingPathComponent("history-v1.json")
        try FileManager.default.createSymbolicLink(at: history, withDestinationURL: parent.appendingPathComponent("services-v1.json"))
        #expect(throws: (any Error).self) { try BackupFileStore.capture(at: parent) }
        try FileManager.default.removeItem(at: history)
        try FileManager.default.createDirectory(at: history, withIntermediateDirectories: false)
        #expect(throws: (any Error).self) { try BackupFileStore.capture(at: parent) }
    }

    @Test func failedCommitLeavesOriginalBytesAndCleansStaging() throws {
        let temp = try BackupTestFixture.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: temp.parent) }
        let payload = try BackupTestFixture.payload()
        #expect(throws: (any Error).self) {
            try BackupFileStore.restore(payload, at: temp.root, exchange: { _, _ in throw CocoaError(.fileWriteOutOfSpace) }, verifyInstalled: { _ in })
        }
        #expect(try String(contentsOf: temp.root.appendingPathComponent("sentinel.txt"), encoding: .utf8) == "original-user-data")
        #expect(try FileManager.default.contentsOfDirectory(atPath: temp.parent.path) == ["TgWatch"])
    }

    #if canImport(Darwin)
    @Test func verifiedRestorePreservesHistoryAndPrivateAttributes() throws {
        let temp = try BackupTestFixture.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: temp.parent) }
        let payload = try BackupTestFixture.payload(history: [.init(epoch: 2, observedAt: Date(), status: .down)])
        let result = try BackupFileStore.restore(payload, at: temp.root)
        #expect(result.historyEntries == 1)
        for (path, data) in payload.files { #expect(try Data(contentsOf: temp.root.appendingPathComponent(path)) == data) }
        #expect(!FileManager.default.fileExists(atPath: temp.root.appendingPathComponent("sentinel.txt").path))
        for path in ["services-v1.json", "webhook.json"] {
            let url = temp.root.appendingPathComponent(path)
            #expect(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
            #if os(iOS)
            #expect(try FileManager.default.attributesOfItem(atPath: url.path)[.protectionKey] as? FileProtectionType == .complete)
            #endif
        }
    }

    @Test func failedPostCommitVerificationRollsBackOriginalDirectory() throws {
        let temp = try BackupTestFixture.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: temp.parent) }
        let payload = try BackupTestFixture.payload()
        #expect(throws: (any Error).self) {
            try BackupFileStore.restore(payload, at: temp.root, exchange: BackupFileStore.atomicExchange,
                verifyInstalled: { _ in throw CocoaError(.fileReadCorruptFile) })
        }
        #expect(try String(contentsOf: temp.root.appendingPathComponent("sentinel.txt"), encoding: .utf8) == "original-user-data")
        #expect(try FileManager.default.contentsOfDirectory(atPath: temp.parent.path) == ["TgWatch"])
    }

    @Test func failedRollbackRetainsPreviousDirectoryForRecovery() throws {
        let temp = try BackupTestFixture.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: temp.parent) }
        let payload = try BackupTestFixture.payload()
        var exchanges = 0
        #expect(throws: BackupError.restoreRecoveryRequired) {
            try BackupFileStore.restore(payload, at: temp.root, exchange: { first, second in
                exchanges += 1
                if exchanges == 2 { throw CocoaError(.fileWriteUnknown) }
                try BackupFileStore.atomicExchange(first, second)
            }, verifyInstalled: { _ in throw CocoaError(.fileReadCorruptFile) })
        }
        let retained = try FileManager.default.contentsOfDirectory(at: temp.parent, includingPropertiesForKeys: nil)
            .filter { $0.lastPathComponent.hasPrefix(".tgwatch-restore-") }
        #expect(retained.count == 1)
        #expect(try String(contentsOf: retained[0].appendingPathComponent("sentinel.txt"), encoding: .utf8) == "original-user-data")
    }
    #endif

    @Test func boundedPrivateWriterDoesNotOverwriteOnFailure() throws {
        let temp = try BackupTestFixture.temporaryRoot()
        defer { try? FileManager.default.removeItem(at: temp.parent) }
        let file = temp.root.appendingPathComponent("services-v1.json")
        try BackupFileStore.writePrivate(Data("old".utf8), to: file, limit: 3)
        #expect(throws: BackupError.payloadTooLarge) { try BackupFileStore.writePrivate(Data("oversized".utf8), to: file, limit: 3) }
        #expect(try Data(contentsOf: file) == Data("old".utf8))
    }
}
