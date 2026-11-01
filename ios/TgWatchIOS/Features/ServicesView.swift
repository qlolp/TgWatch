import SwiftUI
import TgWatchCore

struct ServicesRootView: View {
    @ObservedObject var catalog: ServiceCatalog
    var body: some View {
        TabView {
            if let telegram = catalog.monitors.first(where: { $0.definition.kind == .telegram }) {
                MonitorView(controller: telegram).tabItem { Label("Telegram", systemImage: "paperplane") }
            }
            ServicesView(catalog: catalog).tabItem { Label("services", systemImage: "square.grid.2x2") }
        }
    }
}

struct ServicesView: View {
    @ObservedObject var catalog: ServiceCatalog
    @State private var adding = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("independentServices").font(.subheadline).foregroundStyle(.secondary)
                    if catalog.storageError { Label("preferencesUnavailable", systemImage: "exclamationmark.triangle").foregroundStyle(.red) }
                }
                ForEach(catalog.monitors, id: \.definition.id) { monitor in
                    NavigationLink {
                        ServiceDetailView(controller: monitor, catalog: catalog)
                            .id(monitor.definition.revision)
                    } label: { ServiceRow(controller: monitor) }
                }
                if catalog.nextCustomID != nil {
                    Button("addHTTPS", systemImage: "plus") { adding = true }
                        .accessibilityIdentifier("addHTTPS")
                }
            }
            .navigationTitle("services")
            .sheet(isPresented: $adding) {
                if let id = catalog.nextCustomID { ServiceEditor(catalog: catalog, definition: .init(id: id, kind: .custom)) }
            }
        }
    }
}

private struct ServiceRow: View {
    @ObservedObject var controller: MonitorController
    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            HStack(spacing: 14) {
                Image(systemName: controller.definition.symbol).font(.title2).foregroundStyle(.blue).frame(width: 32)
                VStack(alignment: .leading, spacing: 5) {
                    Text(controller.definition.displayName).font(.headline)
                    if controller.session.checking { Text("checking") }
                    else if controller.session.isFresh(), let value = controller.session.snapshot {
                        Label(value.status.displayTitle, systemImage: value.status.displaySymbol).foregroundStyle(value.status.displayColor)
                    } else { Text("noFreshData").foregroundStyle(.secondary) }
                    Text(controller.session.monitoring ? "autoMonitoring" : "monitoringStopped").font(.caption).foregroundStyle(.secondary)
                }
            }.padding(.vertical, 8)
        }
    }
}

struct ServiceDetailView: View {
    @ObservedObject var controller: MonitorController
    @ObservedObject var catalog: ServiceCatalog
    @State private var editing = false
    @State private var removing = false
    @State private var failure = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        List {
            Section {
                ServiceRow(controller: controller)
                Button(controller.session.checking ? "cancel" : "checkNow") {
                    if controller.session.checking { controller.session.cancel() } else { controller.session.checkNow() }
                }.accessibilityIdentifier("serviceCheck")
                Toggle("autoMonitoring", isOn: Binding(get: { controller.session.monitoring }, set: controller.setMonitoring))
                Stepper(value: Binding(get: { controller.intervalSeconds }, set: controller.setInterval), in: 10...300, step: 10) {
                    LabeledContent("intervalSeconds", value: controller.intervalSeconds.formatted())
                }
                Text("foregroundExplanation").font(.caption).foregroundStyle(.secondary)
                Text("serviceReachability").font(.caption).foregroundStyle(.secondary)
            }
            Section {
                NavigationLink("historyReport") { HistoryReportView(controller: controller) }
                NavigationLink("history") { HistoryView(controller: controller) }
                NavigationLink("eventJournal") { EventJournalView(controller: controller) }
                NavigationLink("alertSettings") { AlertSettingsView(controller: controller) }
                NavigationLink("latencyReport") { LatencyReportView(controller: controller) }
                NavigationLink("shareStatus") { ShareStatusView(controller: controller) }
                        NavigationLink("webhook") { WebhookView(controller: controller) }
            }
            if controller.definition.kind == .custom || controller.definition.kind == .matrix {
                Section {
                    Button("editEndpoint") { editing = true }
                    Text("endpointRevisionHint").font(.caption).foregroundStyle(.secondary)
                    if controller.definition.kind == .custom { Button("removeService", role: .destructive) { removing = true } }
                }
            }
        }
        .navigationTitle(controller.definition.displayName)
        .onChange(of: catalog.monitors.map(\.definition)) { _, definitions in
            if !definitions.contains(controller.definition) { dismiss() }
        }
        .sheet(isPresented: $editing) { ServiceEditor(catalog: catalog, definition: controller.definition) }
        .confirmationDialog("removeServiceQuestion", isPresented: $removing) {
            Button("removeService", role: .destructive) {
                Task {
                    do { try await catalog.remove(controller.definition); dismiss() } catch { failure = true }
                }
            }
        }
        .alert("preferencesUnavailable", isPresented: $failure) { Button("dismiss", role: .cancel) {} }
    }
}

struct ServiceEditor: View {
    @ObservedObject var catalog: ServiceCatalog
    let definition: ServiceDefinition
    @State private var address: String
    @State private var code: String
    @State private var bodySubstring: String
    @State private var conditions: [HealthConditionDraft]
    @State private var headers: [HealthHeaderDraft]
    @State private var selectedPreset: HealthPreset?
    @State private var failure = false
    @Environment(\.dismiss) private var dismiss

    init(catalog: ServiceCatalog, definition: ServiceDefinition) {
        self.catalog = catalog
        self.definition = definition
        _address = State(initialValue: definition.address)
        _code = State(initialValue: definition.expectedCode.map(String.init) ?? "")
        _bodySubstring = State(initialValue: definition.bodySubstring ?? "")
        _conditions = State(initialValue: definition.healthConditions.map { HealthConditionDraft(value: $0) })
        _headers = State(initialValue: (definition.requestHeaders ?? []).map { HealthHeaderDraft(value: $0) })
    }
    private var candidate: ServiceDefinition {
        .init(id: definition.id, kind: definition.kind, address: address.trimmingCharacters(in: .whitespacesAndNewlines),
              expectedCode: code.isEmpty ? nil : Int(code), bodySubstring: bodySubstring,
              additionalJSONConditions: conditions.map(\.value), requestHeaders: headers.map(\.value))
    }
    private var valid: Bool { candidate.valid && (code.isEmpty || Int(code) != nil) }
    var body: some View {
        NavigationStack {
            Form {
                Section("endpointAddress") {
                    TextField("https://example.com/health", text: $address).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("endpointAddress")
                    Text("privateEndpointHint").font(.caption)
                    if definition.kind == .custom {
                        Menu("healthPresets") {
                            ForEach(HealthPreset.allCases) { preset in
                                Button(preset.rawValue) { selectedPreset = preset }
                            }
                        }.accessibilityIdentifier("healthPresets")
                    }
                }
                Section("healthCriteria") {
                    TextField("expectedHTTPCode", text: $code).keyboardType(.numberPad)
                    SecureField("healthBodySubstring", text: $bodySubstring)
                    Text("healthAllConditionsHint").font(.caption)
                }
                HealthConditionsSection(conditions: $conditions)
                HealthHeadersSection(headers: $headers)
                Section {
                    if !valid {
                        Label("healthValidationHint", systemImage: "exclamationmark.circle")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Text("healthPrivacyHint").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(definition.displayName)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("saveService") {
                        do { try catalog.save(candidate); dismiss() } catch { failure = true }
                    }.disabled(!valid).accessibilityIdentifier("saveService")
                }
            }
            .confirmationDialog("healthPresetReplace", isPresented: Binding(get: { selectedPreset != nil }, set: { if !$0 { selectedPreset = nil } })) {
                Button("healthApplyPreset", role: .destructive) {
                    guard let preset = selectedPreset else { return }
                    address = preset.address
                    code = "200"
                    bodySubstring = ""
                    conditions = [.init(value: .init(path: "status.indicator", expectedValue: "none"))]
                    headers = []
                    selectedPreset = nil
                }
                Button("cancel", role: .cancel) { selectedPreset = nil }
            } message: { Text("healthPresetReplaceHint") }
            .alert("preferencesUnavailable", isPresented: $failure) { Button("dismiss", role: .cancel) {} }
        }
    }
}
