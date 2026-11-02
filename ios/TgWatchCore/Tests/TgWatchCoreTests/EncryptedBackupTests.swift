import Testing
import Foundation
@testable import TgWatchCore

@Suite struct EncryptedBackupTests {
    private let testPassword = "correct-horse-battery-staple-1234"

    @Test func roundtripSealAndOpen() throws {
        let preview = EncryptedBackupPayload.Preview(
            createdAt: Date(),
            appVersion: "1.0",
            serviceCount: 2,
            serviceNames: ["Telegram", "Internal API"],
            totalHistoryEntries: 150
        )

        let files: [String: Data] = [
            "services-v1.json": Data("{\"services\":[]}".utf8),
            "favorites.json": Data("[\"telegram\"]".utf8),
            "targets/custom1/550e8400-e29b-41d4-a716-446655440000/history-v1.json": Data("[]".utf8)
        ]

        let encrypted = try EncryptedBackup.seal(files: files, preview: preview, password: testPassword)
        #expect(encrypted.starts(with: EncryptedBackup.magic))

        let decrypted = try EncryptedBackup.open(data: encrypted, password: testPassword)
        #expect(decrypted.preview == preview)
        #expect(decrypted.files == files)
    }

    @Test func distinctFromAndroidTGWB() {
        let androidData = Data("TGWB0001dummydatawithlongcontent12345678901234567890".utf8)
        #expect(throws: BackupError.invalidFormat) {
            try EncryptedBackup.open(data: androidData, password: testPassword)
        }
    }

    @Test func wrongPasswordFailsAuthentication() throws {
        let preview = EncryptedBackupPayload.Preview(
            createdAt: Date(),
            appVersion: "1.0",
            serviceCount: 1,
            serviceNames: ["Telegram"],
            totalHistoryEntries: 5
        )
        let files = ["services-v1.json": Data("{}".utf8)]
        let encrypted = try EncryptedBackup.seal(files: files, preview: preview, password: testPassword)

        #expect(throws: BackupError.authenticationFailed) {
            try EncryptedBackup.open(data: encrypted, password: "wrong-password-long-enough-1234")
        }
    }

    @Test func tamperedCiphertextFailsAuthentication() throws {
        let preview = EncryptedBackupPayload.Preview(
            createdAt: Date(),
            appVersion: "1.0",
            serviceCount: 1,
            serviceNames: ["Telegram"],
            totalHistoryEntries: 5
        )
        let files = ["services-v1.json": Data("{}".utf8)]
        var encrypted = try EncryptedBackup.seal(files: files, preview: preview, password: testPassword)

        // Corrupt a byte in ciphertext (towards the end)
        let lastIndex = encrypted.count - 5
        encrypted[lastIndex] ^= 0xFF

        #expect(throws: BackupError.authenticationFailed) {
            try EncryptedBackup.open(data: encrypted, password: testPassword)
        }
    }

    @Test func passwordLengthValidation() {
        let shortPassword = "too-short" // 9 chars
        let longPassword = String(repeating: "a", count: 257) // 257 chars

        let preview = EncryptedBackupPayload.Preview(
            createdAt: Date(),
            appVersion: "1.0",
            serviceCount: 1,
            serviceNames: ["Telegram"],
            totalHistoryEntries: 0
        )
        let files = ["services-v1.json": Data()]

        #expect(throws: BackupError.invalidPasswordLength) {
            try EncryptedBackup.seal(files: files, preview: preview, password: shortPassword)
        }

        #expect(throws: BackupError.invalidPasswordLength) {
            try EncryptedBackup.seal(files: files, preview: preview, password: longPassword)
        }
    }

    @Test func pathTraversalRejected() {
        let invalidPaths = [
            "../secret.txt",
            "/etc/passwd",
            "targets/../../../var",
            "services\\evil.json",
            "unauthorized_file.txt",
            "targets/id/not-a-uuid/history-v1.json",
            "targets/id/550e8400-e29b-41d4-a716-446655440000/unauthorized.json"
        ]

        let preview = EncryptedBackupPayload.Preview(
            createdAt: Date(),
            appVersion: "1.0",
            serviceCount: 1,
            serviceNames: ["Telegram"],
            totalHistoryEntries: 0
        )

        for path in invalidPaths {
            #expect(throws: BackupError.invalidPath(path)) {
                try EncryptedBackup.seal(files: [path: Data()], preview: preview, password: testPassword)
            }
        }
    }
}
