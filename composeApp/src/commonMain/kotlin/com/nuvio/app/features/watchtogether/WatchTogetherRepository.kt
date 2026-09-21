package com.nuvio.app.features.watchtogether

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

const val DEFAULT_WATCH_TOGETHER_PORT: Int = 47500

private const val CLOCK_PROBE_INTERVAL_MS = 10_000L

/** First retry after a drop. Doubles up to [RECONNECT_MAX_DELAY_MS]. */
private const val RECONNECT_BASE_DELAY_MS = 1_000L
private const val RECONNECT_MAX_DELAY_MS = 8_000L

/**
 * About half a minute of trying, all told.
 *
 * Long enough to ride out a laptop lid, a Wi-Fi handover or a brief hiccup on the host;
 * short enough that a host who has genuinely gone away is reported rather than waited on
 * indefinitely.
 */
private const val RECONNECT_MAX_ATTEMPTS = 6

/** Settings for the feature. Same shape as every other settings object in the app. */
data class WatchTogetherSettings(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_WATCH_TOGETHER_PORT,
    val displayName: String = "",
    /**
     * Overrides the detected LAN address in the invite. This one field is the entire NAT
     * fallback: point it at a Tailscale address or a tunnel hostname and nothing else in
     * the feature changes.
     */
    val publicBaseUrl: String? = null,
    val controlPolicy: WtControlPolicy = WtControlPolicy.EVERYONE,
    val autoAcceptJoins: Boolean = true,
)

object WatchTogetherSettingsRepository {
    private val _uiState = MutableStateFlow(WatchTogetherSettings())
    val uiState: StateFlow<WatchTogetherSettings> = _uiState.asStateFlow()

    val isVisible: Boolean get() = AppFeaturePolicy.watchTogetherEnabled

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() = loadFromDisk()

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = WatchTogetherSettings()
        WatchTogetherSettingsStorage.clearLocalState()
    }

    fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
        WatchTogetherSettingsStorage.saveEnabled(enabled)
    }

    fun setPort(port: Int) {
        update { it.copy(port = port) }
        WatchTogetherSettingsStorage.savePort(port)
    }

    fun setDisplayName(name: String) {
        update { it.copy(displayName = name) }
        WatchTogetherSettingsStorage.saveDisplayName(name)
    }

    fun setPublicBaseUrl(url: String?) {
        val cleaned = url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() && isPlausibleRoomBaseUrl(it) }
        update { it.copy(publicBaseUrl = cleaned) }
        WatchTogetherSettingsStorage.savePublicBaseUrl(cleaned)
    }

    fun setControlPolicy(policy: WtControlPolicy) {
        update { it.copy(controlPolicy = policy) }
        WatchTogetherSettingsStorage.saveControlPolicy(policy.name)
    }

    fun setAutoAcceptJoins(enabled: Boolean) {
        update { it.copy(autoAcceptJoins = enabled) }
        WatchTogetherSettingsStorage.saveAutoAcceptJoins(enabled)
    }

    private fun update(transform: (WatchTogetherSettings) -> WatchTogetherSettings) {
        _uiState.value = transform(_uiState.value)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _uiState.value = WatchTogetherSettings(
            enabled = WatchTogetherSettingsStorage.loadEnabled() ?: false,
            port = WatchTogetherSettingsStorage.loadPort() ?: DEFAULT_WATCH_TOGETHER_PORT,
            displayName = WatchTogetherSettingsStorage.loadDisplayName().orEmpty(),
            publicBaseUrl = WatchTogetherSettingsStorage.loadPublicBaseUrl(),
            controlPolicy = WatchTogetherSettingsStorage.loadControlPolicy()
                ?.let { stored -> WtControlPolicy.entries.firstOrNull { it.name == stored } }
                ?: WtControlPolicy.EVERYONE,
            autoAcceptJoins = WatchTogetherSettingsStorage.loadAutoAcceptJoins() ?: true,
        )
    }
}

/** Everything the guest needs to open the player on the host's content. */
data class WatchTogetherGuestLaunch(
    val session: WtSession,
    val invite: WatchTogetherInvite,
    val hostState: WtHostState,
)

/**
 * The room coordinator: owns the connection, holds the sync state, and is the one place the
 * player talks to.
 *
 * Shaped after `core/sync/SyncManager` — a singleton object with its own scope — but
 * deliberately **not** driven by auth or app-foreground signals. A room has to survive the
 * window being minimised; that is the whole point of it running in the background.
 */
object WatchTogetherRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(WatchTogetherUiState())
    val uiState: StateFlow<WatchTogetherUiState> = _uiState.asStateFlow()

    /** Non-null when a guest has joined and the player should be opened on this content. */
    private val _pendingGuestLaunch = MutableStateFlow<WatchTogetherGuestLaunch?>(null)
    val pendingGuestLaunch: StateFlow<WatchTogetherGuestLaunch?> = _pendingGuestLaunch.asStateFlow()

    /** The host's latest broadcast, as the guest's sync loop consumes it. */
    private val _remoteHostState = MutableStateFlow<WtHostState?>(null)
    val remoteHostState: StateFlow<WtHostState?> = _remoteHostState.asStateFlow()

    // Sync state lives here so it survives a recomposition of the player screen.
    val clockOffset = WatchTogetherClockOffsetEstimator()
    val drift = WatchTogetherDriftController()
    val stallDetector = WatchTogetherStallDetector()
    val thrashGuard = WatchTogetherStallThrashGuard()

    private var eventJob: Job? = null
    private var clockJob: Job? = null
    private var currentInvite: WatchTogetherInvite? = null

    val isSupported: Boolean
        get() = AppFeaturePolicy.watchTogetherEnabled &&
            (WatchTogetherHostEngine.isSupported || WatchTogetherGuestEngine.isSupported)

    // -- Host ---------------------------------------------------------------------------

    /**
     * Opens a room around the content the host is already watching.
     *
     * @return the invite to share, or null when no port could be bound.
     */
    fun openRoom(binding: WatchTogetherHostBinding): WatchTogetherInvite? {
        if (!AppFeaturePolicy.watchTogetherEnabled || !WatchTogetherHostEngine.isSupported) return null
        val settings = WatchTogetherSettingsRepository.uiState.value

        val handle = WatchTogetherHostEngine.open(
            binding = binding,
            preferredPort = settings.port,
            publicBaseUrl = settings.publicBaseUrl,
        ) ?: run {
            _uiState.value = _uiState.value.copy(
                connection = WatchTogetherConnection.CLOSED,
                errorMessage = "Could not open a port for the room.",
            )
            return null
        }

        // Remember the port that actually worked, so the firewall rule the user allowed once
        // keeps applying next time.
        if (handle.port != settings.port) WatchTogetherSettingsRepository.setPort(handle.port)

        val baseUrl = handle.lanAddresses.firstOrNull() ?: "http://127.0.0.1:${handle.port}"
        val invite = WatchTogetherInvite(baseUrl = baseUrl, joinToken = handle.joinToken)
        currentInvite = invite

        _uiState.value = WatchTogetherUiState(
            role = WatchTogetherRole.HOST,
            connection = WatchTogetherConnection.CONNECTED,
            invite = invite,
            // Surfaced immediately rather than waiting for a guest to fail: the host is the
            // only one who can fix a firewall, and only before sending the invite.
            errorMessage = handle.unreachableHint.takeIf { !handle.reachable },
        )
        return invite
    }

    fun publishHostState(state: WtHostState) {
        if (_uiState.value.role != WatchTogetherRole.HOST) return
        WatchTogetherHostEngine.broadcastState(state)
        val participants = WatchTogetherHostEngine.participants()
        _uiState.value = _uiState.value.copy(
            participants = participants,
            stalledPeer = participants.any { it.isStalled },
        )
    }

    fun rotateSource() {
        if (_uiState.value.role != WatchTogetherRole.HOST) return
        WatchTogetherHostEngine.rotateSource()
    }

    fun reportUnshareable(kind: String, message: String) {
        WatchTogetherHostEngine.broadcastUnshareable(kind, message)
        _uiState.value = _uiState.value.copy(unshareableReason = message)
    }

    fun kick(participantId: String) = WatchTogetherHostEngine.kick(participantId)

    /** The current proxy token, for whoever is building the session descriptor. */
    fun currentStreamToken(): String? = WatchTogetherHostEngine.currentStreamToken()

    // -- Guest --------------------------------------------------------------------------

    fun joinRoom(
        invite: WatchTogetherInvite,
        displayName: String,
        appVersion: String,
        clientId: String,
        p2pEnabled: Boolean,
    ) {
        if (!AppFeaturePolicy.watchTogetherEnabled || !WatchTogetherGuestEngine.isSupported) return

        leaveRoom()
        currentInvite = invite
        _uiState.value = WatchTogetherUiState(
            role = WatchTogetherRole.GUEST,
            connection = WatchTogetherConnection.CONNECTING,
            invite = invite,
        )

        scope.launch {
            val result = WatchTogetherGuestEngine.join(
                invite = invite,
                request = WtJoinRequest(
                    joinToken = invite.joinToken,
                    clientId = clientId,
                    displayName = displayName.ifBlank { "Guest" },
                    appVersion = appVersion,
                    p2pEnabled = p2pEnabled,
                ),
            )

            when (result) {
                is WtJoinResult.Rejected -> {
                    _uiState.value = _uiState.value.copy(
                        connection = WatchTogetherConnection.CLOSED,
                        errorMessage = result.message ?: describe(result.reason),
                    )
                }

                is WtJoinResult.Accepted -> {
                    resetSyncState()
                    val arrivedAt = nowMs()
                    clockOffset.record(
                        sentAtMs = arrivedAt,
                        hostTimeMs = result.serverTimeMs,
                        receivedAtMs = arrivedAt,
                    )
                    _remoteHostState.value = result.hostState
                    _pendingGuestLaunch.value = WatchTogetherGuestLaunch(
                        session = result.session,
                        invite = invite,
                        hostState = result.hostState,
                    )
                    _uiState.value = _uiState.value.copy(connection = WatchTogetherConnection.CONNECTED)
                    startEventLoop()
                    startClockLoop()
                }
            }
        }
    }

    fun consumeGuestLaunch() {
        _pendingGuestLaunch.value = null
    }

    fun sendIntent(command: WtClientCommand) {
        if (_uiState.value.role != WatchTogetherRole.GUEST) return
        scope.launch { WatchTogetherGuestEngine.send(command) }
    }

    fun leaveRoom() {
        eventJob?.cancel()
        clockJob?.cancel()
        eventJob = null
        clockJob = null

        when (_uiState.value.role) {
            WatchTogetherRole.HOST -> WatchTogetherHostEngine.close()
            WatchTogetherRole.GUEST -> {
                // Sent before leave() clears the address, or the goodbye has nowhere to
                // go and the host waits out the staleness timeout instead.
                scope.launch {
                    WatchTogetherGuestEngine.send(WtClientCommand.Leave)
                    WatchTogetherGuestEngine.leave()
                }
            }

            null -> Unit
        }

        currentInvite = null
        _remoteHostState.value = null
        _pendingGuestLaunch.value = null
        resetSyncState()
        _uiState.value = WatchTogetherUiState()
    }

    fun guestStreamUrl(streamToken: String): String? =
        currentInvite?.let { WatchTogetherGuestEngine.streamUrl(it, streamToken) }

    // -- Internals ----------------------------------------------------------------------

    /**
     * Holds the event stream open, and puts it back when it drops.
     *
     * A dropped stream and a closed room look identical from here - the flow simply ends -
     * so they are told apart by state: [onServerEvent] marks the connection CLOSED when the
     * host actually says goodbye, and anything else is treated as a drop worth retrying.
     * Without this, a momentary Wi-Fi blip ends the evening.
     */
    private fun startEventLoop() {
        eventJob?.cancel()
        eventJob = scope.launch {
            var attempt = 0
            while (isActive && _uiState.value.role == WatchTogetherRole.GUEST) {
                WatchTogetherGuestEngine.events().collect { event ->
                    // Any frame at all means the link is healthy again.
                    attempt = 0
                    onServerEvent(event)
                }

                if (!isActive) return@launch
                // The host closed the room, or this guest left: nothing to reconnect to.
                if (_uiState.value.role != WatchTogetherRole.GUEST) return@launch
                if (_uiState.value.connection == WatchTogetherConnection.CLOSED) return@launch

                attempt += 1
                if (attempt > RECONNECT_MAX_ATTEMPTS) {
                    _uiState.value = _uiState.value.copy(
                        connection = WatchTogetherConnection.CLOSED,
                        errorMessage = "Lost the connection to the host.",
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    connection = WatchTogetherConnection.CONNECTING,
                    errorMessage = null,
                )
                // Everything the drift controller learned is about a stream that is no
                // longer flowing; keeping it would correct against a stale baseline.
                drift.reset()
                stallDetector.reset()

                delay(reconnectDelayMs(attempt))
            }
        }
    }

    /** Exponential, capped: a host that is down should not be hammered. */
    private fun reconnectDelayMs(attempt: Int): Long {
        var delayMs = RECONNECT_BASE_DELAY_MS
        repeat(attempt - 1) {
            delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        }
        return delayMs
    }

    private fun onServerEvent(event: WtServerEvent) {
        when (event) {
            is WtServerEvent.State -> {
                _remoteHostState.value = event.state
                // Frames are flowing: whatever the state said mid-reconnect, we are
                // connected now.
                if (_uiState.value.connection == WatchTogetherConnection.CONNECTING) {
                    _uiState.value = _uiState.value.copy(
                        connection = WatchTogetherConnection.CONNECTED,
                        errorMessage = null,
                    )
                }
            }

            is WtServerEvent.SourceChanged -> {
                // The host switched source or episode: re-open the player on the new one and
                // throw away everything the drift controller learned about the old stream.
                resetSyncState()
                _remoteHostState.value = event.hostState
                currentInvite?.let { invite ->
                    _pendingGuestLaunch.value =
                        WatchTogetherGuestLaunch(event.session, invite, event.hostState)
                }
            }

            is WtServerEvent.SourceUnshareable ->
                _uiState.value = _uiState.value.copy(unshareableReason = event.message)

            is WtServerEvent.Participants ->
                _uiState.value = _uiState.value.copy(
                    participants = event.participants,
                    stalledPeer = event.participants.any { it.isStalled },
                )

            is WtServerEvent.RoomClosed ->
                _uiState.value = _uiState.value.copy(
                    connection = WatchTogetherConnection.CLOSED,
                    errorMessage = "The room closed (${event.reason}).",
                )

            is WtServerEvent.Ping -> Unit
        }
    }

    private fun startClockLoop() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                val sentAt = nowMs()
                val response = WatchTogetherGuestEngine.probeTime()
                if (response != null) {
                    clockOffset.record(
                        sentAtMs = sentAt,
                        hostTimeMs = response.hostTimeMs,
                        receivedAtMs = nowMs(),
                    )
                }
                delay(CLOCK_PROBE_INTERVAL_MS)
            }
        }
    }

    private fun resetSyncState() {
        clockOffset.reset()
        drift.reset()
        stallDetector.reset()
        thrashGuard.reset()
    }

    private fun describe(reason: WtRejectReason): String = when (reason) {
        WtRejectReason.BAD_TOKEN -> "That invite is not valid."
        WtRejectReason.PROTOCOL_MISMATCH -> "The two apps are running different versions."
        WtRejectReason.ROOM_FULL -> "The room is full."
        WtRejectReason.ROOM_CLOSED -> "The room is closed."
        WtRejectReason.SOURCE_NOT_SHAREABLE -> "The host is playing something that can't be shared yet."
    }
}

private val monotonicOrigin = TimeSource.Monotonic.markNow()

/**
 * Monotonic milliseconds. Never wall clock — two machines' wall clocks routinely differ by
 * seconds, which would make the guest hard-seek on every tick.
 *
 * One origin for the whole process, host and guest alike: the clock-offset estimate compares
 * the host's `/time` reply against the same reading that stamps `WtHostState.sampleTimeMs`,
 * so two monotonic clocks with different origins would bake a constant error into every
 * projection.
 */
internal fun nowMs(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds
