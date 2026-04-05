package com.ShareVia.app

import android.net.Uri
import java.util.UUID

enum class ShareMode {
    OFFLINE,
    ONLINE,
}

enum class DrawerDestination {
    HOME,
    DEVICES,
    PROFILE,
    HISTORY,
    SETTINGS,
    ECOSYSTEM,
    DIAGNOSTICS,
    TOOLS,
}

data class ShareProfile(
    val displayName: String,
    val avatarUri: Uri? = null,
)

data class NearbyPeer(
    val endpointId: String,
    val displayName: String,
    val connected: Boolean,
    val lastSeenAt: Long,
)

enum class TransferDirection(val displayLabel: String) {
    OUTGOING("Sent"),
    INCOMING("Received"),
}

enum class TransferStatus(val displayLabel: String) {
    QUEUED("Queued"),
    IN_PROGRESS("In progress"),
    COMPLETED("Completed"),
    FAILED("Failed"),
}

data class TransferItem(
    val transferId: String,
    val endpointId: String,
    val peerName: String,
    val fileName: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val totalBytes: Long,
    val transferredBytes: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val savedPath: String? = null,
    val errorMessage: String? = null,
)

data class TransferHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val peerName: String,
    val direction: TransferDirection,
    val sizeBytes: Long,
    val timestamp: Long,
    val localPath: String? = null,
)

data class ShareViaUiState(
    val destination: DrawerDestination = DrawerDestination.HOME,
    val selectedMode: ShareMode = ShareMode.OFFLINE,
    val profile: ShareProfile = ShareProfile("My Device"),
    val isNearbySessionActive: Boolean = false,
    val isOnlineSessionActive: Boolean = false,
    val onlineRoomId: String? = null,
    val connectedOnlinePeers: Int = 0,
    val peers: List<NearbyPeer> = emptyList(),
    val liveTransfers: List<TransferItem> = emptyList(),
    val history: List<TransferHistoryEntry> = emptyList(),
    val statusMessage: String = "Offline mode ready. Start Nearby to discover devices.",
    val onlineStatusMessage: String = "Online mode ready. Connect to realtime hub to host/join room.",
    val supportsNfc: Boolean = false,
)
