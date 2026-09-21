package com.nuvio.app.features.watchtogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Watch Together wire contract, shared by host and guest.
 *
 * Deliberately transport-independent: today it travels as SSE frames (host to guest) and
 * plain POST bodies (guest to host), but nothing here knows that, so moving to WebSockets
 * later would touch the two transport actuals and no protocol type.
 *
 * **Every time here is monotonic milliseconds**, not wall clock. Two machines' wall clocks
 * routinely differ by seconds; comparing them directly would make the guest hard-seek on
 * every tick, forever. The guest converts host time into its own via
 * [WatchTogetherClockOffsetEstimator].
 */
const val WATCH_TOGETHER_PROTOCOL_VERSION: Int = 1

/** Lenient on read so a newer host adding a field cannot break an older guest outright. */
val watchTogetherJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "op"
}

@Serializable
data class WtSubtitle(
    val url: String,
    val language: String,
    val name: String? = null,
)

/** How the guest is to obtain the bytes. */
@Serializable
sealed interface WtSource {
    /** The host re-serves its own stream. Costs the host upload at the stream's bitrate. */
    @Serializable
    @SerialName("proxy")
    data class Proxy(
        val streamToken: String,
        /**
         * False when the origin answered a `Range: bytes=0-1` probe with 200 instead of 206.
         * The guest then cannot seek at all, and the host is warned before anyone joins.
         */
        val supportsRange: Boolean = true,
        val contentLengthBytes: Long? = null,
    ) : WtSource

    /**
     * The guest resolves the same torrent locally — no host upload whatsoever. Always
     * preferred when available.
     */
    @Serializable
    @SerialName("torrent")
    data class Torrent(
        val infoHash: String,
        val fileIdx: Int? = null,
        val filename: String? = null,
        val trackers: List<String> = emptyList(),
        /** Used when the guest has P2P turned off: the host proxies its own TorrServer URL. */
        val proxyFallbackToken: String? = null,
    ) : WtSource
}

/**
 * Everything the guest needs to rebuild a `PlayerLaunch` and open the same content.
 *
 * Built from the runtime's `active*` fields rather than the original launch arguments,
 * because `switchToSource` / `switchToEpisodeStream` replace the stream mid-session.
 */
@Serializable
data class WtSession(
    val sessionId: String,
    val title: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val contentType: String? = null,
    val videoId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val streamType: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val durationMs: Long = 0L,
    val externalSubtitles: List<WtSubtitle> = emptyList(),
    val source: WtSource,
)

/** Why playback is currently paused. Only the automatic causes may auto-resume. */
@Serializable
enum class WtPauseCause {
    NONE,

    /** Somebody pressed pause. Sticky — a stall clearing must never override this. */
    USER,

    /** The host itself is buffering. */
    BUFFERING,

    /** A guest reported a stall and the room paused to wait for them. */
    STALLED_PEER,
}

/** The host's playback state — the single source of truth for the whole room. */
@Serializable
data class WtHostState(
    /** Host monotonic ms **at the moment position was sampled**, not at transmit. */
    val sampleTimeMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val playbackSpeed: Float = 1.0f,
    val pauseCause: WtPauseCause = WtPauseCause.NONE,
    val sessionId: String,
    /** Monotonically increasing, so a guest can discard an out-of-order frame. */
    val sequence: Long = 0L,
)

@Serializable
data class WtParticipant(
    val participantId: String,
    val displayName: String,
    val isHost: Boolean = false,
    val isStalled: Boolean = false,
    val bufferedAheadMs: Long = 0L,
    val connected: Boolean = true,
)

// ---------------------------------------------------------------------------------------
// Join handshake
// ---------------------------------------------------------------------------------------

@Serializable
data class WtJoinRequest(
    val protocolVersion: Int = WATCH_TOGETHER_PROTOCOL_VERSION,
    val joinToken: String,
    val clientId: String,
    val displayName: String,
    val appVersion: String,
    /** Whether the guest can resolve a torrent itself, deciding torrent vs proxy fallback. */
    val p2pEnabled: Boolean = false,
)

@Serializable
sealed interface WtJoinResult {
    @Serializable
    @SerialName("accepted")
    data class Accepted(
        val protocolVersion: Int = WATCH_TOGETHER_PROTOCOL_VERSION,
        val participantId: String,
        /** Bearer for the event stream and the command endpoint. */
        val participantToken: String,
        val session: WtSession,
        val hostState: WtHostState,
        val serverTimeMs: Long,
        val controlPolicy: WtControlPolicy,
    ) : WtJoinResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val reason: WtRejectReason,
        val message: String? = null,
    ) : WtJoinResult
}

@Serializable
enum class WtRejectReason {
    BAD_TOKEN,
    PROTOCOL_MISMATCH,
    ROOM_FULL,
    ROOM_CLOSED,

    /** The host's current stream is HLS/DASH, which v1 cannot re-serve. */
    SOURCE_NOT_SHAREABLE,
}

@Serializable
enum class WtControlPolicy {
    /** Anyone may issue an intent; the host still commits it. The default. */
    EVERYONE,

    /** Guest intents are rejected outright. */
    HOST_ONLY,
}

// ---------------------------------------------------------------------------------------
// Host -> guest
// ---------------------------------------------------------------------------------------

@Serializable
sealed interface WtServerEvent {
    @Serializable
    @SerialName("state")
    data class State(val state: WtHostState) : WtServerEvent

    /** The host switched source or episode. Carries a fresh session and fresh tokens. */
    @Serializable
    @SerialName("sourceChanged")
    data class SourceChanged(val session: WtSession, val hostState: WtHostState) : WtServerEvent

    /**
     * The host moved to something v1 cannot share (an adaptive manifest). The room stays
     * open in a degraded state rather than being torn down.
     */
    @Serializable
    @SerialName("sourceUnshareable")
    data class SourceUnshareable(val kind: String, val message: String) : WtServerEvent

    @Serializable
    @SerialName("participants")
    data class Participants(val participants: List<WtParticipant>) : WtServerEvent

    @Serializable
    @SerialName("closed")
    data class RoomClosed(val reason: String) : WtServerEvent

    /** Keepalive; also the passive half of clock synchronisation. */
    @Serializable
    @SerialName("ping")
    data class Ping(val serverTimeMs: Long) : WtServerEvent
}

// ---------------------------------------------------------------------------------------
// Guest -> host
// ---------------------------------------------------------------------------------------

@Serializable
sealed interface WtClientCommand {
    @Serializable
    @SerialName("play")
    data class IntentPlay(val atPositionMs: Long) : WtClientCommand

    @Serializable
    @SerialName("pause")
    data class IntentPause(val atPositionMs: Long) : WtClientCommand

    @Serializable
    @SerialName("seek")
    data class IntentSeek(val toPositionMs: Long) : WtClientCommand

    /** Sent once a second, so the host can show buffer health and detect a vanished guest. */
    @Serializable
    @SerialName("guestState")
    data class GuestState(
        val sampleTimeMs: Long,
        val positionMs: Long,
        val isLoading: Boolean,
        val bufferedAheadMs: Long,
        val appliedSpeed: Float = 1.0f,
    ) : WtClientCommand

    @Serializable
    @SerialName("stall")
    data object Stall : WtClientCommand

    @Serializable
    @SerialName("stallCleared")
    data class StallCleared(val bufferedAheadMs: Long) : WtClientCommand

    @Serializable
    @SerialName("leave")
    data object Leave : WtClientCommand
}

/** Round-trip clock probe. The host stamps [WtTimeResponse.hostTimeMs] on receipt. */
@Serializable
data class WtTimeRequest(val sentAtMs: Long)

@Serializable
data class WtTimeResponse(val sentAtMs: Long, val hostTimeMs: Long)

@Serializable
data class WtHealth(
    val protocolVersion: Int = WATCH_TOGETHER_PROTOCOL_VERSION,
    val roomOpen: Boolean,
)
