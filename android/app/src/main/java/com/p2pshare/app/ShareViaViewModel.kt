package com.ShareVia.app

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareViaViewModel(
    application: Application,
) : AndroidViewModel(application), NearbyOfflineShareManager.Listener, OnlineTransportManager.Listener {
    private val appContext = application.applicationContext
    private val profileStore = ProfileStore(appContext)
    private val historyStore = TransferHistoryStore(appContext)
    private val nearbyManager = NearbyOfflineShareManager(appContext, this)
    private val onlineManager = OnlineTransportManager(appContext, this)

    private val peersByEndpoint: MutableMap<String, NearbyPeer> = mutableMapOf()
    private val transfersById: MutableMap<String, TransferItem> = mutableMapOf()
    private val historyRecordedTransferIds: MutableSet<String> = mutableSetOf()

    private val _uiState =
        MutableStateFlow(
            ShareViaUiState(
                profile = profileStore.load(),
                history = historyStore.load(),
                supportsNfc = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
            ),
        )
    val uiState = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 12)
    val messages = _messages.asSharedFlow()

    fun changeDestination(destination: DrawerDestination) {
        _uiState.value = _uiState.value.copy(destination = destination)
    }

    fun selectMode(mode: ShareMode) {
        _uiState.value = _uiState.value.copy(selectedMode = mode)
        pushMessage(
            when (mode) {
                ShareMode.OFFLINE -> "Offline mode selected."
                ShareMode.ONLINE -> "Online mode selected."
            },
        )
    }

    fun startNearbySession() {
        nearbyManager.start(_uiState.value.profile)
    }

    fun stopNearbySession() {
        nearbyManager.stop()
    }

    fun connect(endpointId: String) {
        nearbyManager.connect(endpointId)
    }

    fun sendFile(endpointId: String, uri: Uri) {
        nearbyManager.sendFile(endpointId, uri)
    }

    fun startOnlineSession() {
        onlineManager.start(_uiState.value.profile)
    }

    fun stopOnlineSession() {
        onlineManager.stop()
    }

    fun hostOnlineRoom() {
        val roomId = generateRoomCode()
        onlineManager.hostRoom(roomId)
    }

    fun joinOnlineRoom(roomId: String) {
        onlineManager.joinRoom(roomId)
    }

    fun leaveOnlineRoom() {
        val current = _uiState.value.onlineRoomId ?: return
        onlineManager.leaveRoom(current)
    }

    fun saveProfileName(inputName: String) {
        val name =
            inputName
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(30)
                .ifBlank { "My Device" }
        val next = _uiState.value.profile.copy(displayName = name)
        profileStore.save(next)
        _uiState.value =
            _uiState.value.copy(
                profile = next,
                statusMessage = "Profile updated. Restart Nearby to re-announce.",
                onlineStatusMessage = "Profile updated. Reconnect online to refresh room identity.",
            )
        pushMessage("Profile saved.")
    }

    fun updateAvatar(uri: Uri?) {
        val next = _uiState.value.profile.copy(avatarUri = uri)
        profileStore.save(next)
        _uiState.value = _uiState.value.copy(profile = next)
        if (uri != null) {
            pushMessage("Display picture updated.")
        }
    }

    fun clearHistory() {
        historyStore.clear()
        _uiState.value = _uiState.value.copy(history = emptyList())
    }

    fun pushMessage(message: String) {
        _messages.tryEmit(message)
    }

    override fun onSessionStateChanged(active: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isNearbySessionActive = active)
        }
    }

    override fun onStatusMessage(message: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusMessage = message)
            _messages.emit(message)
        }
    }

    override fun onPeerUpsert(peer: NearbyPeer) {
        viewModelScope.launch(Dispatchers.Default) {
            peersByEndpoint[peer.endpointId] = peer
            publishPeers()
        }
    }

    override fun onPeerRemoved(endpointId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            peersByEndpoint.remove(endpointId)
            publishPeers()
        }
    }

    override fun onTransferUpdated(transfer: TransferItem) {
        viewModelScope.launch(Dispatchers.Default) {
            transfersById[transfer.transferId] = transfer
            publishTransfers()
            maybeRecordHistory(transfer)
        }
    }

    override fun onOnlineSessionStateChanged(active: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOnlineSessionActive = active)
        }
    }

    override fun onOnlineStatusMessage(message: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(onlineStatusMessage = message)
            _messages.emit(message)
        }
    }

    override fun onOnlineRoomChanged(roomId: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(onlineRoomId = roomId)
        }
    }

    override fun onOnlinePeerCountChanged(count: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(connectedOnlinePeers = count)
        }
    }

    private suspend fun publishPeers() {
        val sorted =
            peersByEndpoint.values
                .sortedWith(
                    compareByDescending<NearbyPeer> { it.connected }
                        .thenBy { it.displayName.lowercase() },
                )
        withContext(Dispatchers.Main.immediate) {
            _uiState.value = _uiState.value.copy(peers = sorted)
        }
    }

    private suspend fun publishTransfers() {
        val sorted = transfersById.values.sortedByDescending { it.startedAt }
        withContext(Dispatchers.Main.immediate) {
            _uiState.value = _uiState.value.copy(liveTransfers = sorted.take(16))
        }
    }

    private suspend fun maybeRecordHistory(transfer: TransferItem) {
        if (transfer.status != TransferStatus.COMPLETED) {
            return
        }
        if (!historyRecordedTransferIds.add(transfer.transferId)) {
            return
        }

        val historyEntry =
            TransferHistoryEntry(
                fileName = transfer.fileName,
                peerName = transfer.peerName,
                direction = transfer.direction,
                sizeBytes = transfer.totalBytes,
                timestamp = transfer.completedAt ?: System.currentTimeMillis(),
                localPath = transfer.savedPath,
            )
        historyStore.append(historyEntry)
        withContext(Dispatchers.Main.immediate) {
            _uiState.value = _uiState.value.copy(history = historyStore.load())
        }
    }

    private fun generateRoomCode(): String {
        return (100000..999999).random().toString()
    }

    override fun onCleared() {
        super.onCleared()
        nearbyManager.destroy()
        onlineManager.destroy()
    }
}
