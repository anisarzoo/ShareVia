package com.ShareVia.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TransferHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun load(): List<TransferHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val direction =
                        when (item.optString("direction")) {
                            TransferDirection.OUTGOING.name -> TransferDirection.OUTGOING
                            else -> TransferDirection.INCOMING
                        }
                    add(
                        TransferHistoryEntry(
                            id = item.optString("id", ""),
                            fileName = item.optString("fileName", "Unknown file"),
                            peerName = item.optString("peerName", "Unknown device"),
                            direction = direction,
                            sizeBytes = item.optLong("sizeBytes", 0L),
                            timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                            localPath = item.optString("localPath").ifBlank { null },
                        ),
                    )
                }
            }.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    fun append(entry: TransferHistoryEntry) {
        val latest = load().toMutableList()
        latest.add(0, entry)
        val bounded = latest.take(MAX_ITEMS)
        persist(bounded)
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
    }

    private fun persist(entries: List<TransferHistoryEntry>) {
        val jsonArray = JSONArray()
        entries.forEach { entry ->
            jsonArray.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("fileName", entry.fileName)
                    .put("peerName", entry.peerName)
                    .put("direction", entry.direction.name)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("timestamp", entry.timestamp)
                    .put("localPath", entry.localPath),
            )
        }

        prefs.edit().putString(KEY_HISTORY_JSON, jsonArray.toString()).apply()
    }

    companion object {
        private const val PREF_FILE = "sharevia_history"
        private const val KEY_HISTORY_JSON = "entries"
        private const val MAX_ITEMS = 120
    }
}
