package com.nuvio.app.features.settings

// AutoSync V2 is desktop-only for now, so there is nothing to configure here.
internal actual object SubtitleAutoSyncSettings {
    actual val isSupported: Boolean = false
    actual val isEnabled: Boolean = false
    actual val isAggressiveMode: Boolean = false

    actual fun setEnabled(enabled: Boolean) = Unit
    actual fun setAggressiveMode(enabled: Boolean) = Unit
}
