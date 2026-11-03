import Foundation

public enum MonitorStorage {
    /// All files of a catalog share one root, including fixtures and restored controllers.
    public static func directory(for definition: ServiceDefinition, root: URL) -> URL {
        definition.kind == .telegram ? root : root.appendingPathComponent("targets/\(definition.id)/\(definition.revision.uuidString)")
    }
}
