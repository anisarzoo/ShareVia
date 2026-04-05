package com.ShareVia.app

import android.app.Application
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
) : AndroidViewModel(application), NearbyOfflineShareManager.Listener {
    private val profileStore = ProfileStore(application.applicationContext)
    private val historyStore = TransferHistoryStore(application.applicationContext)
    private val nearbyManager = NearbyOfflineShareManager(application.applicationContext, this)

    private val peersByEndpoint: MutableMap<String, NearbyPeer> = mutableMapOf()
    private val transfersById: MutableMap<String, TransferItem> = mutableMapOf()
    private val historyRecordedTransferIds: MutableSet<String> = mutableSetOf()

    private val _uiState =
        MutableStateFlow(
            ShareViaUiState(
                profile = profileStore.load(),
                history = historyStore.load(),
            ),
        )
    val uiState = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 12)
    val messages = _messages.asSharedFlow()

    fun changeDestination(destination: DrawerDestination) {
        _uiState.value = _uiState.value.copy(destination = destination)
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

    fun saveProfileName(inputName: String) {
        val name =
            inputName
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(30)
                .ifBlank { "My Device" }
        val next = _uiState.value.profile.copy(displayName = name)
        profileStore.save(next)
        _uiState.value = _uiState.value.copy(profile = next, statusMessage = "Profile updated. Restart Nearby to re-announce.")
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

    override fun onCleared() {
        super.onCleared()
        nearbyManager.destroy()
    }
}
