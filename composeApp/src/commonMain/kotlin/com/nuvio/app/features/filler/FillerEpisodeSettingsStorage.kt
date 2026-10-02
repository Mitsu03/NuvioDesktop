package com.nuvio.app.features.filler

/** Device-local on purpose: the meta screen payload is synced verbatim and other clients would drop the key. */
internal expect object FillerEpisodeSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
}
