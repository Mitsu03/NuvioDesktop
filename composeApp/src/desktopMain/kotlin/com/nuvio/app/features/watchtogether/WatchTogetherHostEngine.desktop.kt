package com.nuvio.app.features.watchtogether

import co.touchlab.kermit.Logger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val log = Logger.withTag("WatchTogetherHost")

private const val MAX_PARTICIPANTS = 4

/** Three missed 1 Hz heartbeats: enough to ride out a hiccup, short enough to notice a death. */
private const val PARTICIPANT_STALE_MS = 3_500L

internal actual object WatchTogetherHostEngine {
    actual val isSupported: Boolean = true

    private var server: WatchTogetherHttpServer? = null
    private var room: HostRoom? = null

    actual fun open(
        binding: WatchTogetherHostBinding,
        preferredPort: Int,
        publicBaseUrl: String?,
    ): WatchTogetherRoomHandle? {
        close()

        val newRoom = HostRoom(binding)
        val newServer = WatchTogetherHttpServer(
            originProvider = binding.origin,
            controlPlane = newRoom,
        )

        val port = try {
            newServer.start(preferredPort)
        } catch (e: Exception) {
            log.e(e) { "Could not bind a port for the room" }
            return null
        }

        newRoom.attach(newServer)
        server = newServer
        room = newRoom

        val addresses = publicBaseUrl?.let(::listOf) ?: localAddresses().map { "http://$it:$port" }
        val reachableAddress = addresses.firstOrNull(::isRoomReachable)

        return WatchTogetherRoomHandle(
            port = port,
            joinToken = newRoom.joinToken,
            // Lead with an address that answered, so the invite carries something that works.
            lanAddresses = reachableAddress?.let { listOf(it) + (addresses - it) } ?: addresses,
            reachable = reachableAddress != null,
            unreachableHint = if (reachableAddress != null) null else firewallHint(port),
        )
    }

    actual fun close() {
        room?.broadcast(WtServerEvent.RoomClosed("host left"))
        // A one-second grace so the close actually reaches the guests, rather than them
        // discovering it as a dropped connection.
        server?.stop(1)
        server = null
        room = null
    }

    actual fun broadcastState(state: WtHostState) {
        room?.let {
            it.dropStaleParticipants()
            it.broadcast(WtServerEvent.State(state))
        }
    }

    actual fun rotateSource() {
        room?.rotateSource()
    }

    actual fun broadcastUnshareable(kind: String, message: String) {
        room?.broadcast(WtServerEvent.SourceUnshareable(kind, message))
    }

    actual fun participants(): List<WtParticipant> = room?.snapshot().orEmpty()

    actual fun kick(participantId: String) {
        room?.kick(participantId)
    }

    actual fun currentStreamToken(): String? = room?.streamToken
}

/**
 * The room's own state: who is here, which tokens are live, and what everyone is told.
 *
 * Implements the transport's [WatchTogetherControlPlane] so the HTTP layer stays ignorant of
 * rooms and players.
 */
private class HostRoom(
    private val binding: WatchTogetherHostBinding,
) : WatchTogetherControlPlane {

    val joinToken: String = randomToken()

    @Volatile
    var streamToken: String = ""
        private set

    private var server: WatchTogetherHttpServer? = null
    private val participants = ConcurrentHashMap<String, Participant>()
    private val sinks = ConcurrentHashMap<String, WatchTogetherEventSink>()

    fun attach(httpServer: WatchTogetherHttpServer) {
        server = httpServer
        streamToken = httpServer.issueStreamToken()
    }

    // -- WatchTogetherControlPlane ------------------------------------------------------

    override fun join(request: WtJoinRequest): WtJoinResult {
        if (request.protocolVersion != WATCH_TOGETHER_PROTOCOL_VERSION) {
            return WtJoinResult.Rejected(
                WtRejectReason.PROTOCOL_MISMATCH,
                "This room speaks protocol $WATCH_TOGETHER_PROTOCOL_VERSION.",
            )
        }
        if (!constantTimeEquals(joinToken, request.joinToken)) {
            return WtJoinResult.Rejected(WtRejectReason.BAD_TOKEN)
        }
        if (participants.size >= MAX_PARTICIPANTS) {
            return WtJoinResult.Rejected(WtRejectReason.ROOM_FULL)
        }

        val session = binding.session(streamToken)
            ?: return WtJoinResult.Rejected(
                WtRejectReason.SOURCE_NOT_SHAREABLE,
                "The host is playing a source that can't be shared yet.",
            )

        val participant = Participant(
            id = randomToken(9),
            token = randomToken(),
            displayName = request.displayName.ifBlank { "Guest" },
            p2pEnabled = request.p2pEnabled,
        )
        participants[participant.id] = participant
        publishParticipants()

        return WtJoinResult.Accepted(
            participantId = participant.id,
            participantToken = participant.token,
            session = session,
            hostState = binding.hostState(),
            serverTimeMs = hostTimeMs(),
            controlPolicy = WtControlPolicy.EVERYONE,
        )
    }

    override fun participantFor(bearerToken: String): String? {
        if (bearerToken.isBlank()) return null
        var found: String? = null
        // No early exit, so the comparison time says nothing about which token matched.
        for (participant in participants.values) {
            if (constantTimeEquals(participant.token, bearerToken)) found = participant.id
        }
        return found
    }

    override fun onCommand(participantId: String, command: WtClientCommand) {
        val participant = participants[participantId] ?: return
        participant.lastSeenMs = hostTimeMs()

        when (command) {
            is WtClientCommand.GuestState -> {
                participant.bufferedAheadMs = command.bufferedAheadMs
                publishParticipants()
            }

            is WtClientCommand.Stall -> {
                participant.stalled = true
                publishParticipants()
                binding.onGuestIntent(command)
            }

            is WtClientCommand.StallCleared -> {
                participant.stalled = false
                participant.bufferedAheadMs = command.bufferedAheadMs
                publishParticipants()
                binding.onGuestIntent(command)
            }

            is WtClientCommand.Leave -> {
                remove(participantId)
            }

            // Transport intents: the host is the only thing that commits them, so they go
            // straight to the player and come back to everyone as the next host state.
            else -> binding.onGuestIntent(command)
        }
    }

    override fun onEventStreamOpened(participantId: String, sink: WatchTogetherEventSink) {
        sinks[participantId] = sink
        participants[participantId]?.lastSeenMs = hostTimeMs()
        // Bring the newcomer level immediately rather than making it wait for the next tick.
        sink.emit(WtServerEvent.State(binding.hostState()))
        publishParticipants()
    }

    override fun onEventStreamClosed(participantId: String) {
        sinks.remove(participantId)
        publishParticipants()
    }

    override fun hostTimeMs(): Long = nowMs()

    // -- Room operations ----------------------------------------------------------------

    fun broadcast(event: WtServerEvent) {
        sinks.values.forEach { sink ->
            runCatching { sink.emit(event) }
        }
    }

    fun rotateSource() {
        val httpServer = server ?: return
        val previous = streamToken
        val fresh = httpServer.issueStreamToken()
        streamToken = fresh
        // The old token keeps working briefly: libmpv re-opens its connection on every seek,
        // so a rotation landing inside one would otherwise 404 the guest mid-motion.
        if (previous.isNotBlank()) httpServer.revokeStreamToken(previous)

        val session = binding.session(fresh)
        if (session == null) {
            broadcast(
                WtServerEvent.SourceUnshareable(
                    kind = "unknown",
                    message = "The host moved to a source that can't be shared.",
                ),
            )
            return
        }
        broadcast(WtServerEvent.SourceChanged(session, binding.hostState()))
    }

    fun kick(participantId: String) {
        sinks[participantId]?.emit(WtServerEvent.RoomClosed("removed by host"))
        remove(participantId)
    }

    /**
     * Forgets a guest that stopped reporting.
     *
     * Without this a vanished guest's stall signal would leave the room paused for ever,
     * because nothing would ever clear it.
     */
    fun dropStaleParticipants() {
        val now = hostTimeMs()
        val stale = participants.values.filter { now - it.lastSeenMs > PARTICIPANT_STALE_MS }
        if (stale.isEmpty()) return
        stale.forEach { participant ->
            log.i { "Guest ${participant.displayName} went quiet; dropping" }
            participants.remove(participant.id)
            sinks.remove(participant.id)
        }
        publishParticipants()
    }

    fun snapshot(): List<WtParticipant> = participants.values.map { it.toWire(sinks.containsKey(it.id)) }

    private fun remove(participantId: String) {
        participants.remove(participantId)
        sinks.remove(participantId)
        publishParticipants()
    }

    private fun publishParticipants() {
        val list = snapshot()
        binding.onParticipantsChanged(list)
        broadcast(WtServerEvent.Participants(list))
    }

    private class Participant(
        val id: String,
        val token: String,
        val displayName: String,
        val p2pEnabled: Boolean,
        @Volatile var lastSeenMs: Long = nowMs(),
        @Volatile var stalled: Boolean = false,
        @Volatile var bufferedAheadMs: Long = 0L,
    ) {
        fun toWire(connected: Boolean) = WtParticipant(
            participantId = id,
            displayName = displayName,
            isHost = false,
            isStalled = stalled,
            bufferedAheadMs = bufferedAheadMs,
            connected = connected,
        )
    }
}

private val secureRandom = SecureRandom()
private val tokenEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

private fun randomToken(bytes: Int = 20): String {
    val buffer = ByteArray(bytes)
    secureRandom.nextBytes(buffer)
    return tokenEncoder.encodeToString(buffer)
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

/**
 * Addresses a guest could plausibly reach, best guess first.
 *
 * Virtual adapters (Hyper-V, VirtualBox, WSL, Docker) advertise perfectly valid site-local
 * addresses that nothing outside this machine can route to, and picking one silently
 * produces a "can't connect" nobody can explain. So they are filtered out, and when more
 * than one candidate survives the host is shown all of them rather than being guessed at.
 */
internal fun localAddresses(): List<String> {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrNull()
        ?: return emptyList()

    val candidates = mutableListOf<Pair<Int, String>>()
    for (nic in interfaces) {
        val usable = runCatching { nic.isUp && !nic.isLoopback && !nic.isVirtual }.getOrDefault(false)
        if (!usable) continue
        if (looksVirtual(nic.displayName) || looksVirtual(nic.name)) continue

        for (address in nic.inetAddresses) {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) continue
            when (address) {
                is Inet4Address -> {
                    // Tailscale's 100.64/10 range is the one most likely to work for a guest
                    // outside the LAN, so it sorts ahead of an ordinary private address.
                    val rank = if (address.hostAddress.startsWith("100.")) 0 else 1
                    candidates += rank to address.hostAddress
                }

                is Inet6Address -> {
                    // Strip the zone id: a URL cannot carry "%eth0", and the bracket form is
                    // what an IPv6 literal needs in an authority.
                    val plain = address.hostAddress.substringBefore('%')
                    candidates += 2 to "[$plain]"
                }

                else -> Unit
            }
        }
    }
    return candidates.sortedBy { it.first }.map { it.second }.distinct()
}

private fun looksVirtual(name: String?): Boolean {
    val lower = name?.lowercase() ?: return false
    return listOf("hyper-v", "virtualbox", "vmware", "wsl", "docker", "loopback", "vethernet")
        .any { lower.contains(it) }
}


/**
 * Asks the room, over the network, whether it can be reached at [baseUrl].
 *
 * Deliberately a real round trip rather than a check of the bind: the socket binds fine and
 * the firewall drops the packets afterwards, which is exactly the case that produces an
 * unexplainable failure for the guest.
 */
private fun isRoomReachable(baseUrl: String, timeoutMs: Int = 1_500): Boolean = runCatching {
    val connection = java.net.URI("$baseUrl/wt/v1/health").toURL().openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = timeoutMs
    connection.readTimeout = timeoutMs
    connection.requestMethod = "GET"
    try {
        connection.responseCode in 200..299
    } finally {
        connection.disconnect()
    }
}.getOrDefault(false)

/**
 * Copyable, and deliberately not self-elevating: silently asking for admin to punch a
 * firewall hole is not something an app should do on the user's behalf.
 */
private fun firewallHint(port: Int): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("windows") ->
            "Windows Firewall is blocking Nuvio on your network. On a Public network it blocks " +
                "with no prompt at all — set the network to Private, or run as administrator: " +
                "netsh advfirewall firewall add rule name=\"Nuvio Watch Together\" dir=in " +
                "action=allow protocol=TCP localport=$port"

        os.contains("mac") ->
            "macOS blocked incoming connections for Nuvio. Allow it in System Settings > " +
                "Network > Firewall > Options."

        else ->
            "Something is blocking incoming connections on port $port. Allow it in your " +
                "firewall, for example: sudo ufw allow $port/tcp"
    }
}
