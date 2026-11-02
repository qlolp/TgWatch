import SwiftUI
import TgWatchCore

actor WebhookSender {
    private var busy = false
    func send(settings: WebhookSettings, event: WebhookEvent) async throws -> Bool {
        guard !busy, settings.enabled, ServiceDefinition.validAddress(settings.address),
              let url = URL(string: settings.address) else { return false }
        busy = true; defer { busy = false }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5; config.timeoutIntervalForResource = 10
        config.urlCache = nil; config.httpCookieStorage = nil; config.httpShouldSetCookies = false
        let session = URLSession(configuration: config, delegate: WebhookRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        if settings.json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(event)
        } else {
            request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data("TgWatch: \(event.serviceID) — \(event.status ?? "test")".utf8)
        }
        let (_, response) = try await session.bytes(for: request)
        try Task.checkCancellation()
        return (response as? HTTPURLResponse).map { (200...299).contains($0.statusCode) } ?? false
    }
}

private final class WebhookRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct WebhookView: View {
    @ObservedObject var controller: MonitorController
    @State private var address = ""
    @State private var enabled = false
    @State private var json = true
    @State private var saved = false
    var body: some View {
        Form {
            Section {
                Text("webhookHint").font(.subheadline)
                Toggle("webhookEnabled", isOn: $enabled)
                SecureField("endpointAddress", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled()
                Text("webhookAddress").font(.caption).foregroundStyle(.secondary)
                Toggle("webhookJSON", isOn: $json)
                Button("saveService") {
                    controller.saveWebhook(.init(enabled: enabled, address: address.isEmpty ? controller.webhookSettings.address : address, json: json))
                    saved = !controller.webhookError
                    address = ""
                }.disabled(enabled && address.isEmpty && controller.webhookSettings.address.isEmpty)
                if saved { Label("webhookSaved", systemImage: "checkmark.circle") }
            }
            Section {
                Button("webhookTest") { controller.sendWebhook(.unknown) }.disabled(!controller.webhookSettings.enabled)
                if let value = controller.webhookDelivered {
                    Label(value ? "webhookDelivered" : "webhookFailed", systemImage: value ? "checkmark.circle" : "exclamationmark.triangle")
                }
                if controller.webhookError { Text("webhookFailed").foregroundStyle(.red) }
                Text("webhookPrivacy").font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("webhook")
        .onAppear { enabled = controller.webhookSettings.enabled; json = controller.webhookSettings.json }
    }
}
