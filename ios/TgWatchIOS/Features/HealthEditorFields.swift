import SwiftUI
import TgWatchCore

struct HealthConditionDraft: Identifiable {
    let id = UUID()
    var value: HealthJSONCondition
}

struct HealthHeaderDraft: Identifiable {
    let id = UUID()
    var value: HealthRequestHeader
}

struct HealthConditionsSection: View {
    @Binding var conditions: [HealthConditionDraft]
    var body: some View {
        Section("healthJSONConditions") {
            ForEach($conditions) { $condition in
                VStack(alignment: .leading, spacing: 8) {
                    TextField("jsonFieldPath", text: $condition.value.path)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("healthJSONPath")
                    SecureField("expectedJSONValue", text: $condition.value.expectedValue)
                    Button("healthRemoveCondition", role: .destructive) {
                        conditions.removeAll { $0.id == condition.id }
                    }
                }
            }
            if conditions.count < 4 {
                Button("healthAddCondition", systemImage: "plus") {
                    conditions.append(.init(value: .init()))
                }.accessibilityIdentifier("healthAddCondition")
            }
            Text("healthJSONHint").font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct HealthHeadersSection: View {
    @Binding var headers: [HealthHeaderDraft]
    var body: some View {
        Section("healthRequestHeaders") {
            ForEach($headers) { $header in
                VStack(alignment: .leading, spacing: 8) {
                    TextField("healthHeaderName", text: $header.value.name)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("healthHeaderValue", text: $header.value.value)
                    Button("healthRemoveHeader", role: .destructive) {
                        headers.removeAll { $0.id == header.id }
                    }
                }
            }
            if headers.count < 8 {
                Button("healthAddHeader", systemImage: "plus") {
                    headers.append(.init(value: .init()))
                }.accessibilityIdentifier("healthAddHeader")
            }
            Text("healthHeadersHint").font(.caption).foregroundStyle(.secondary)
        }
    }
}

enum HealthPreset: String, CaseIterable, Identifiable {
    case github = "GitHub", discord = "Discord", cloudflare = "Cloudflare", atlassian = "Atlassian", openai = "OpenAI"
    var id: String { rawValue }
    var address: String {
        switch self {
        case .github: "https://www.githubstatus.com/api/v2/status.json"
        case .discord: "https://discordstatus.com/api/v2/status.json"
        case .cloudflare: "https://www.cloudflarestatus.com/api/v2/status.json"
        case .atlassian: "https://status.atlassian.com/api/v2/status.json"
        case .openai: "https://status.openai.com/api/v2/status.json"
        }
    }
}
