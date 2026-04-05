package com.ShareVia.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OnlineTransportManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onOnlineSessionStateChanged(active: Boolean)

        fun onOnlineStatusMessage(message: String)

        fun onOnlineRoomChanged(roomId: String?)

        fun onOnlinePeerCountChanged(count: Int)
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient =
        OkHttpClient
            .Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var isActive = false
    private var roomId: String? = null
    private val roomPeers: MutableSet<String> = mutableSetOf()
    private var localProfile: ShareProfile = ShareProfile("My Device")

    fun start(profile: ShareProfile) {
        stop()
        localProfile = profile

        val request =
            Request
                .Builder()
                .url(buildRealtimeUrl())
                .build()

        socket = httpClient.newWebSocket(request, realtimeListener)
    }

    fun stop() {
        synchronized(lock) {
            socket?.close(1000, "client-stop")
            socket = null
            isActive = false
            roomId = null
            roomPeers.clear()
        }
        listener.onOnlineSessionStateChanged(false)
        listener.onOnlineRoomChanged(null)
        listener.onOnlinePeerCountChanged(0)
    }

    fun destroy() {
        stop()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    fun hostRoom(nextRoomId: String) {
        val normalized = nextRoomId.trim().take(12)
        if (normalized.isBlank()) {
            listener.onOnlineStatusMessage("Room code is required.")
            return
        }
        val payload =
            JSONObject()
                .put("roomId", normalized)
                .put("mode", "online")
                .put("transportHints", listOf("webrtc", "relay"))
        sendEvent("room.host", payload)
    }

    fun joinRoom(nextRoomId: String) {
        val normalized = nextRoomId.trim().take(12)
        if (normalized.isBlank()) {
            listener.onOnlineStatusMessage("Room code is required.")
            return
        }
        val payload = JSONObject().put("roomId", normalized)
        sendEvent("room.join", payload)
    }

    fun leaveRoom(currentRoomId: String) {
        if (currentRoomId.isBlank()) return
        sendEvent("room.leave", JSONObject().put("roomId", currentRoomId))
        synchronized(lock) {
            if (roomId == currentRoomId) {
                roomId = null
                roomPeers.clear()
            }
        }
        listener.onOnlineRoomChanged(null)
        listener.onOnlinePeerCountChanged(0)
    }

    private fun sendAuthHello() {
        val payload =
            JSONObject()
                .put("deviceId", getOrCreateDeviceId())
                .put("platform", "android")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("sessionToken", prefs.getString(KEY_SESSION_TOKEN, "") ?: "")
                .put("displayName", localProfile.displayName)
        sendEvent("auth.hello", payload)
    }

    private fun sendEvent(event: String, payload: JSONObject) {
        val envelope =
            JSONObject()
                .put("event", event)
                .put("payload", payload)

        val ws =
            synchronized(lock) {
                socket
            } ?: run {
                listener.onOnlineStatusMessage("Online socket is not connected.")
                return
            }

        val sent = ws.send(envelope.toString())
        if (!sent) {
            listener.onOnlineStatusMessage("Failed to send $event.")
        }
    }

    private val realtimeListener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) { isActive = true }
                listener.onOnlineSessionStateChanged(true)
                listener.onOnlineStatusMessage("Online hub connected.")
                sendAuthHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                val event = message.optString("event")
                val payload = message.optJSONObject("payload") ?: JSONObject()

                when (event) {
                    "auth.hello.ack" -> {
                        listener.onOnlineStatusMessage("Online identity synced.")
                    }

                    "room.hosted",
                    "room.joined",
                    -> {
                        val nextRoomId = payload.optString("roomId").trim()
                        synchronized(lock) {
                            roomId = nextRoomId.ifBlank { null }
                        }
                        listener.onOnlineRoomChanged(nextRoomId.ifBlank { null })
                        listener.onOnlineStatusMessage("Connected to room $nextRoomId.")
                    }

                    "room.member" -> {
                        val action = payload.optString("action")
                        val peerDevice = payload.optString("deviceId")
                        synchronized(lock) {
                            when (action) {
                                "joined",
                                "host-online",
                                -> {
                                    if (peerDevice.isNotBlank() && peerDevice != getOrCreateDeviceId()) {
                                        roomPeers += peerDevice
                                    }
                                }

                                "left" -> {
                                    if (peerDevice.isNotBlank()) {
                                        roomPeers -= peerDevice
                                    }
                                }
                            }
                            listener.onOnlinePeerCountChanged(roomPeers.size)
                        }
                    }

                    "route.missed" -> {
                        listener.onOnlineStatusMessage("Direct route missed. Relay fallback can be applied.")
                    }

                    "server.error" -> {
                        listener.onOnlineStatusMessage(
                            payload.optString("message", "Realtime server error."),
                        )
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    isActive = false
                    roomId = null
                    roomPeers.clear()
                }
                listener.onOnlineSessionStateChanged(false)
                listener.onOnlineRoomChanged(null)
                listener.onOnlinePeerCountChanged(0)
                listener.onOnlineStatusMessage("Online hub closing: $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    isActive = false
                    roomId = null
                    roomPeers.clear()
                    socket = null
                }
                listener.onOnlineSessionStateChanged(false)
                listener.onOnlineRoomChanged(null)
                listener.onOnlinePeerCountChanged(0)
                listener.onOnlineStatusMessage("Online hub disconnected.")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    isActive = false
                    roomId = null
                    roomPeers.clear()
                    socket = null
                }
                listener.onOnlineSessionStateChanged(false)
                listener.onOnlineRoomChanged(null)
                listener.onOnlinePeerCountChanged(0)
                listener.onOnlineStatusMessage("Online hub error: ${t.message ?: "unknown"}")
            }
        }

    private fun buildRealtimeUrl(): String {
        val base = BuildConfig.REALTIME_BASE_URL.trim().removeSuffix("/")
        val path = BuildConfig.REALTIME_PATH.trim().let { if (it.startsWith("/")) it else "/$it" }

        return when {
            base.startsWith("wss://") || base.startsWith("ws://") -> "$base$path"
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}$path"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}$path"
            else -> "wss://$base$path"
        }
    }

    private fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, "").orEmpty().trim()
        if (existing.isNotBlank()) {
            return existing
        }
        val generated = "and-${System.currentTimeMillis()}-${(1000..9999).random()}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    companion object {
        private const val PREFS_NAME = "sharevia_v2_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SESSION_TOKEN = "session_token"
    }
}
