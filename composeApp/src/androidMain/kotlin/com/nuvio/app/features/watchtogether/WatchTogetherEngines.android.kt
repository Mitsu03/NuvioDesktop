package com.nuvio.app.features.watchtogether

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Android does not host or join rooms in v1 — `AppFeaturePolicy.watchTogetherEnabled` is
 * false here, so nothing reaches these. They exist because adding a member to an
 * `expect object` obliges every target to answer.
 *
 * They report [isSupported] false and do nothing rather than throwing: a stub that throws
 * turns a missing feature into a crash the first time some future caller forgets the check.
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
    private const val preferencesName = "watch_together"
    private const val enabledKey = "enabled"
    private const val portKey = "port"
    private const val displayNameKey = "display_name"
    private const val publicBaseUrlKey = "public_base_url"
    private const val controlPolicyKey = "control_policy"
    private const val autoAcceptJoinsKey = "auto_accept_joins"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadEnabled(): Boolean? = loadBoolean(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = saveBoolean(enabledKey, enabled)

    actual fun loadPort(): Int? = preferences
        ?.takeIf { it.contains(key(portKey)) }
        ?.getInt(key(portKey), 0)

    actual fun savePort(port: Int) {
        preferences?.edit()?.putInt(key(portKey), port)?.apply()
    }

    actual fun loadDisplayName(): String? = loadString(displayNameKey)
    actual fun saveDisplayName(name: String) = saveString(displayNameKey, name)

    actual fun loadPublicBaseUrl(): String? = loadString(publicBaseUrlKey)
    actual fun savePublicBaseUrl(url: String?) = saveString(publicBaseUrlKey, url)

    actual fun loadControlPolicy(): String? = loadString(controlPolicyKey)
    actual fun saveControlPolicy(policy: String) = saveString(controlPolicyKey, policy)

    actual fun loadAutoAcceptJoins(): Boolean? = loadBoolean(autoAcceptJoinsKey)
    actual fun saveAutoAcceptJoins(enabled: Boolean) = saveBoolean(autoAcceptJoinsKey, enabled)

    actual fun clearLocalState() {
        val editor = preferences?.edit() ?: return
        listOf(enabledKey, portKey, displayNameKey, publicBaseUrlKey, controlPolicyKey, autoAcceptJoinsKey)
            .forEach { editor.remove(key(it)) }
        editor.apply()
    }

    private fun key(name: String) = ProfileScopedKey.of(name)

    private fun loadBoolean(name: String): Boolean? = preferences
        ?.takeIf { it.contains(key(name)) }
        ?.getBoolean(key(name), false)

    private fun saveBoolean(name: String, value: Boolean) {
        preferences?.edit()?.putBoolean(key(name), value)?.apply()
    }

    private fun loadString(name: String): String? = preferences?.getString(key(name), null)

    private fun saveString(name: String, value: String?) {
        preferences?.edit()?.putString(key(name), value)?.apply()
    }
}
