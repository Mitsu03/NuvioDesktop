package com.nuvio.app.features.watchtogether

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object WatchTogetherSettingsStorage {
    private const val enabledKey = "enabled"

    /**
     * Persisted so the firewall rule the user allowed once keeps working: a fresh port every
     * launch would mean a fresh prompt, or silent failure on a Public network profile.
     */
    private const val portKey = "port"
    private const val displayNameKey = "display_name"
    private const val publicBaseUrlKey = "public_base_url"
    private const val controlPolicyKey = "control_policy"
    private const val autoAcceptJoinsKey = "auto_accept_joins"

    private val store = DesktopStorage.store("watch_together")

    actual fun loadEnabled(): Boolean? = store.getBoolean(key(enabledKey))
    actual fun saveEnabled(enabled: Boolean) = store.putBoolean(key(enabledKey), enabled)

    actual fun loadPort(): Int? = store.getInt(key(portKey))
    actual fun savePort(port: Int) = store.putInt(key(portKey), port)

    actual fun loadDisplayName(): String? = store.getString(key(displayNameKey))
    actual fun saveDisplayName(name: String) = store.putString(key(displayNameKey), name)

    actual fun loadPublicBaseUrl(): String? = store.getString(key(publicBaseUrlKey))
    actual fun savePublicBaseUrl(url: String?) = store.putString(key(publicBaseUrlKey), url)

    actual fun loadControlPolicy(): String? = store.getString(key(controlPolicyKey))
    actual fun saveControlPolicy(policy: String) = store.putString(key(controlPolicyKey), policy)

    actual fun loadAutoAcceptJoins(): Boolean? = store.getBoolean(key(autoAcceptJoinsKey))
    actual fun saveAutoAcceptJoins(enabled: Boolean) = store.putBoolean(key(autoAcceptJoinsKey), enabled)

    actual fun clearLocalState() {
        listOf(enabledKey, portKey, displayNameKey, publicBaseUrlKey, controlPolicyKey, autoAcceptJoinsKey)
            .forEach { store.putString(key(it), null) }
    }

    private fun key(name: String) = ProfileScopedKey.of(name)
}
