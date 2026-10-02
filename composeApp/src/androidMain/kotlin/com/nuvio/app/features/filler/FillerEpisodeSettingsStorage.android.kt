package com.nuvio.app.features.filler

import android.content.Context
import android.content.SharedPreferences

internal actual object FillerEpisodeSettingsStorage {
    private const val preferencesName = "nuvio_filler_episode_settings"
    private const val enabledKey = "enabled"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEnabled(): Boolean? =
        preferences?.let { prefs ->
            if (prefs.contains(enabledKey)) prefs.getBoolean(enabledKey, false) else null
        }

    actual fun saveEnabled(enabled: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(enabledKey, enabled)
            ?.apply()
    }
}
