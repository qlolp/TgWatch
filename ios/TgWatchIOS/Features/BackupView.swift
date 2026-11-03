import SwiftUI
import UniformTypeIdentifiers
import TgWatchCore

struct BackupView: View {
    @ObservedObject var catalog: ServiceCatalog

    // Export state
    @State private var exportPassword = ""
    @State private var exportConfirmPassword = ""
    @State private var isExporting = false
    @State private var exportFileURL: URL?
    @State private var exportError: String?

    // Import state
    @State private var isImportingPicker = false
    @State private var importData: Data?
    @State private var importPassword = ""
    @State private var importPreview: EncryptedBackupPayload?
    @State private var isRestoring = false
    @State private var restoreConfirmed = false
    @State private var restoreSuccess = false
    @State private var importError: String?
    @State private var importGeneration = 0
    @State private var isPreviewing = false

    var body: some View {
        Form {
            exportSection
            importSection
        }
        .navigationTitle("backupTitle")
        .onChange(of: importPassword) { _, _ in
            importGeneration += 1
            importPreview = nil
            restoreConfirmed = false
        }
        .fileImporter(
            isPresented: $isImportingPicker,
            allowedContentTypes: [.data, .item]
        ) { result in
            handleFileImport(result)
        }
        .confirmationDialog(
            "confirmRestore",
            isPresented: $restoreConfirmed,
            titleVisibility: .visible
        ) {
            Button("confirmRestore", role: .destructive) {
                performRestore()
            }
        } message: {
            Text("backupReplaceWarning")
        }
    }

    // MARK: - Export Section

    private var exportSection: some View {
        Section("backupSectionExport") {
            Text("backupPasswordPrompt")
                .font(.footnote)
                .foregroundStyle(.secondary)

            SecureField("backupPasswordPlaceholder", text: $exportPassword)
            SecureField("backupPasswordConfirmation", text: $exportConfirmPassword)

            if !exportValidationMessage.isEmpty {
                Text(exportValidationMessage)
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            Button {
                generateBackup()
            } label: {
                HStack {
                    if isExporting {
                        ProgressView()
                    } else {
                        Text("exportBackup")
                    }
                }
            }
            .disabled(isExporting || !isExportValid)

            if let exportFileURL {
                HStack {
                    ShareLink(item: exportFileURL) {
                        Label("shareBackup", systemImage: "square.and.arrow.up")
                    }
                }
            }

            if let exportError {
                Text(exportError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }

    private var isExportValid: Bool {
        exportPassword.count >= 12 &&
        exportPassword.count <= 256 &&
        exportPassword == exportConfirmPassword
    }

    private var exportValidationMessage: String {
        if exportPassword.isEmpty { return "" }
        if exportPassword.count < 12 {
            return NSLocalizedString("backupPasswordTooShort", comment: "")
        }
        if !exportConfirmPassword.isEmpty && exportPassword != exportConfirmPassword {
            return NSLocalizedString("backupPasswordMismatch", comment: "")
        }
        return ""
    }

    private func generateBackup() {
        guard isExportValid else { return }
        isExporting = true
        exportError = nil
        exportFileURL = nil

        let password = exportPassword
        Task {
            do {
                let data = try await catalog.createBackup(password: password)

                let formatter = DateFormatter()
                formatter.dateFormat = "yyyyMMdd-HHmmss"
                let dateString = formatter.string(from: Date())
                let tempURL = FileManager.default.temporaryDirectory
                    .appendingPathComponent("tgwatch-backup-\(dateString).tgwbackup")

                try data.write(to: tempURL, options: .atomic)
                self.exportFileURL = tempURL
                self.isExporting = false
            } catch {
                self.exportError = error.localizedDescription
                self.isExporting = false
            }
        }
    }

    // MARK: - Import Section

    private var importSection: some View {
        Section("backupSectionImport") {
            Button {
                isImportingPicker = true
            } label: {
                Label("importBackup", systemImage: "square.and.arrow.down")
            }
            .disabled(isRestoring || isPreviewing)

            if importData != nil {
                SecureField("backupPasswordPlaceholder", text: $importPassword)
                    .disabled(isRestoring || isPreviewing)

                Button("backupImportPreview") {
                    previewImport()
                }
                .disabled(!(12...256).contains(importPassword.count) || isRestoring || isPreviewing)
            }

            if let preview = importPreview?.preview {
                VStack(alignment: .leading, spacing: 6) {
                    Text("backupImportPreview")
                        .font(.headline)
                    LabeledContent("backupCreatedAt", value: preview.createdAt.formatted(date: .abbreviated, time: .shortened))
                    LabeledContent("backupAppVersion", value: preview.appVersion)
                    LabeledContent("backupServicesCount", value: "\(preview.serviceCount)")
                    LabeledContent("backupEntriesCount", value: "\(preview.totalHistoryEntries)")

                    if !preview.serviceNames.isEmpty {
                        Text(preview.serviceNames.joined(separator: ", "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Button("confirmRestore", role: .destructive) {
                        restoreConfirmed = true
                    }
                    .padding(.top, 4)
                }
                .padding(.vertical, 4)
            }

            if isRestoring {
                ProgressView()
            }

            if restoreSuccess {
                Label("restoreSuccess", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            }

            if let importError {
                Text(importError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
    }

    private func handleFileImport(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            importGeneration += 1
            restoreConfirmed = false
            importError = nil
            importPreview = nil
            importData = nil
            importPassword = ""
            restoreSuccess = false

            let accessing = url.startAccessingSecurityScopedResource()
            defer {
                if accessing { url.stopAccessingSecurityScopedResource() }
            }

            do {
                let data = try BackupFileStore.read(url, limit: EncryptedBackup.maxEncryptedSize)
                self.importData = data
            } catch {
                importError = error.localizedDescription
            }

        case .failure(let error):
            importError = error.localizedDescription
        }
    }

    private func previewImport() {
        guard let data = importData else { return }
        importError = nil
        importPreview = nil

        let password = importPassword
        let generation = importGeneration
        isPreviewing = true
        Task {
            defer { isPreviewing = false }
            do {
                let payload = try await catalog.previewBackup(data: data, password: password)

                guard generation == importGeneration, importData == data, importPassword == password else { return }
                self.importPreview = payload
            } catch {
                self.importError = error.localizedDescription
            }
        }
    }

    private func performRestore() {
        guard let payload = importPreview else { return }
        isRestoring = true
        importError = nil

        Task {
            do {
                try await catalog.restore(payload: payload)
                self.isRestoring = false
                self.restoreSuccess = true
                self.importPreview = nil
                self.importData = nil
                self.importPassword = ""
            } catch {
                self.importError = error.localizedDescription
                self.isRestoring = false
            }
        }
    }
}
