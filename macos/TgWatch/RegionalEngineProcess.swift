import Foundation
import Darwin

/// Never invoke a shell or the system developer-tools launcher. All candidates are absolute executables.
enum MacPythonRuntime {
#if arch(arm64)
    static let processArchitecture = "arm64"
#elseif arch(x86_64)
    static let processArchitecture = "x86_64"
#else
    static let processArchitecture = "unsupported"
#endif
    static func candidates(resourcesURL: URL = Bundle.main.resourceURL ?? Bundle.main.bundleURL,
                           architecture: String = processArchitecture) -> [URL] {
        guard architecture == "arm64" || architecture == "x86_64" else { return [] }
        return [resourcesURL.appendingPathComponent("python/\(architecture)/bin/python3")]
    }
    static func find(candidates: [URL], isCurrent: () -> Bool) -> URL? {
        for url in candidates {
            guard isCurrent() else { return nil }
            var directory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: url.path, isDirectory: &directory), !directory.boolValue,
                  FileManager.default.isExecutableFile(atPath: url.path) else { continue }
            let probe = Process()
            probe.executableURL = url
            probe.arguments = ["-I", "-B", "-c", "import sys; raise SystemExit(0 if sys.version_info >= (3,10) else 1)"]
            probe.standardOutput = FileHandle.nullDevice
            probe.standardError = FileHandle.nullDevice
            do { try probe.run() } catch { continue }
            let deadline = ProcessInfo.processInfo.systemUptime + 2
            while probe.isRunning && isCurrent() && ProcessInfo.processInfo.systemUptime < deadline {
                Thread.sleep(forTimeInterval: 0.02)
            }
            if probe.isRunning { probe.terminate() }
            let grace = ProcessInfo.processInfo.systemUptime + 0.2
            while probe.isRunning && ProcessInfo.processInfo.systemUptime < grace { Thread.sleep(forTimeInterval: 0.01) }
            if probe.isRunning { kill(probe.processIdentifier, SIGKILL) }
            probe.waitUntilExit()
            guard isCurrent() else { return nil }
            if probe.terminationReason == .exit && probe.terminationStatus == 0 { return url }
        }
        return nil
    }
}

enum RegionalEngineState: Equatable { case starting, running, stopped, missingRuntime, failed }

/// Process and pipe state belong to one queue. Only the desired generation crosses threads, under a lock.
final class RegionalEngineProcess: @unchecked Sendable {
    private let script: URL
    private let runtimeCandidates: [URL]
    private let onLine: @Sendable (Data, UInt64) -> Void
    private let onState: @Sendable (RegionalEngineState, UInt64) -> Void
    private let queue = DispatchQueue(label: "ru.tgwatch.mac.engine")
    private let lock = NSLock()
    private var desiredGeneration: UInt64 = 0
    private var process: Process?
    private var output: FileHandle?
    private var buffer = Data()

    init(script: URL, runtimeCandidates: [URL] = MacPythonRuntime.candidates(),
         onLine: @escaping @Sendable (Data, UInt64) -> Void,
         onState: @escaping @Sendable (RegionalEngineState, UInt64) -> Void) {
        self.script = script
        self.runtimeCandidates = runtimeCandidates
        self.onLine = onLine
        self.onState = onState
    }
    func replace(services: Set<RegionalMenuService>, generation: UInt64) {
        lock.lock(); desiredGeneration = generation; lock.unlock()
        queue.async { [self] in
            stopOwned()
            guard isCurrent(generation) else { return }
            guard !services.isEmpty else { onState(.stopped, generation); return }
            onState(.starting, generation)
            let runtime = MacPythonRuntime.find(candidates: runtimeCandidates,
                                                isCurrent: { self.isCurrent(generation) })
            guard isCurrent(generation) else { return }
            guard let runtime else { onState(.missingRuntime, generation); return }
            launch(runtime, services: services, generation: generation)
        }
    }
    private func isCurrent(_ generation: UInt64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return generation == desiredGeneration
    }
    private func launch(_ runtime: URL, services: Set<RegionalMenuService>, generation: UInt64) {
        let child = Process()
        let pipe = Pipe()
        child.executableURL = runtime
        child.arguments = ["-I", "-B", "-u", script.path, "--app-json", "--watch"] +
            services.sorted { $0.rawValue < $1.rawValue }.flatMap { ["--service", $0.rawValue] }
        child.standardOutput = pipe
        child.standardError = FileHandle.nullDevice
        child.terminationHandler = { [weak self] child in
            self?.queue.async { [weak self] in
                guard let self, self.process === child else { return }
                self.output?.readabilityHandler = nil
                try? self.output?.close()
                self.process = nil
                self.output = nil
                self.buffer.removeAll()
                if self.isCurrent(generation) { self.onState(.failed, generation) }
            }
        }
        process = child
        output = pipe.fileHandleForReading
        do { try child.run() } catch {
            stopOwned()
            if isCurrent(generation) { onState(.failed, generation) }
            return
        }
        guard isCurrent(generation) else { stopOwned(); return }
        let descriptor = pipe.fileHandleForReading.fileDescriptor
        let flags = fcntl(descriptor, F_GETFL)
        guard flags >= 0, fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) >= 0 else {
            stopOwned(); onState(.failed, generation); return
        }
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            // FileHandle.read(upToCount:) can wait for the entire count. POSIX read returns available bytes.
            var bytes = [UInt8](repeating: 0, count: 8_192)
            let count = bytes.withUnsafeMutableBytes { Darwin.read(handle.fileDescriptor, $0.baseAddress, $0.count) }
            if count < 0 && [EINTR, EAGAIN].contains(errno) { return }
            let data = count > 0 ? Data(bytes.prefix(count)) : Data()
            self?.queue.async { [weak self] in
                guard let self, self.isCurrent(generation), self.process === child else { return }
                if data.isEmpty { self.protocolFailure(generation); return }
                self.accept(data, generation: generation)
            }
        }
        onState(.running, generation)
    }
    private func accept(_ data: Data, generation: UInt64) {
        buffer.append(data)
        var frames = 0
        while let end = buffer.firstIndex(of: 10) {
            let line = Data(buffer[..<end])
            buffer.removeSubrange(...end)
            frames += 1
            guard frames <= 32, RegionalMenuDecoder.decode(line, now: Date().timeIntervalSince1970 * 1_000,
                uptime: ProcessInfo.processInfo.systemUptime) != nil else { protocolFailure(generation); return }
            onLine(line, generation)
        }
        if buffer.count > RegionalMenuDecoder.maximumBytes { protocolFailure(generation) }
    }
    private func protocolFailure(_ generation: UInt64) {
        stopOwned()
        if isCurrent(generation) { onState(.failed, generation) }
    }
    private func stopOwned() {
        let child = process
        process = nil
        output?.readabilityHandler = nil
        child?.terminationHandler = nil
        if let child, child.isRunning {
            child.terminate()
            let deadline = ProcessInfo.processInfo.systemUptime + 3
            while child.isRunning && ProcessInfo.processInfo.systemUptime < deadline { Thread.sleep(forTimeInterval: 0.02) }
            if child.isRunning { kill(child.processIdentifier, SIGKILL) }
            child.waitUntilExit()
        }
        try? output?.close()
        output = nil
        buffer.removeAll()
    }
}
