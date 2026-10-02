package com.nuvio.app.features.filler

import platform.Foundation.NSUserDefaults

internal actual object FillerEpisodeSettingsStorage {
    private const val enabledKey = "filler_episode_tag_enabled"

    actual fun loadEnabled(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(enabledKey) != null) {
            defaults.boolForKey(enabledKey)
        } else {
            null
        }
    }

    actual fun saveEnabled(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = enabledKey)
    }
}
