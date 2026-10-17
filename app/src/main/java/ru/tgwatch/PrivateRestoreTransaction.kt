package ru.tgwatch

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Only the random transaction ID crosses into device-protected storage. Payloads remain credential protected. */
internal class PrivateRestoreTransaction(private val marker: File, private val credentials: File,
    private val io: HistoryFileOperations = SystemHistoryFileOperations) {
    @Synchronized fun stage(payload: ByteArray, legacyEndpoint: String?,
        metadata: PrivateRestoreMetadata = PrivateRestoreMetadata()) {
        require(payload.isNotEmpty() && payload.size <= TargetBackupCodec.MAX_PLAINTEXT_BYTES)
        require(legacyEndpoint == null || ExtraProbes.normalizeCustomUrl(legacyEndpoint) == legacyEndpoint)
        check(!marker.exists()) { "A restore is already pending" }
        check(credentials.isDirectory || credentials.mkdirs())
        // A directory without a published marker is an interrupted preparation, never an accepted restore.
        credentials.listFiles()?.filter { it.isDirectory && ID.matches(it.name) }?.forEach {
            check(it.deleteRecursively())
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val directory = File(credentials, id).apply { check(mkdir()) }
        write(File(directory, PAYLOAD), payload)
        write(File(directory, METADATA), metadata.encode())
        if (legacyEndpoint != null) write(File(directory, ENDPOINT), legacyEndpoint.toByteArray(Charsets.UTF_8))
        io.syncDirectory(directory)
        io.syncDirectory(credentials)
        io.syncDirectory(credentials.absoluteFile.parentFile!!)
        commitMarker(id)
    }

    fun recover(apply: (ByteArray, String?) -> Unit): Boolean =
        recoverWithMetadata { bytes, endpoint, _ -> apply(bytes, endpoint) }

    @Synchronized fun recoverWithMetadata(apply: (ByteArray, String?, PrivateRestoreMetadata) -> Unit): Boolean {
        if (!marker.exists()) return false
        val id = marker.inputStream().use { BoundedInput.read(it, ID_LIMIT) }.toString(Charsets.US_ASCII)
        require(ID.matches(id)) { "Invalid restore marker" }
        val directory = File(credentials, id)
        val bytes = File(directory, PAYLOAD).inputStream().use {
            BoundedInput.read(it, TargetBackupCodec.MAX_PLAINTEXT_BYTES)
        }
        val endpointFile = File(directory, ENDPOINT)
        val endpoint = if (endpointFile.exists()) endpointFile.inputStream().use {
            BoundedInput.read(it, ENDPOINT_LIMIT).toString(Charsets.UTF_8)
        } else null
        require(endpoint == null || ExtraProbes.normalizeCustomUrl(endpoint) == endpoint)
        val metadataFile = File(directory, METADATA)
        val metadata = if (metadataFile.exists()) metadataFile.inputStream().use {
            PrivateRestoreMetadata.decode(BoundedInput.read(it, PrivateRestoreMetadata.MAX_BYTES))
        } else PrivateRestoreMetadata()
        try { apply(bytes, endpoint, metadata) } finally { bytes.fill(0) }
        check(marker.delete()) { "Cannot finish restore marker" }
        io.syncDirectory(marker.absoluteFile.parentFile!!)
        check(directory.deleteRecursively()) { "Cannot remove applied private restore" }
        io.syncDirectory(credentials)
        return true
    }

    private fun write(file: File, bytes: ByteArray) = FileOutputStream(file).use {
        it.write(bytes)
        io.syncFile(it.fd)
    }

    private fun commitMarker(id: String) {
        val directory = marker.absoluteFile.parentFile!!
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, marker.name + ".tmp")
        try {
            write(temporary, id.toByteArray(Charsets.US_ASCII))
            io.replace(temporary, marker)
            io.syncDirectory(directory)
            io.syncDirectory(directory.parentFile!!)
        } finally { temporary.delete() }
    }

    companion object {
        private val ID = Regex("[a-f0-9]{32}")
        private const val ID_LIMIT = 32
        private const val ENDPOINT_LIMIT = 256
        private const val PAYLOAD = "snapshot.json"
        private const val ENDPOINT = "legacy-endpoint"
        private const val METADATA = "metadata"
    }
}
