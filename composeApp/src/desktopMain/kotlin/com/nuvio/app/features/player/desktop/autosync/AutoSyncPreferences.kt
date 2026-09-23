package com.nuvio.app.features.player.desktop.autosync

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AutoSync-owned preferences, ported from NuvioTV's AutoSync V2.
 *
 * Kept outside the app's main settings store on purpose, same reasoning as the Android original:
 * this is a fork feature that doesn't need new fields/migrations wired into shared settings
 * infrastructure. Backed by [DesktopStorage] instead of Android SharedPreferences -- no Context
 * dependency, so unlike the Android version there's no lazy `ensureLoaded(context)` step.
 */
internal object AutoSyncPreferences {
    private const val KEY_ENABLED = "autosync_v2_enabled"
    private const val KEY_AGGRESSIVE_MODE = "autosync_v2_aggressive_mode"
    private val store = DesktopStorage.store("nuvio_autosync_v2")

    private val lock = Any()
    private var lastStartupSessionKey: Int? = null
    private var lastStartupPlaybackKey: String? = null

    // Opt-in: the toggle lives in Settings -> Playback -> Subtitle Auto Sync
    // (see SubtitleAutoSyncSettings).
    private val _enabled = MutableStateFlow(store.getBoolean(KEY_ENABLED) ?: false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _aggressiveMode = MutableStateFlow(store.getBoolean(KEY_AGGRESSIVE_MODE) ?: true)
    val aggressiveMode: StateFlow<Boolean> = _aggressiveMode.asStateFlow()

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        store.putBoolean(KEY_ENABLED, enabled)
    }

    fun setAggressiveMode(enabled: Boolean) {
        if (_aggressiveMode.value == enabled) return
        _aggressiveMode.value = enabled
        store.putBoolean(KEY_AGGRESSIVE_MODE, enabled)
    }

    /**
     * Startup auto-run (as opposed to a manual subtitle selection) should fire at most once per
     * (player session, playback) pair. Manual selection always re-triggers and does not go
     * through this gate. In-memory only -- this is runtime dedupe state, not a stored preference.
     */
    fun claimStartupRun(sessionKey: Int, playbackKey: String): Boolean {
        synchronized(lock) {
            if (!_enabled.value) return false
            if (lastStartupSessionKey == sessionKey && lastStartupPlaybackKey == playbackKey) {
                return false
            }
            lastStartupSessionKey = sessionKey
            lastStartupPlaybackKey = playbackKey
            return true
        }
    }
}
