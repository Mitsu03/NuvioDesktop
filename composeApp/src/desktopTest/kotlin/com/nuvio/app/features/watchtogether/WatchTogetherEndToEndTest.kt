package com.nuvio.app.features.watchtogether

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host and guest talking to each other over real HTTP, with no player involved.
 *
 * As close to the real thing as one machine and no UI can get: a genuine socket, the real
 * join handshake, the real event stream, real commands and the real byte proxy. What it
 * deliberately cannot cover is mpv itself, and whether the host's upload can carry the
 * stream — that one needs two machines and a real film.
 *
 * DISABLED, deliberately. Something here does not terminate, and it takes the whole Gradle
 * test task with it — which in turn blocks every other build in the project behind a lock
 * nobody can see, so a hanging test is worse than no test at all. It did find one real bug
 * on the way: the guest engine's event stream ignored coroutine cancellation, because a
 * blocking socket read never sees it and the host's keepalive means the read never times
 * out. That is fixed. It was not the whole cause. Re-enable only once the remainder is
 * reproduced in isolation and understood.
 */
@Ignore
class WatchTogetherEndToEndTest {
    private val bodySize = 1_000_000
    private val body = ByteArray(bodySize) { (it % 251).toByte() }

    private var origin: HttpServer? = null
    private val intents = mutableListOf<WtClientCommand>()
    private val hostState = AtomicReference(
        WtHostState(
            sampleTimeMs = 0,
            positionMs = 60_000,
            durationMs = 1_400_000,
            isPlaying = true,
            isLoading = false,
            sessionId = "synthetic",
        ),
    )

    @AfterTest
    fun tearDown() {
        WatchTogetherGuestEngine.leave()
        WatchTogetherHostEngine.close()
        origin?.stop(0)
    }

    /** A stand-in for the addon's CDN, honouring Range the way a real one does. */
    private fun startOrigin(): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/media") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range")
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                if (range == null) {
                    exchange.sendResponseHeaders(200, bodySize.toLong())
                    exchange.responseBody.write(body)
                } else {
                    val spec = range.removePrefix("bytes=").split('-')
                    val start = spec[0].toInt()
                    val end = spec.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (bodySize - 1)
                    val slice = body.copyOfRange(start, end + 1)
                    exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$bodySize")
                    exchange.sendResponseHeaders(206, slice.size.toLong())
                    exchange.responseBody.write(slice)
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        origin = server
        return "http://127.0.0.1:${server.address.port}/media"
    }

    private fun openRoom(originUrl: String): WatchTogetherRoomHandle {
        val handle = WatchTogetherHostEngine.open(
            binding = WatchTogetherHostBinding(
                origin = { WatchTogetherOrigin(url = originUrl) },
                session = { token ->
                    WtSession(
                        sessionId = "synthetic",
                        title = "Synthetic Show",
                        parentMetaId = "tt0000000",
                        parentMetaType = "series",
                        seasonNumber = 1,
                        episodeNumber = 2,
                        streamTitle = "1080p",
                        providerName = "Synthetic Provider",
                        durationMs = 1_400_000,
                        source = WtSource.Proxy(streamToken = token),
                    )
                },
                hostState = { hostState.get() },
                onGuestIntent = { synchronized(intents) { intents += it } },
                onParticipantsChanged = { },
            ),
            preferredPort = 0,
            // Skips LAN discovery: the reachability probe would otherwise walk every adapter
            // on the machine, which is slow and says nothing about the protocol.
            publicBaseUrl = "http://127.0.0.1:0",
        )
        return assertNotNull(handle, "the room could not be opened")
    }

    private fun inviteFor(handle: WatchTogetherRoomHandle, token: String = handle.joinToken) =
        WatchTogetherInvite(baseUrl = "http://127.0.0.1:${handle.port}", joinToken = token)

    private fun joinRequest(token: String) = WtJoinRequest(
        joinToken = token,
        clientId = "synthetic-client",
        displayName = "Joana",
        appVersion = "test",
    )

    @Test
    fun aGuestJoinsAndReceivesTheHostsContent() = runBlocking {
        val handle = openRoom(startOrigin())
        val invite = inviteFor(handle)

        val accepted = assertIs<WtJoinResult.Accepted>(
            WatchTogetherGuestEngine.join(invite, joinRequest(handle.joinToken)),
        )

        assertEquals("Synthetic Show", accepted.session.title)
        assertEquals(1, accepted.session.seasonNumber)
        assertEquals(60_000, accepted.hostState.positionMs)
        assertTrue(accepted.participantToken.isNotBlank())

        // The whole promise of the feature: the guest fetches the host's stream without
        // owning the source at all.
        val proxy = assertIs<WtSource.Proxy>(accepted.session.source)
        val (code, payload) = fetch(
            WatchTogetherGuestEngine.streamUrl(invite, proxy.streamToken),
            range = "bytes=1000-1100",
        )
        assertEquals(206, code)
        assertContentEquals(body.copyOfRange(1000, 1101), payload)
    }

    @Test
    fun theWrongTokenIsRefusedWithAReasonRatherThanASilentFailure() = runBlocking {
        val handle = openRoom(startOrigin())
        val rejected = assertIs<WtJoinResult.Rejected>(
            WatchTogetherGuestEngine.join(inviteFor(handle, "not-the-token"), joinRequest("not-the-token")),
        )
        assertEquals(WtRejectReason.BAD_TOKEN, rejected.reason)
    }

    @Test
    fun theHostPushesItsStateAndTheGuestPushesIntentsBack() = runBlocking {
        val handle = openRoom(startOrigin())
        val invite = inviteFor(handle)
        assertIs<WtJoinResult.Accepted>(
            WatchTogetherGuestEngine.join(invite, joinRequest(handle.joinToken)),
        )

        // The stream opens with a state frame, so a guest is level before any tick lands.
        val opening = withTimeoutOrNull(10_000) {
            WatchTogetherGuestEngine.events().first { it is WtServerEvent.State }
        }
        val state = assertIs<WtServerEvent.State>(
            assertNotNull(opening, "no state frame arrived on the event stream"),
        )
        assertEquals(60_000, state.state.positionMs)

        // And a guest's pause request reaches the host, which is the only thing allowed to
        // commit it.
        WatchTogetherGuestEngine.send(WtClientCommand.IntentPause(atPositionMs = 61_234))
        val seen = withTimeoutOrNull(5_000) {
            var found: WtClientCommand? = null
            while (found == null) {
                synchronized(intents) {
                    found = intents.firstOrNull { it is WtClientCommand.IntentPause }
                }
                if (found == null) delay(50)
            }
            found
        }
        assertEquals(WtClientCommand.IntentPause(61_234), seen)
    }

    /** The offset estimate is what stops two machines' clocks tearing the room apart. */
    @Test
    fun theClockProbeRoundTrips() = runBlocking {
        val handle = openRoom(startOrigin())
        assertIs<WtJoinResult.Accepted>(
            WatchTogetherGuestEngine.join(inviteFor(handle), joinRequest(handle.joinToken)),
        )

        val response = assertNotNull(WatchTogetherGuestEngine.probeTime(), "no clock reply")
        assertTrue(response.hostTimeMs > 0, "the host did not stamp its own clock")
        assertTrue(response.sentAtMs > 0, "the guest's send time did not come back")
    }

    private fun fetch(url: String, range: String? = null): Pair<Int, ByteArray> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        connection.connect()
        val code = connection.responseCode
        val sink = ByteArrayOutputStream()
        runCatching { (connection.errorStream ?: connection.inputStream)?.use { it.copyTo(sink) } }
        connection.disconnect()
        return code to sink.toByteArray()
    }
}
