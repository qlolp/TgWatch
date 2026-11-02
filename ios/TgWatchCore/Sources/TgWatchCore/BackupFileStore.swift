import Foundation
#if canImport(Darwin)
import Darwin
#endif

public enum BackupFileStore {
    public static func read(_ url: URL, limit: Int) throws -> Data {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else { throw BackupError.corruptedData }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: limit + 1) ?? Data()
        guard data.count <= limit else { throw BackupError.payloadTooLarge }
        return data
    }

    public static func writePrivate(_ data: Data, to url: URL, limit: Int) throws {
        guard data.count <= limit else { throw BackupError.payloadTooLarge }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        var options: Data.WritingOptions = [.atomic]
        #if os(iOS) || os(watchOS) || os(tvOS) || os(visionOS)
        options.insert(.completeFileProtection)
        #endif
        try data.write(to: url, options: options)
        var protectedURL = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try protectedURL.setResourceValues(values)
    }

    /// Reads every managed file or throws. Missing optional-on-disk settings are represented
    /// explicitly with defaults so omission from an archive cannot silently erase history.
    public static func capture(at root: URL) throws -> [String: Data] {
        let manager = FileManager.default
        let properties = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard properties.isDirectory == true, properties.isSymbolicLink != true else { throw BackupError.corruptedData }
        let sourceRoot = root.resolvingSymlinksInPath()
        let rootComponents = sourceRoot.pathComponents
        let manifest = try read(root.appendingPathComponent("services-v1.json"), limit: BackupContents.manifestLimit)
        let definitions = try BackupContents.decodeManifest(manifest)
        var files: [String: Data] = [:]
        var readError: Error?
        guard let enumerator = manager.enumerator(at: sourceRoot,
            includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey],
            errorHandler: { _, error in readError = error; return false }) else { throw BackupError.corruptedData }
        for case let url as URL in enumerator {
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw BackupError.corruptedData }
            let components = url.resolvingSymlinksInPath().pathComponents
            guard components.starts(with: rootComponents), components.count > rootComponents.count else {
                throw BackupError.corruptedData
            }
            let relative = components.dropFirst(rootComponents.count).joined(separator: "/")
            if values.isDirectory == true {
                let parts = relative.split(separator: "/")
                guard parts.first == "targets", parts.count <= 3,
                      parts.count < 2 || (BackupContents.serviceIDs.contains(String(parts[1])) && parts[1] != "telegram"),
                      parts.count < 3 || UUID(uuidString: String(parts[2]))?.uuidString == String(parts[2]) else {
                    throw BackupError.corruptedData
                }
                continue
            }
            try EncryptedBackup.validatePath(relative)
            files[relative] = try read(url, limit: BackupContents.fileLimit(relative))
            guard files.count <= BackupContents.maxFiles,
                  files.values.reduce(0, { $0 + $1.count }) <= EncryptedBackup.maxPlaintextSize else {
                throw BackupError.payloadTooLarge
            }
        }
        if let readError { throw readError }
        guard files["services-v1.json"] == manifest else { throw BackupError.corruptedData }
        var prefixes = Set(definitions.map(BackupContents.prefix(for:)))
        for path in files.keys where path.hasPrefix("targets/") {
            prefixes.insert(path.split(separator: "/").prefix(3).joined(separator: "/") + "/")
        }
        let defaults = try BackupContents.defaultDirectoryFiles()
        for prefix in prefixes {
            for (name, data) in defaults where files[prefix + name] == nil { files[prefix + name] = data }
        }
        if files["favorites.json"] == nil { files["favorites.json"] = try JSONEncoder().encode(Set(["telegram"])) }
        _ = try BackupContents.validateFiles(files)
        return files
    }

    @discardableResult
    public static func restore(_ payload: EncryptedBackupPayload, at root: URL) throws -> BackupContents.Validated {
        try restore(payload, at: root, exchange: atomicExchange, verifyInstalled: { _ in })
    }

    // Internal fault seams exercise failed commit/verification without changing real app data.
    static func restore(_ payload: EncryptedBackupPayload, at root: URL,
                        exchange: (URL, URL) throws -> Void,
                        verifyInstalled: (URL) throws -> Void) throws -> BackupContents.Validated {
        let validated = try BackupContents.validate(payload) // Before any filesystem or monitor mutation.
        let manager = FileManager.default
        let rootValues = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey, .isExcludedFromBackupKey])
        guard rootValues.isDirectory == true, rootValues.isSymbolicLink != true else { throw BackupError.corruptedData }
        var stage = root.deletingLastPathComponent().appendingPathComponent(".tgwatch-restore-\(UUID().uuidString)")
        try manager.createDirectory(at: stage, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        var cleanupStage = true
        defer { if cleanupStage { try? manager.removeItem(at: stage) } }
        var stageFlags = URLResourceValues(); stageFlags.isExcludedFromBackup = true
        try stage.setResourceValues(stageFlags)
        for (path, data) in payload.files {
            let url = stage.appendingPathComponent(path)
            if path == "services-v1.json" || path.hasSuffix("webhook.json") {
                try writePrivate(data, to: url, limit: BackupContents.fileLimit(path))
            } else {
                try manager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try data.write(to: url, options: .atomic)
            }
            guard try read(url, limit: BackupContents.fileLimit(path)) == data else { throw BackupError.corruptedData }
        }
        // Retain the root's backup policy; each restored private file remains excluded.
        stageFlags.isExcludedFromBackup = rootValues.isExcludedFromBackup ?? false
        try stage.setResourceValues(stageFlags)
        try exchange(root, stage) // Old and new directories both remain available until verification.
        do {
            for (path, data) in payload.files {
                guard try read(root.appendingPathComponent(path), limit: BackupContents.fileLimit(path)) == data else {
                    throw BackupError.corruptedData
                }
            }
            try verifyInstalled(root)
        } catch {
            do { try exchange(root, stage) }
            catch {
                cleanupStage = false // Keep the previous directory for recovery if rollback itself fails.
                throw BackupError.restoreRecoveryRequired
            }
            throw error
        }
        return validated
    }

    static func atomicExchange(_ first: URL, _ second: URL) throws {
        #if canImport(Darwin)
        // Same-parent staging keeps both directories on one filesystem. Never fall back to
        // two moves: an unsupported exchange must leave the current data untouched.
        let result = first.withUnsafeFileSystemRepresentation { firstPath in
            second.withUnsafeFileSystemRepresentation { secondPath in
                renamex_np(firstPath!, secondPath!, UInt32(RENAME_SWAP))
            }
        }
        guard result == 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        #else
        throw CocoaError(.featureUnsupported)
        #endif
    }
}
