package com.nuvio.app.features.watchtogether

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.NSUserDefaults

/**
 * iOS neither hosts nor joins rooms in v1 — `AppFeaturePolicy.watchTogetherEnabled` is false
 * here. These exist so the shared `expect` declarations have an answer on every target, and
 * they no-op rather than throwing so a future caller that forgets [isSupported] degrades
 * instead of crashing.
 */
internal actual object WatchTogetherHostEngine {
    actual val isSupported: Boolean = false

    actual fun open(
        binding: WatchTogetherHostBinding,
        preferredPort: Int,
        publicBaseUrl: String?,
    ): WatchTogetherRoomHandle? = null

    actual fun close() = Unit
    actual fun broadcastState(state: WtHostState) = Unit
    actual fun rotateSource() = Unit
    actual fun broadcastUnshareable(kind: String, message: String) = Unit
    actual fun participants(): List<WtParticipant> = emptyList()
    actual fun kick(participantId: String) = Unit
    actual fun currentStreamToken(): String? = null
}

internal actual object WatchTogetherGuestEngine {
    actual val isSupported: Boolean = false

    actual suspend fun join(invite: WatchTogetherInvite, request: WtJoinRequest): WtJoinResult =
        WtJoinResult.Rejected(WtRejectReason.ROOM_CLOSED, "Watch Together is desktop-only for now.")

    actual fun events(): Flow<WtServerEvent> = emptyFlow()
    actual suspend fun send(command: WtClientCommand) = Unit
    actual suspend fun probeTime(): WtTimeResponse? = null
    actual fun leave() = Unit
    actual fun streamUrl(invite: WatchTogetherInvite, streamToken: String): String =
        "${invite.baseUrl}/wt/v1/s/$streamToken/stream"
}

internal actual object WatchTogetherSettingsStorage {
    private const val enabledKey = "watch_together_enabled"
    private const val portKey = "watch_together_port"
    private const val displayNameKey = "watch_together_display_name"
    private const val publicBaseUrlKey = "watch_together_public_base_url"
    private const val controlPolicyKey = "watch_together_control_policy"
    private const val autoAcceptJoinsKey = "watch_together_auto_accept_joins"

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = saveBoolean(enabledKey, enabled)

    actual fun loadPort(): Int? = loadString(portKey)?.toIntOrNull()
    actual fun savePort(port: Int) = saveString(portKey, port.toString())

    actual fun loadDisplayName(): String? = loadString(displayNameKey)
    actual fun saveDisplayName(name: String) = saveString(displayNameKey, name)

    actual fun loadPublicBaseUrl(): String? = loadString(publicBaseUrlKey)
    actual fun savePublicBaseUrl(url: String?) = saveString(publicBaseUrlKey, url)

    actual fun loadControlPolicy(): String? = loadString(controlPolicyKey)
    actual fun saveControlPolicy(policy: String) = saveString(controlPolicyKey, policy)

    actual fun loadAutoAcceptJoins(): Boolean? = loadBoolean(autoAcceptJoinsKey)
    actual fun saveAutoAcceptJoins(enabled: Boolean) = saveBoolean(autoAcceptJoinsKey, enabled)

    actual fun clearLocalState() {
        listOf(enabledKey, portKey, displayNameKey, publicBaseUrlKey, controlPolicyKey, autoAcceptJoinsKey)
            .forEach { defaults.removeObjectForKey(key(it)) }
    }

    private fun key(name: String) = ProfileScopedKey.of(name)

    // NSUserDefaults has no "absent" for a Bool, so absence is stored as a missing string.
    private fun loadBoolean(name: String): Boolean? = loadString(name)?.toBooleanStrictOrNull()

    private fun saveBoolean(name: String, value: Boolean) = saveString(name, value.toString())

    private fun loadString(name: String): String? = defaults.stringForKey(key(name))

    private fun saveString(name: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key(name))
        } else {
            defaults.setObject(value, key(name))
        }
    }
}
