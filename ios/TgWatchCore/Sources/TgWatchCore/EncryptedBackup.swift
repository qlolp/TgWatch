import Foundation
import CryptoKit
import CommonCrypto

public enum BackupError: Error, Equatable, LocalizedError {
    case invalidPasswordLength
    case invalidFormat
    case authenticationFailed
    case payloadTooLarge
    case invalidPath(String)
    case corruptedData

    public var errorDescription: String? {
        switch self {
        case .invalidPasswordLength:
            return "Password must be between 12 and 256 characters"
        case .invalidFormat:
            return "Unsupported or invalid backup format"
        case .authenticationFailed:
            return "Incorrect password or tampered backup"
        case .payloadTooLarge:
            return "Backup payload exceeds maximum allowed size"
        case .invalidPath(let path):
            return "Invalid file path in backup: \(path)"
        case .corruptedData:
            return "Corrupted backup data"
        }
    }
}

public struct EncryptedBackupPayload: Codable, Sendable, Equatable {
    public struct Preview: Codable, Sendable, Equatable {
        public let createdAt: Date
        public let appVersion: String
        public let serviceCount: Int
        public let serviceNames: [String]
        public let totalHistoryEntries: Int

        public init(
            createdAt: Date = Date(),
            appVersion: String,
            serviceCount: Int,
            serviceNames: [String],
            totalHistoryEntries: Int
        ) {
            self.createdAt = createdAt
            self.appVersion = appVersion
            self.serviceCount = serviceCount
            self.serviceNames = serviceNames
            self.totalHistoryEntries = totalHistoryEntries
        }
    }

    public let preview: Preview
    public let files: [String: Data]

    public init(preview: Preview, files: [String: Data]) {
        self.preview = preview
        self.files = files
    }
}

public enum EncryptedBackup {
    public static let magic = Data("TGWIOS01".utf8)
    public static let saltLength = 16
    public static let pbkdf2Iterations: UInt32 = 600_000
    public static let maxPlaintextSize = 32 * 1024 * 1024 // 32 MiB

    public static func validatePassword(_ password: String) throws {
        guard (12...256).contains(password.count) else {
            throw BackupError.invalidPasswordLength
        }
    }

    public static func validatePath(_ path: String) throws {
        guard !path.isEmpty, !path.starts(with: "/"), !path.contains(".."), !path.contains("\\") else {
            throw BackupError.invalidPath(path)
        }

        let allowedRootFiles: Set<String> = [
            "services-v1.json",
            "favorites.json",
            "history-v1.json",
            "long-term-v1.json",
            "preferences.json",
            "alerts.json",
            "interval.json",
            "webhook.json"
        ]

        if allowedRootFiles.contains(path) {
            return
        }

        let components = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        if components.count == 4 && components[0] == "targets" {
            let serviceID = components[1]
            let revision = components[2]
            let filename = components[3]

            let allowedTargetFiles: Set<String> = [
                "history-v1.json",
                "long-term-v1.json",
                "preferences.json",
                "alerts.json",
                "interval.json",
                "webhook.json"
            ]

            if !serviceID.isEmpty, UUID(uuidString: revision) != nil, allowedTargetFiles.contains(filename) {
                return
            }
        }

        throw BackupError.invalidPath(path)
    }

    public static func deriveKey(password: String, salt: Data) throws -> SymmetricKey {
        try validatePassword(password)
        guard salt.count == saltLength else {
            throw BackupError.corruptedData
        }

        var derivedKey = Data(count: 32)
        let status = derivedKey.withUnsafeMutableBytes { derivedKeyBytes in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    password,
                    password.utf8.count,
                    saltBytes.bindMemory(to: UInt8.self).baseAddress,
                    salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    pbkdf2Iterations,
                    derivedKeyBytes.bindMemory(to: UInt8.self).baseAddress,
                    32
                )
            }
        }

        guard status == kCCSuccess else {
            throw BackupError.corruptedData
        }

        return SymmetricKey(data: derivedKey)
    }

    public static func seal(files: [String: Data], preview: EncryptedBackupPayload.Preview, password: String) throws -> Data {
        try validatePassword(password)

        for path in files.keys {
            try validatePath(path)
        }

        let payload = EncryptedBackupPayload(preview: preview, files: files)
        let plaintext = try JSONEncoder().encode(payload)
        guard plaintext.count <= maxPlaintextSize else {
            throw BackupError.payloadTooLarge
        }

        var salt = Data(count: saltLength)
        let saltResult = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, saltLength, $0.baseAddress!) }
        guard saltResult == errSecSuccess else {
            throw BackupError.corruptedData
        }

        let key = try deriveKey(password: password, salt: salt)
        let sealedBox = try AES.GCM.seal(plaintext, using: key)

        guard let combined = sealedBox.combined else {
            throw BackupError.corruptedData
        }

        var result = Data()
        result.append(magic)
        result.append(salt)
        result.append(combined)
        return result
    }

    public static func open(data: Data, password: String) throws -> EncryptedBackupPayload {
        try validatePassword(password)

        let headerSize = magic.count + saltLength
        // combined AES.GCM box requires at least 12 bytes nonce + 16 bytes tag = 28 bytes
        guard data.count >= headerSize + 28 else {
            throw BackupError.invalidFormat
        }

        let fileMagic = data.prefix(magic.count)
        guard fileMagic == magic else {
            throw BackupError.invalidFormat
        }

        let salt = data.subdata(in: magic.count..<(magic.count + saltLength))
        let combinedCiphertext = data.subdata(in: headerSize..<data.count)

        let key = try deriveKey(password: password, salt: salt)

        let sealedBox: AES.GCM.SealedBox
        do {
            sealedBox = try AES.GCM.SealedBox(combined: combinedCiphertext)
        } catch {
            throw BackupError.authenticationFailed
        }

        let plaintext: Data
        do {
            plaintext = try AES.GCM.open(sealedBox, using: key)
        } catch {
            throw BackupError.authenticationFailed
        }

        guard plaintext.count <= maxPlaintextSize else {
            throw BackupError.payloadTooLarge
        }

        let payload: EncryptedBackupPayload
        do {
            payload = try JSONDecoder().decode(EncryptedBackupPayload.self, from: plaintext)
        } catch {
            throw BackupError.corruptedData
        }

        for path in payload.files.keys {
            try validatePath(path)
        }

        return payload
    }
}
