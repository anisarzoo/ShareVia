package com.ShareVia.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class NearbyOfflineShareManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onSessionStateChanged(active: Boolean)

        fun onStatusMessage(message: String)

        fun onPeerUpsert(peer: NearbyPeer)

        fun onPeerRemoved(endpointId: String)

        fun onTransferUpdated(transfer: TransferItem)
    }

    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var localProfile: ShareProfile = ShareProfile(displayName = "My Device")
    private var active = false
    private val peers: MutableMap<String, NearbyPeer> = mutableMapOf()
    private val peerNames: MutableMap<String, String> = mutableMapOf()
    private val connectedEndpoints: MutableSet<String> = mutableSetOf()
    private val transferByPayloadId: MutableMap<Long, TransferItem> = mutableMapOf()
    private val incomingFileByPayloadId: MutableMap<Long, Payload.File> = mutableMapOf()
    private val incomingMetaByPayloadId: MutableMap<Long, IncomingFileMeta> = mutableMapOf()
    private val outgoingTempFilesByPayloadId: MutableMap<Long, File> = mutableMapOf()

    @SuppressLint("MissingPermission")
    fun start(profile: ShareProfile) {
        stop()
        localProfile = profile
        active = true

        val strategy = Strategy.P2P_CLUSTER
        val endpointName = buildEndpointName(profile.displayName)

        val advertiseTask =
            connectionsClient.startAdvertising(
                endpointName,
                SERVICE_ID,
                connectionLifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(strategy).build(),
            )
        advertiseTask.addOnFailureListener { error ->
            listener.onStatusMessage("Advertising failed: ${error.message ?: "unknown error"}")
        }

        val discoverTask =
            connectionsClient.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(strategy).build(),
            )
        discoverTask.addOnFailureListener { error ->
            listener.onStatusMessage("Discovery failed: ${error.message ?: "unknown error"}")
        }

        listener.onSessionStateChanged(true)
        listener.onStatusMessage("Nearby session started. Searching for offline peers.")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!active && peers.isEmpty()) {
            return
        }

        runCatching { connectionsClient.stopAdvertising() }
        runCatching { connectionsClient.stopDiscovery() }
        runCatching { connectionsClient.stopAllEndpoints() }

        val endpointIdsToRemove =
            synchronized(lock) {
                val ids = peers.keys.toList()
                peers.clear()
                peerNames.clear()
                connectedEndpoints.clear()
                transferByPayloadId.clear()
                incomingFileByPayloadId.clear()
                incomingMetaByPayloadId.clear()
                outgoingTempFilesByPayloadId.values.forEach { file -> runCatching { file.delete() } }
                outgoingTempFilesByPayloadId.clear()
                ids
            }
        endpointIdsToRemove.forEach(listener::onPeerRemoved)

        active = false
        listener.onSessionStateChanged(false)
        listener.onStatusMessage("Nearby session stopped.")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    fun connect(endpointId: String) {
        if (!active) {
            listener.onStatusMessage("Start Nearby before pairing with a device.")
            return
        }
        val requesterName = buildEndpointName(localProfile.displayName)
        connectionsClient
            .requestConnection(requesterName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { error ->
                listener.onStatusMessage("Pair request failed: ${error.message ?: "unknown error"}")
            }
    }

    @SuppressLint("MissingPermission")
    fun sendFile(endpointId: String, sourceUri: Uri) {
        if (!connectedEndpoints.contains(endpointId)) {
            listener.onStatusMessage("Pair with the device before sending files.")
            return
        }

        scope.launch {
            val staged =
                runCatching { stageOutgoingFile(sourceUri) }
                    .onFailure {
                        listener.onStatusMessage("Unable to open selected file.")
                    }
                    .getOrNull() ?: return@launch

            val payload = Payload.fromFile(staged.stagedFile)
            val payloadId = payload.id
            val peerName = synchronized(lock) { peerNames[endpointId] }.orEmpty().ifBlank { "Nearby device" }
            val transfer =
                TransferItem(
                    transferId = "out-$payloadId",
                    endpointId = endpointId,
                    peerName = peerName,
                    fileName = staged.fileName,
                    direction = TransferDirection.OUTGOING,
                    status = TransferStatus.QUEUED,
                    totalBytes = staged.fileSize,
                    transferredBytes = 0L,
                )

            synchronized(lock) {
                transferByPayloadId[payloadId] = transfer
                outgoingTempFilesByPayloadId[payloadId] = staged.stagedFile
            }
            listener.onTransferUpdated(transfer)

            val meta =
                JSONObject()
                    .put("type", TYPE_FILE_META)
                    .put("payloadId", payloadId)
                    .put("name", staged.fileName)
                    .put("size", staged.fileSize)
                    .put("mime", staged.mimeType ?: "application/octet-stream")
                    .put("senderName", localProfile.displayName)
            connectionsClient
                .sendPayload(endpointId, Payload.fromBytes(meta.toString().toByteArray()))
                .addOnFailureListener { error ->
                    markTransferFailed(payloadId, "Metadata send failed: ${error.message ?: "unknown error"}")
                }

            connectionsClient
                .sendPayload(endpointId, payload)
                .addOnFailureListener { error ->
                    markTransferFailed(payloadId, "File send failed: ${error.message ?: "unknown error"}")
                }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendHello(endpointId: String) {
        val hello =
            JSONObject()
                .put("type", TYPE_HELLO)
                .put("name", localProfile.displayName)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(hello.toString().toByteArray()))
    }

    private val endpointDiscoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                val name = parseEndpointName(info.endpointName)
                val peer =
                    NearbyPeer(
                        endpointId = endpointId,
                        displayName = name,
                        connected = connectedEndpoints.contains(endpointId),
                        lastSeenAt = System.currentTimeMillis(),
                    )
                synchronized(lock) {
                    peers[endpointId] = peer
                    peerNames[endpointId] = name
                }
                listener.onPeerUpsert(peer)
            }

            override fun onEndpointLost(endpointId: String) {
                synchronized(lock) {
                    peers.remove(endpointId)
                    peerNames.remove(endpointId)
                    connectedEndpoints.remove(endpointId)
                }
                listener.onPeerRemoved(endpointId)
            }
        }

    private val connectionLifecycleCallback =
        object : ConnectionLifecycleCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
                val endpointName = connectionInfo.endpointName.orEmpty()
                if (!endpointName.startsWith(ENDPOINT_PREFIX)) {
                    connectionsClient.rejectConnection(endpointId)
                    return
                }

                val name = parseEndpointName(endpointName)
                synchronized(lock) {
                    peerNames[endpointId] = name
                    peers[endpointId] =
                        NearbyPeer(
                            endpointId = endpointId,
                            displayName = name,
                            connected = false,
                            lastSeenAt = System.currentTimeMillis(),
                        )
                }
                listener.onPeerUpsert(peers.getValue(endpointId))

                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                val status = result.status.statusCode
                if (status == ConnectionsStatusCodes.STATUS_OK) {
                    synchronized(lock) {
                        connectedEndpoints += endpointId
                        val peer = peers[endpointId]
                        if (peer != null) {
                            peers[endpointId] =
                                peer.copy(
                                    connected = true,
                                    lastSeenAt = System.currentTimeMillis(),
                                )
                        }
                    }
                    peers[endpointId]?.let(listener::onPeerUpsert)
                    sendHello(endpointId)
                    listener.onStatusMessage("Paired with ${peerNames[endpointId].orEmpty().ifBlank { "nearby device" }}.")
                    return
                }

                listener.onStatusMessage("Pairing failed (${connectionStatusLabel(status)}).")
            }

            override fun onDisconnected(endpointId: String) {
                synchronized(lock) {
                    connectedEndpoints.remove(endpointId)
                    val peer = peers[endpointId]
                    if (peer != null) {
                        peers[endpointId] =
                            peer.copy(
                                connected = false,
                                lastSeenAt = System.currentTimeMillis(),
                            )
                    }
                }
                peers[endpointId]?.let(listener::onPeerUpsert)
                listener.onStatusMessage("Disconnected from ${peerNames[endpointId].orEmpty().ifBlank { "nearby device" }}.")
            }
        }

    private val payloadCallback =
        object : com.google.android.gms.nearby.connection.PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val bytes = payload.asBytes() ?: return
                        handleBytesPayload(endpointId, bytes)
                    }

                    Payload.Type.FILE -> {
                        val payloadFile = payload.asFile() ?: return
                        synchronized(lock) {
                            incomingFileByPayloadId[payload.id] = payloadFile
                            if (transferByPayloadId[payload.id] == null) {
                                transferByPayloadId[payload.id] =
                                    TransferItem(
                                        transferId = "in-${payload.id}",
                                        endpointId = endpointId,
                                        peerName = peerNames[endpointId].orEmpty().ifBlank { "Nearby device" },
                                        fileName = "Incoming file",
                                        direction = TransferDirection.INCOMING,
                                        status = TransferStatus.QUEUED,
                                        totalBytes = 0L,
                                        transferredBytes = 0L,
                                    )
                            }
                        }
                    }

                    else -> Unit
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                val payloadId = update.payloadId
                val existing =
                    synchronized(lock) {
                        transferByPayloadId[payloadId]
                    } ?: return

                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        val next =
                            existing.copy(
                                status = TransferStatus.IN_PROGRESS,
                                transferredBytes = update.bytesTransferred,
                                totalBytes = maxOf(existing.totalBytes, update.totalBytes),
                            )
                        synchronized(lock) {
                            transferByPayloadId[payloadId] = next
                        }
                        listener.onTransferUpdated(next)
                    }

                    PayloadTransferUpdate.Status.SUCCESS -> {
                        val incomingFile = synchronized(lock) { incomingFileByPayloadId[payloadId] }
                        if (incomingFile != null) {
                            scope.launch { finalizeIncomingTransfer(endpointId, payloadId, update.bytesTransferred) }
                        } else {
                            finalizeOutgoingTransfer(payloadId, update.bytesTransferred)
                        }
                    }

                    PayloadTransferUpdate.Status.FAILURE,
                    PayloadTransferUpdate.Status.CANCELED,
                    -> {
                        markTransferFailed(payloadId, "Transfer interrupted.")
                    }
                }
            }
        }

    private fun handleBytesPayload(endpointId: String, bytes: ByteArray) {
        val payload =
            runCatching {
                JSONObject(String(bytes, Charsets.UTF_8))
            }.getOrNull() ?: return

        when (payload.optString("type")) {
            TYPE_HELLO -> {
                val announcedName = sanitizeDisplayName(payload.optString("name", "Nearby device"))
                val peer =
                    synchronized(lock) {
                        peerNames[endpointId] = announcedName
                        val existing =
                            peers[endpointId]
                                ?: NearbyPeer(
                                    endpointId = endpointId,
                                    displayName = announcedName,
                                    connected = connectedEndpoints.contains(endpointId),
                                    lastSeenAt = System.currentTimeMillis(),
                                )
                        val updated = existing.copy(displayName = announcedName, lastSeenAt = System.currentTimeMillis())
                        peers[endpointId] = updated
                        updated
                    }
                listener.onPeerUpsert(peer)
            }

            TYPE_FILE_META -> {
                val payloadId = payload.optLong("payloadId", -1L)
                if (payloadId <= 0L) return
                val fileName = sanitizeFileName(payload.optString("name", "incoming.bin"))
                val totalBytes = payload.optLong("size", 0L)
                val mimeType = payload.optString("mime", "application/octet-stream")
                val meta =
                    IncomingFileMeta(
                        fileName = fileName,
                        sizeBytes = totalBytes,
                        mimeType = mimeType,
                        senderName = payload.optString("senderName", peerNames[endpointId].orEmpty()),
                    )
                val transfer =
                    synchronized(lock) {
                        incomingMetaByPayloadId[payloadId] = meta
                        val existing = transferByPayloadId[payloadId]
                        val next =
                            if (existing == null) {
                                TransferItem(
                                    transferId = "in-$payloadId",
                                    endpointId = endpointId,
                                    peerName = meta.senderName.ifBlank { "Nearby device" },
                                    fileName = fileName,
                                    direction = TransferDirection.INCOMING,
                                    status = TransferStatus.QUEUED,
                                    totalBytes = totalBytes,
                                    transferredBytes = 0L,
                                )
                            } else {
                                existing.copy(
                                    fileName = fileName,
                                    totalBytes = maxOf(existing.totalBytes, totalBytes),
                                    peerName = meta.senderName.ifBlank { existing.peerName },
                                )
                            }
                        transferByPayloadId[payloadId] = next
                        next
                    }
                listener.onTransferUpdated(transfer)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun finalizeIncomingTransfer(endpointId: String, payloadId: Long, bytesTransferred: Long) {
        val incomingFile = synchronized(lock) { incomingFileByPayloadId.remove(payloadId) } ?: return
        val currentTransfer = synchronized(lock) { transferByPayloadId[payloadId] } ?: return
        val meta = synchronized(lock) { incomingMetaByPayloadId[payloadId] }
        val destination =
            runCatching {
                val incomingDir =
                    File(appContext.getExternalFilesDir(null), "received").apply {
                        mkdirs()
                    }
                val finalName = sanitizeFileName(meta?.fileName ?: currentTransfer.fileName)
                uniqueTargetFile(incomingDir, finalName)
            }.getOrNull()

        if (destination == null) {
            markTransferFailed(payloadId, "Unable to create destination file.")
            return
        }

        val copyOutcome =
            runCatching {
                val existingJavaFile = incomingFile.asJavaFile()
                if (existingJavaFile != null && existingJavaFile.exists()) {
                    existingJavaFile.copyTo(destination, overwrite = true)
                } else {
                    val descriptor = incomingFile.asParcelFileDescriptor()
                    checkNotNull(descriptor) { "Incoming payload missing file descriptor." }
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        FileOutputStream(destination).use { output -> input.copyTo(output) }
                    }
                }
            }
        if (copyOutcome.isFailure) {
            markTransferFailed(payloadId, "Failed to save incoming file.")
            return
        }

        val next =
            currentTransfer.copy(
                status = TransferStatus.COMPLETED,
                transferredBytes = bytesTransferred.coerceAtLeast(currentTransfer.totalBytes),
                totalBytes = maxOf(currentTransfer.totalBytes, bytesTransferred),
                completedAt = System.currentTimeMillis(),
                savedPath = destination.absolutePath,
            )

        synchronized(lock) {
            transferByPayloadId[payloadId] = next
            incomingMetaByPayloadId.remove(payloadId)
        }
        listener.onTransferUpdated(next)
        listener.onStatusMessage("Received ${next.fileName} from ${peerNames[endpointId].orEmpty().ifBlank { "nearby device" }}.")
    }

    private fun finalizeOutgoingTransfer(payloadId: Long, bytesTransferred: Long) {
        val currentTransfer = synchronized(lock) { transferByPayloadId[payloadId] } ?: return
        val next =
            currentTransfer.copy(
                status = TransferStatus.COMPLETED,
                transferredBytes = bytesTransferred.coerceAtLeast(currentTransfer.totalBytes),
                totalBytes = maxOf(currentTransfer.totalBytes, bytesTransferred),
                completedAt = System.currentTimeMillis(),
            )
        synchronized(lock) {
            transferByPayloadId[payloadId] = next
            outgoingTempFilesByPayloadId.remove(payloadId)?.let { runCatching { it.delete() } }
        }
        listener.onTransferUpdated(next)
        listener.onStatusMessage("Sent ${next.fileName} to ${next.peerName}.")
    }

    private fun markTransferFailed(payloadId: Long, error: String) {
        val failed =
            synchronized(lock) {
                val existing = transferByPayloadId[payloadId] ?: return@synchronized null
                val next =
                    existing.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = error,
                        completedAt = System.currentTimeMillis(),
                    )
                transferByPayloadId[payloadId] = next
                incomingFileByPayloadId.remove(payloadId)
                incomingMetaByPayloadId.remove(payloadId)
                outgoingTempFilesByPayloadId.remove(payloadId)?.let { runCatching { it.delete() } }
                next
            } ?: return
        listener.onTransferUpdated(failed)
        listener.onStatusMessage(error)
    }

    private fun stageOutgoingFile(uri: Uri): StagedOutgoingFile {
        val resolver = appContext.contentResolver
        var fileName = "sharevia-${System.currentTimeMillis()}.bin"
        var fileSize = 0L
        var mimeType = resolver.getType(uri)

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    fileName = sanitizeFileName(cursor.getString(nameIndex).orEmpty())
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        }

        val staged =
            File(appContext.cacheDir, "out_${System.currentTimeMillis()}_${fileName.take(48)}")
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(staged).use { output -> input.copyTo(output) }
        } ?: error("Cannot open selected file.")

        if (fileSize <= 0L) {
            fileSize = staged.length()
        }
        if (mimeType.isNullOrBlank()) {
            mimeType = "application/octet-stream"
        }

        return StagedOutgoingFile(
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            stagedFile = staged,
        )
    }

    private fun buildEndpointName(displayName: String): String {
        val safe = sanitizeDisplayName(displayName)
        return "$ENDPOINT_PREFIX$safe"
    }

    private fun parseEndpointName(endpointName: String?): String {
        val raw = endpointName.orEmpty().trim()
        if (raw.startsWith(ENDPOINT_PREFIX)) {
            return sanitizeDisplayName(raw.removePrefix(ENDPOINT_PREFIX))
        }
        return sanitizeDisplayName(raw)
    }

    private fun sanitizeDisplayName(raw: String): String =
        raw
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(28)
            .ifBlank { "Nearby device" }

    private fun sanitizeFileName(raw: String): String {
        val safe =
            raw
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "incoming.bin" }
        return safe.take(80)
    }

    private fun uniqueTargetFile(directory: File, baseName: String): File {
        var candidate = File(directory, baseName)
        if (!candidate.exists()) return candidate

        val dotIndex = baseName.lastIndexOf('.')
        val stem = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val ext = if (dotIndex > 0) baseName.substring(dotIndex) else ""
        var attempt = 1
        while (candidate.exists()) {
            candidate = File(directory, "$stem ($attempt)$ext")
            attempt += 1
        }
        return candidate
    }

    private fun connectionStatusLabel(code: Int): String =
        when (code) {
            ConnectionsStatusCodes.STATUS_OK -> "ok"
            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> "rejected"
            ConnectionsStatusCodes.STATUS_ERROR -> "error"
            else -> "code $code"
        }

    private data class IncomingFileMeta(
        val fileName: String,
        val sizeBytes: Long,
        val mimeType: String,
        val senderName: String,
    )

    private data class StagedOutgoingFile(
        val fileName: String,
        val fileSize: Long,
        val mimeType: String?,
        val stagedFile: File,
    )

    companion object {
        private const val SERVICE_ID = "com.sharevia.offline.transfer"
        private const val ENDPOINT_PREFIX = "SV2|"
        private const val TYPE_HELLO = "hello"
        private const val TYPE_FILE_META = "file-meta"
    }
}
