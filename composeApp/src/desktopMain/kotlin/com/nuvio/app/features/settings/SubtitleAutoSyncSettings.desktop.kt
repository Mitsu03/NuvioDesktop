package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.autosync.AutoSyncPreferences

internal actual object SubtitleAutoSyncSettings {
    actual val isSupported: Boolean = true

    actual val isEnabled: Boolean
        get() = AutoSyncPreferences.enabled.value

    actual val isAggressiveMode: Boolean
        get() = AutoSyncPreferences.aggressiveMode.value

    actual fun setEnabled(enabled: Boolean) = AutoSyncPreferences.setEnabled(enabled)

    actual fun setAggressiveMode(enabled: Boolean) = AutoSyncPreferences.setAggressiveMode(enabled)
}
