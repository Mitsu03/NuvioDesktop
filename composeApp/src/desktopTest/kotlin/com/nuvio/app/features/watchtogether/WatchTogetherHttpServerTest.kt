package com.nuvio.app.features.watchtogether

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the proxy against a synthetic origin.
 *
 * Range correctness is the load-bearing behaviour: libmpv seeks by issuing `Range` requests,
 * so if these fail the guest cannot seek and every later milestone is pointless.
 */
class WatchTogetherHttpServerTest {
    private val bodySize = 1_000_000
    private val body = ByteArray(bodySize) { (it % 251).toByte() }

    private var origin: HttpServer? = null
    private var proxy: WatchTogetherHttpServer? = null
    private val originRequests = mutableListOf<Map<String, String>>()

    @AfterTest
    fun tearDown() {
        proxy?.stop()
        origin?.stop(0)
    }

    private fun startOrigin(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/media") { exchange ->
            try {
                // com.sun.net.httpserver.Headers normalises keys to First-upper-rest-lower,
                // so "User-Agent" arrives as "User-agent". Lowercase to compare reliably.
                originRequests += exchange.requestHeaders.entries
                    .associate { (k, v) -> k.lowercase() to v.joinToString() }
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        origin = server
        return "http://127.0.0.1:${server.address.port}/media"
    }

    /** A minimal origin that honours Range the way a real CDN does. */
    private fun rangeAwareOrigin(): String = startOrigin { exchange ->
        val range = exchange.requestHeaders.getFirst("Range")
        exchange.responseHeaders.set("Content-Type", "video/mp4")
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        // Hop-by-hop noise the proxy must not forward.
        exchange.responseHeaders.set("Connection", "keep-alive")

        if (range == null) {
            exchange.sendResponseHeaders(200, bodySize.toLong())
            if (exchange.requestMethod != "HEAD") exchange.responseBody.write(body)
            return@startOrigin
        }

        val spec = range.removePrefix("bytes=").split('-')
        val start = spec[0].toInt()
        val end = spec.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (bodySize - 1)
        val slice = body.copyOfRange(start, end + 1)
        exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$bodySize")
        exchange.sendResponseHeaders(206, slice.size.toLong())
        if (exchange.requestMethod != "HEAD") exchange.responseBody.write(slice)
    }

    private fun startProxy(originUrl: String, headers: Map<String, String> = emptyMap()): Pair<String, String> {
        val server = WatchTogetherHttpServer(
            originProvider = { WatchTogetherOrigin(url = originUrl, headers = headers) },
        )
        val port = server.start(preferredPort = 0, portScanRange = 1)
        proxy = server
        val token = server.issueStreamToken()
        return "http://127.0.0.1:$port" to token
    }

    private fun request(
        url: String,
        method: String = "GET",
        range: String? = null,
        readTimeoutMs: Int = 0,
    ): Triple<Int, Map<String, String>, ByteArray> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.readTimeout = readTimeoutMs
        range?.let { connection.setRequestProperty("Range", it) }
        connection.connect()
        val code = connection.responseCode
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { (key, _) -> key.lowercase() }
            .mapValues { (_, value) -> value.joinToString() }
        val payload = runCatching {
            val sink = ByteArrayOutputStream()
            (connection.errorStream ?: connection.inputStream)?.use { it.copyTo(sink) }
            sink.toByteArray()
        }.getOrDefault(ByteArray(0))
        connection.disconnect()
        return Triple(code, headers, payload)
    }

    @Test
    fun healthAnswersEvenWithNoRoomContent() {
        val (base, _) = startProxy(rangeAwareOrigin())
        val (code, _, payload) = request("$base/wt/v1/health")
        assertEquals(200, code)
        assertTrue(payload.decodeToString().contains("\"roomOpen\":true"))
    }

    @Test
    fun aFullFetchStreamsTheWholeBody() {
        val (base, token) = startProxy(rangeAwareOrigin())
        val (code, headers, payload) = request("$base/wt/v1/s/$token/stream")

        assertEquals(200, code)
        assertEquals(bodySize, payload.size)
        assertContentEquals(body, payload)
        assertEquals("video/mp4", headers["content-type"])
    }

    /** Without this, the guest cannot seek and the feature does not work at all. */
    @Test
    fun aRangeRequestIsPassedThroughAndAnsweredWith206() {
        val (base, token) = startProxy(rangeAwareOrigin())
        val (code, headers, payload) = request(
            "$base/wt/v1/s/$token/stream",
            range = "bytes=1000-1100",
        )

        assertEquals(206, code)
        assertEquals("bytes 1000-1100/$bodySize", headers["content-range"])
        assertEquals("bytes", headers["accept-ranges"])
        assertEquals(101, payload.size)
        assertContentEquals(body.copyOfRange(1000, 1101), payload)
    }

    @Test
    fun anOpenEndedRangeRunsToTheEndOfTheFile() {
        val (base, token) = startProxy(rangeAwareOrigin())
        val (code, headers, payload) = request(
            "$base/wt/v1/s/$token/stream",
            range = "bytes=999000-",
        )

        assertEquals(206, code)
        assertEquals("bytes 999000-${bodySize - 1}/$bodySize", headers["content-range"])
        assertEquals(1000, payload.size)
    }

    @Test
    fun headReturnsMetadataWithoutABody() {
        val (base, token) = startProxy(rangeAwareOrigin())
        val (code, headers, payload) = request("$base/wt/v1/s/$token/stream", method = "HEAD")

        assertEquals(200, code)
        assertEquals(0, payload.size)
        assertNotNull(headers["content-type"])
    }

    @Test
    fun theHostsOwnRequestHeadersAreForwardedUpstream() {
        // This is what makes a `notWebReady` source work for a guest who has no addon.
        val (base, token) = startProxy(
            rangeAwareOrigin(),
            headers = mapOf("Referer" to "https://origin.example/", "User-Agent" to "NuvioTest"),
        )
        request("$base/wt/v1/s/$token/stream", range = "bytes=0-10")

        val seen = originRequests.last()
        assertEquals("https://origin.example/", seen["referer"])
        assertEquals("NuvioTest", seen["user-agent"])
    }

    @Test
    fun hopByHopHeadersAreNotForwardedToTheGuest() {
        val (base, token) = startProxy(rangeAwareOrigin())
        val (_, headers, _) = request("$base/wt/v1/s/$token/stream", range = "bytes=0-10")
        // The origin sets Connection: keep-alive; forwarding it would describe the wrong hop.
        assertTrue(headers["connection"]?.contains("keep-alive") != true, "got ${headers["connection"]}")
    }

    @Test
    fun anUnknownTokenIsIndistinguishableFromAMissingRoom() {
        val (base, _) = startProxy(rangeAwareOrigin())
        // A 403 here would confirm the path shape to someone probing; 404 says nothing.
        assertEquals(404, request("$base/wt/v1/s/not-a-real-token/stream").first)
    }

    @Test
    fun aRetiredTokenKeepsWorkingBrieflySoAnInFlightSeekSurvives() {
        val (base, token) = startProxy(rangeAwareOrigin())
        proxy!!.revokeStreamToken(token)
        // libmpv re-opens its connection on every seek; a rotation landing inside one would
        // otherwise 404 the guest mid-motion.
        assertEquals(206, request("$base/wt/v1/s/$token/stream", range = "bytes=0-10").first)
    }

    @Test
    fun thereIsNoUrlParameterToTurnThisIntoAnOpenProxy() {
        val (base, token) = startProxy(rangeAwareOrigin())
        val (_, _, payload) = request(
            "$base/wt/v1/s/$token/stream?url=http://attacker.example/anything",
        )
        // The query is ignored entirely: the origin comes from host process state.
        assertContentEquals(body, payload)
    }

    /**
     * The executor regression test. With `HttpServer`'s default `null` executor every
     * handler runs serialized on the dispatcher thread, so one in-flight video response
     * blocks all later requests and the room simply appears to hang.
     */
    @Test
    fun aSlowStreamDoesNotBlockOtherRequests() {
        // Latches rather than sleeps: a timing-based version finishes the transfer before
        // the assertion runs and then passes even with the broken default executor.
        val streamStarted = CountDownLatch(1)
        val releaseStream = CountDownLatch(1)

        val slowOrigin = startOrigin { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, bodySize.toLong())
            exchange.responseBody.write(body, 0, 64 * 1024)
            exchange.responseBody.flush()
            streamStarted.countDown()
            // Hold the response open until the assertion has been made.
            releaseStream.await(5, TimeUnit.SECONDS)
        }
        val (base, token) = startProxy(slowOrigin)

        val streaming = Thread { runCatching { request("$base/wt/v1/s/$token/stream") } }
        streaming.isDaemon = true
        streaming.start()

        assertTrue(streamStarted.await(5, TimeUnit.SECONDS), "the stream never started")
        try {
            // Times out rather than hanging the suite if the server really is blocked.
            assertEquals(200, request("$base/wt/v1/health", readTimeoutMs = 5_000).first)
        } finally {
            releaseStream.countDown()
        }
    }
}
