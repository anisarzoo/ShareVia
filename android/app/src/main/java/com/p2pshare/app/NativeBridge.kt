package com.p2pshare.app

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import kotlin.random.Random

class NativeBridge(
    private val activity: Activity,
    private val webView: WebView,
) {
    @JavascriptInterface
    fun startBluetoothPairing() {
        // Production app: replace this with Nearby/BLE handshake.
        sendInfo("Bluetooth pairing requested from web layer.")
        sendPairingCode()
    }

    @JavascriptInterface
    fun startNfcPairing() {
        // Production app: connect to Android Beam replacement / custom NFC payload flow.
        sendInfo("NFC pairing requested from web layer.")
        sendPairingCode()
    }

    @JavascriptInterface
    fun startLocationPairing() {
        // Production app: use location-based discovery fallback for older Android devices.
        sendInfo("Location-assisted pairing requested from web layer.")
        sendPairingCode()
    }

    @JavascriptInterface
    fun postMessage(rawPayload: String) {
        val action = runCatching {
            JSONObject(rawPayload).optString("action")
        }.getOrNull()

        when (action) {
            "startBluetoothPairing" -> startBluetoothPairing()
            "startNfcPairing" -> startNfcPairing()
            "startLocationPairing" -> startLocationPairing()
            else -> sendInfo("Unknown native action: ${action ?: "none"}")
        }
    }

    private fun sendPairingCode() {
        val code = Random.nextInt(100000, 1000000).toString()
        val payload = JSONObject()
            .put("type", "pairing-code")
            .put("code", code)

        dispatch(payload)
    }

    private fun sendInfo(message: String) {
        val payload = JSONObject()
            .put("type", "info")
            .put("message", message)

        dispatch(payload)
    }

    private fun dispatch(payload: JSONObject) {
        activity.runOnUiThread {
            val script = "window.handleNativeBridgeMessage(${payload.toString()});"
            webView.evaluateJavascript(script, null)
        }
    }
}
