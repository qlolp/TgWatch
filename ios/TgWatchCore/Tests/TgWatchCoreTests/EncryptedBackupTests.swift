import Testing
import Foundation
import CryptoKit
@testable import TgWatchCore

@Suite struct EncryptedBackupTests {
    private let testPassword = "correct-horse-battery-staple-1234"

    @Test func authenticatedButInvalidContentIsRejectedOnOpen() throws {
        let fixture = try BackupTestFixture.payload()
        for files in [[String: Data](), ["services-v1.json": Data("not-json".utf8)]] {
            let payload = BackupTestFixture.replacing(fixture, files: files)
            let salt = Data(repeating: 7, count: EncryptedBackup.saltLength)
            let key = try EncryptedBackup.deriveKey(password: testPassword, salt: salt)
            let box = try AES.GCM.seal(JSONEncoder().encode(payload), using: key)
            let encoded = EncryptedBackup.magic + salt + box.combined!
            #expect(throws: (any Error).self) { try EncryptedBackup.open(data: encoded, password: testPassword) }
        }
    }

    @Test func oversizedCiphertextIsRejectedBeforeDecryption() {
        let data = Data(repeating: 0, count: EncryptedBackup.maxPlaintextSize + 53)
        #expect(throws: BackupError.payloadTooLarge) { try EncryptedBackup.open(data: data, password: testPassword) }
    }

    @Test func roundtripSealAndOpen() throws {
        let fixture = try BackupTestFixture.payload()
        let preview = fixture.preview
        let files = fixture.files

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
        let fixture = try BackupTestFixture.payload()
        let preview = fixture.preview
        let files = fixture.files
        let encrypted = try EncryptedBackup.seal(files: files, preview: preview, password: testPassword)

        #expect(throws: BackupError.authenticationFailed) {
            try EncryptedBackup.open(data: encrypted, password: "wrong-password-long-enough-1234")
        }
    }

    @Test func tamperedCiphertextFailsAuthentication() throws {
        let fixture = try BackupTestFixture.payload()
        let preview = fixture.preview
        let files = fixture.files
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
