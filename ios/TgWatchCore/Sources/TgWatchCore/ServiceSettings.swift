import Foundation
public struct ServiceSettings: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let target: ServiceTarget
    public let intervalSeconds: Int
    public let automatic: Bool
    public static let defaults = ServiceSettings(target:.matrix,intervalSeconds:60,automatic:false)
    public init(target: ServiceTarget, intervalSeconds: Int, automatic: Bool) {
        schemaVersion=1; self.target=target; self.intervalSeconds=intervalSeconds; self.automatic=automatic
    }
    public var isValid: Bool { schemaVersion == 1 && target != .telegram && [60,120,300].contains(intervalSeconds) }
}
/// Tiny synchronous settings I/O. Unknown or corrupt files survive until an explicit reset.
public struct ServiceSettingsStore: Sendable {
    public enum Failure: Error { case oversized, invalid }
    public static let maxBytes=1024
    private let url: URL
    public init(url: URL) { self.url=url }
    public func load() throws -> ServiceSettings {
        guard FileManager.default.fileExists(atPath:url.path) else { return .defaults }
        let handle=try FileHandle(forReadingFrom:url); defer { try? handle.close() }
        let data=try handle.read(upToCount:Self.maxBytes+1) ?? Data()
        guard data.count <= Self.maxBytes else { throw Failure.oversized }
        var reader=UniqueJSON(bytes:Array(data))
        guard reader.valid(), let object=try JSONSerialization.jsonObject(with:data) as? [String:Any],
              Set(object.keys) == Set(["schemaVersion","target","intervalSeconds","automatic"]) else { throw Failure.invalid }
        let value=try JSONDecoder().decode(ServiceSettings.self,from:data)
        guard value.isValid else { throw Failure.invalid }
        return value
    }
    public func save(_ value: ServiceSettings) throws {
        guard value.isValid else { throw Failure.invalid }
        _ = try load() // Do not overwrite unreadable/future settings through an ordinary change.
        try persist(value)
    }
    public func reset() throws { try persist(.defaults) }
    private func persist(_ value: ServiceSettings) throws {
        let data=try JSONEncoder().encode(value)
        guard data.count <= Self.maxBytes else { throw Failure.oversized }
        try FileManager.default.createDirectory(at:url.deletingLastPathComponent(),withIntermediateDirectories:true)
        try data.write(to:url,options:.atomic)
    }
}
