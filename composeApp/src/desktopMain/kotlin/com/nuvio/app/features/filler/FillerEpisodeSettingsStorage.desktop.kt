package com.nuvio.app.features.filler

import com.nuvio.app.core.storage.DesktopStorage

internal actual object FillerEpisodeSettingsStorage {
    private const val enabledKey = "enabled"
    private val store = DesktopStorage.store("nuvio_filler_episode_settings")

    actual fun loadEnabled(): Boolean? =
        if (store.contains(enabledKey)) store.getBoolean(enabledKey) else null

    actual fun saveEnabled(enabled: Boolean) {
        store.putBoolean(enabledKey, enabled)
    }
}
