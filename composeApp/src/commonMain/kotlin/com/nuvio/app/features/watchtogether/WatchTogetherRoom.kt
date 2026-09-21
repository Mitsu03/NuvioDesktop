package com.nuvio.app.features.watchtogether

import kotlinx.coroutines.flow.Flow

/**
 * Where the host's bytes actually live right now.
 *
 * Resolved **by reference on every request**, never snapshotted: for a torrent the runtime's
 * `activeSourceUrl` is a `torrent://` sentinel that is not fetchable at all (the real one is
 * `p2pResolvedSourceUrl`), and for debrid links the credential-refresh path swaps the URL
 * mid-session. A snapshot leaves the guest 403-ing while the host plays on happily.
 */
data class WatchTogetherOrigin(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** Extra response headers the app already trusts for this source. */
    val responseHeaders: Map<String, String> = emptyMap(),
)

enum class WatchTogetherRole { HOST, GUEST }

enum class WatchTogetherConnection { IDLE, CONNECTING, CONNECTED, DEGRADED, CLOSED }

/** What the player and the settings page render. Coarse by design — see below. */
data class WatchTogetherUiState(
    val role: WatchTogetherRole? = null,
    val connection: WatchTogetherConnection = WatchTogetherConnection.IDLE,
    val participants: List<WtParticipant> = emptyList(),
    val invite: WatchTogetherInvite? = null,
    /** Set when the host's current source cannot be re-served (HLS/DASH). */
    val unshareableReason: String? = null,
    val stalledPeer: Boolean = false,
    val degradedMessage: String? = null,
    val errorMessage: String? = null,
) {
    val isActive: Boolean get() = role != null && connection != WatchTogetherConnection.CLOSED
    val isGuest: Boolean get() = role == WatchTogetherRole.GUEST
    val isHost: Boolean get() = role == WatchTogetherRole.HOST
}

/**
 * How the host engine reaches back into the player runtime.
 *
 * Callbacks rather than state, precisely so the origin and the session are re-read per
 * request instead of captured once.
 */
class WatchTogetherHostBinding(
    val origin: () -> WatchTogetherOrigin?,
    /**
     * Builds the descriptor for the current source. The engine passes in the stream token it
     * has just minted, so token lifetime stays entirely inside the engine and the caller
     * never has to keep the two in step.
     */
    val session: (streamToken: String) -> WtSession?,
    val hostState: () -> WtHostState,
    val onGuestIntent: (WtClientCommand) -> Unit,
    val onParticipantsChanged: (List<WtParticipant>) -> Unit,
)

/** What the host advertises once a room is open. */
data class WatchTogetherRoomHandle(
    val port: Int,
    val joinToken: String,
    val lanAddresses: List<String>,
    /**
     * Whether the host could actually reach its own advertised address.
     *
     * False almost always means a firewall — and on a network Windows has classified as
     * Public it blocks **silently, with no prompt at all**, so this self-check is the only
     * thing standing between the user and an unexplained "can't connect".
     */
    val reachable: Boolean = true,
    val unreachableHint: String? = null,
)

/**
 * The host half of the transport.
 *
 * Only desktop implements it in v1; the other targets answer [isSupported] with false so the
 * shared code above compiles everywhere without conditionals.
 */
internal expect object WatchTogetherHostEngine {
    val isSupported: Boolean

    /** Binds a port and opens a room. Returns null when the port could not be bound. */
    fun open(
        binding: WatchTogetherHostBinding,
        preferredPort: Int,
        publicBaseUrl: String?,
    ): WatchTogetherRoomHandle?

    fun close()

    /** Pushed to guests; also the clock reference the whole room follows. */
    fun broadcastState(state: WtHostState)

    /**
     * The host switched source or episode: mints a fresh stream token, retires the old one
     * on a grace window, rebuilds the descriptor and tells the guests.
     */
    fun rotateSource()

    fun broadcastUnshareable(kind: String, message: String)

    fun participants(): List<WtParticipant>

    fun kick(participantId: String)

    /** The live proxy token, so callers can build a descriptor without owning its lifetime. */
    fun currentStreamToken(): String?
}

/** The guest half of the transport. */
internal expect object WatchTogetherGuestEngine {
    val isSupported: Boolean

    suspend fun join(invite: WatchTogetherInvite, request: WtJoinRequest): WtJoinResult

    /** Host events. Completes when the room closes or the connection is dropped. */
    fun events(): Flow<WtServerEvent>

    suspend fun send(command: WtClientCommand)

    /** One round-trip clock probe, for [WatchTogetherClockOffsetEstimator]. */
    suspend fun probeTime(): WtTimeResponse?

    fun leave()

    /** Absolute URL the guest's player should open for a proxied source. */
    fun streamUrl(invite: WatchTogetherInvite, streamToken: String): String
}

internal expect object WatchTogetherSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadPort(): Int?
    fun savePort(port: Int)
    fun loadDisplayName(): String?
    fun saveDisplayName(name: String)
    fun loadPublicBaseUrl(): String?
    fun savePublicBaseUrl(url: String?)
    fun loadControlPolicy(): String?
    fun saveControlPolicy(policy: String)
    fun loadAutoAcceptJoins(): Boolean?
    fun saveAutoAcceptJoins(enabled: Boolean)
    fun clearLocalState()
}
