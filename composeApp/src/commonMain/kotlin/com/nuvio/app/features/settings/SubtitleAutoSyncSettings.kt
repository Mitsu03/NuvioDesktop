package com.nuvio.app.features.settings

/**
 * Settings surface for the automatic (algorithmic) subtitle sync -- AutoSync V2 -- which is
 * implemented on desktop only for now. Same shape as [DesktopRendererSettings]: platforms that
 * don't have the feature report [isSupported] as false and the settings page hides the section.
 *
 * Distinct from the manual "capture a line" Auto Sync tool in the player's subtitle panel, which
 * is available everywhere and has no settings of its own.
 */
internal expect object SubtitleAutoSyncSettings {
    val isSupported: Boolean
    val isEnabled: Boolean
    val isAggressiveMode: Boolean

    fun setEnabled(enabled: Boolean)
    fun setAggressiveMode(enabled: Boolean)
}
