import Foundation
import Darwin

@main struct RegionalEngineTests {
    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        if !condition() { FileHandle.standardError.write(Data((message + "\n").utf8)); exit(1) }
    }
    static func main() throws {
        let resources = URL(fileURLWithPath: "/tmp/TgWatch.app/Contents/Resources")
        expect(MacPythonRuntime.candidates(resourcesURL: resources, architecture: "arm64").map(\.path) ==
               ["/tmp/TgWatch.app/Contents/Resources/python/arm64/bin/python3"],
               "Apple Silicon uses its bundled runtime")
        expect(MacPythonRuntime.candidates(resourcesURL: resources, architecture: "x86_64").map(\.path) ==
               ["/tmp/TgWatch.app/Contents/Resources/python/x86_64/bin/python3"],
               "Intel uses its bundled runtime")
        expect(MacPythonRuntime.candidates(resourcesURL: resources).map(\.path) ==
               ["/tmp/TgWatch.app/Contents/Resources/python/\(MacPythonRuntime.processArchitecture)/bin/python3"],
               "The app process architecture selects its matching bundled interpreter")
        expect(MacPythonRuntime.candidates(resourcesURL: resources, architecture: "unknown").isEmpty,
               "Unsupported architectures have no runtime candidate")
        expect(MacPythonRuntime.find(candidates: [], isCurrent: { true }) == nil, "Missing runtime is explicit")
        expect(MacPythonRuntime.find(candidates: [URL(fileURLWithPath: "/usr/bin/python3")], isCurrent: { false }) == nil,
               "Canceled discovery cannot launch a process")
        guard let python = ProcessInfo.processInfo.environment["TGWATCH_TEST_PYTHON"] else {
            fatalError("Process tests need TGWATCH_TEST_PYTHON")
        }
        let runtime = URL(fileURLWithPath: python)
        expect(MacPythonRuntime.find(candidates: [runtime], isCurrent: { true }) == runtime,
               "An explicitly injected process-test interpreter must pass the version probe")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let script = directory.appendingPathComponent("fixture.py")
        let marker = directory.appendingPathComponent("pid")
        let fixture = """
        import json, os, pathlib, time
        pathlib.Path(__file__).with_name('pid').write_text(str(os.getpid()))
        print(json.dumps(dict(schema=1, source='mac_public', service='gemini', status='WEB_REACHABLE',
          http_code=200, country='DE', checked_at=int(time.time()*1000), region_confirmed=False,
          context_id='0123456789abcdef0123456789abcdef')), flush=True)
        time.sleep(60)
        """
        try fixture.write(to: script, atomically: true, encoding: .utf8)
        let received = DispatchSemaphore(value: 0)
        let stopped = DispatchSemaphore(value: 0)
        let wrong = DispatchSemaphore(value: 0)
        let engine = RegionalEngineProcess(script: script, runtimeCandidates: [runtime], onLine: { data, generation in
            if generation == 1 && RegionalMenuDecoder.decode(data,
                now: Date().timeIntervalSince1970 * 1_000, uptime: ProcessInfo.processInfo.systemUptime) != nil {
                received.signal()
            } else { wrong.signal() }
        }, onState: { state, generation in
            if case .stopped = state, generation == 2 { stopped.signal() }
        })
        engine.replace(services: [.gemini], generation: 1)
        let didReceive = received.wait(timeout: .now() + 10)
        if didReceive != .success {
            engine.replace(services: [], generation: 2)
            _ = stopped.wait(timeout: .now() + 5)
        }
        expect(didReceive == .success, "Real interpreter must emit valid IPC")
        let pid = Int32(try String(contentsOf: marker, encoding: .utf8))!
        engine.replace(services: [], generation: 2)
        expect(stopped.wait(timeout: .now() + 5) == .success, "Stop waits for the owned interpreter")
        expect(kill(pid, 0) == -1 && errno == ESRCH, "The stopped interpreter must not remain alive")
        expect(wrong.wait(timeout: .now() + 0.1) == .timedOut, "No replaced child may publish")
        print("RegionalEngine: runtime discovery, real IPC and owned process stop passed")
    }
}
