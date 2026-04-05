package com.ShareVia.app

import android.content.Context
import android.net.Uri
import android.os.Build

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun load(): ShareProfile {
        val storedName = prefs.getString(KEY_NAME, null)?.trim().orEmpty()
        val name =
            storedName.ifBlank {
                fallbackDeviceName()
            }
        val avatarRaw = prefs.getString(KEY_AVATAR_URI, null)
        val avatarUri = avatarRaw?.let { Uri.parse(it) }
        return ShareProfile(displayName = name, avatarUri = avatarUri)
    }

    fun save(profile: ShareProfile) {
        prefs
            .edit()
            .putString(KEY_NAME, profile.displayName.trim())
            .putString(KEY_AVATAR_URI, profile.avatarUri?.toString())
            .apply()
    }

    private fun fallbackDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifBlank { "My Device" }.take(30)
    }

    companion object {
        private const val PREF_FILE = "sharevia_profile"
        private const val KEY_NAME = "display_name"
        private const val KEY_AVATAR_URI = "avatar_uri"
    }
}
