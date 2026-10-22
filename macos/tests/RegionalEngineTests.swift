import Foundation
import Darwin

@main struct RegionalEngineTests {
    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        if !condition() { FileHandle.standardError.write(Data((message + "\n").utf8)); exit(1) }
    }
    static func main() throws {
        let candidates = MacPythonRuntime.candidates(environment: ["PATH": ":relative:/usr/bin:/tmp/bin:/tmp/bin"],
                                                     knownPaths: [])
        expect(candidates.map(\.path) == ["/tmp/bin/python3"], "No shell, relative PATH, system launcher or duplicates")
        expect(MacPythonRuntime.find(candidates: [], isCurrent: { true }) == nil, "Missing runtime is explicit")
        expect(MacPythonRuntime.find(candidates: MacPythonRuntime.candidates(), isCurrent: { false }) == nil,
               "Canceled discovery cannot launch a process")
        guard MacPythonRuntime.find(candidates: MacPythonRuntime.candidates(), isCurrent: { true }) != nil else {
            fatalError("Process tests need installed Python3.10+")
        }
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
        let engine = RegionalEngineProcess(script: script, onLine: { data, generation in
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
