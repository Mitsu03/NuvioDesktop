package com.nuvio.app.features.watchtogether

import co.touchlab.kermit.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the server needs from the room to answer the control endpoints.
 *
 * Kept behind an interface so the transport knows nothing about rooms, participants or the
 * player — swapping SSE for WebSockets later would touch this file and nothing else.
 */
internal interface WatchTogetherControlPlane {
    fun join(request: WtJoinRequest): WtJoinResult

    /** @return the participant id, or null when the bearer is unknown or revoked. */
    fun participantFor(bearerToken: String): String?

    fun onCommand(participantId: String, command: WtClientCommand)

    fun onEventStreamOpened(participantId: String, sink: WatchTogetherEventSink)

    fun onEventStreamClosed(participantId: String)

    fun hostTimeMs(): Long
}

/** One connected guest's outbound channel. */
internal fun interface WatchTogetherEventSink {
    fun emit(event: WtServerEvent)
}

/**
 * The host's in-process server: one port carrying both the control channel and the video.
 *
 * Built on `com.sun.net.httpserver` deliberately — `jdk.httpserver` is already in the
 * packaging module list, so this adds nothing to the installed runtime, where a Ktor server
 * would need new dependencies re-verified against jlink.
 */
internal class WatchTogetherHttpServer(
    private val originProvider: () -> WatchTogetherOrigin?,
    /** Absent in the proxy-only tests; the control endpoints then answer 503. */
    private val controlPlane: WatchTogetherControlPlane? = null,
) {
    private val log = Logger.withTag("WatchTogetherServer")

    private var server: HttpServer? = null
    private val tokens = StreamTokenStore()

    @Volatile
    private var roomOpen: Boolean = false

    val boundPort: Int? get() = server?.address?.port
    val isRunning: Boolean get() = server != null

    /**
     * A dedicated client: the app's shared desktop HTTP client is tuned for short addon JSON
     * calls, and its read timeout would cut a film in half.
     */
    private val proxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Generous rather than unlimited: this bounds the gap *between* bytes, which a
            // live stream never approaches, but it still lets the thread die when an origin
            // hangs — with an unlimited read timeout a stalled CDN parks a thread for ever.
            .readTimeout(120, TimeUnit.SECONDS)
            // The call as a whole is unbounded: a film legitimately takes hours.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Binds the first free port at or after [preferredPort].
     *
     * Binds the **wildcard dual-stack** address rather than `0.0.0.0`: Tailscale — the
     * documented answer for guests outside the LAN — hands out IPv6 alongside its `100.x`
     * addresses, and `InetSocketAddress("0.0.0.0", port)` is IPv4-only. Deliberately unlike
     * TorrServer, which binds loopback.
     *
     * Called only when the user actually opens a room, so the firewall prompt arrives in
     * context rather than at app start.
     */
    fun start(preferredPort: Int = DEFAULT_PORT, portScanRange: Int = PORT_SCAN_RANGE): Int {
        server?.let { return it.address.port }

        var lastFailure: Exception? = null
        for (port in preferredPort until preferredPort + portScanRange) {
            try {
                val created = HttpServer.create(InetSocketAddress(port), BACKLOG)
                configure(created)
                created.start()
                server = created
                roomOpen = true
                // The bound address, not the loop variable: a preferred port of 0 means
                // "any free port", and the loop variable would then report 0.
                val bound = created.address.port
                log.i { "Watch Together server listening on port $bound" }
                return bound
            } catch (e: BindException) {
                lastFailure = e
            }
        }

        // Every candidate was taken; let the OS choose rather than refusing to open a room.
        val fallback = HttpServer.create(InetSocketAddress(0), BACKLOG)
        configure(fallback)
        fallback.start()
        server = fallback
        roomOpen = true
        log.w(lastFailure) { "Preferred ports were busy; bound ephemeral ${fallback.address.port}" }
        return fallback.address.port
    }

    fun stop(delaySeconds: Int = 0) {
        roomOpen = false
        tokens.clear()
        server?.let {
            it.stop(delaySeconds)
            log.i { "Watch Together server stopped" }
        }
        server = null
    }

    /** Mints a token for the current source. One token, one fixed path — never a `?url=`. */
    fun issueStreamToken(): String = tokens.issue()

    /**
     * Retires a token when the source switches.
     *
     * The old token stays valid for [STREAM_TOKEN_GRACE_MS]: libmpv drops and re-opens its
     * connection on every seek, so a rotation landing inside a seek would 404 the guest
     * mid-motion.
     */
    fun revokeStreamToken(token: String) = tokens.revoke(token, STREAM_TOKEN_GRACE_MS)

    private fun configure(target: HttpServer) {
        // Without an explicit executor, HttpServer runs every handler on the dispatcher
        // thread, serialized — so the first long-lived response (an event stream, or a film)
        // blocks every later request and the room simply appears to hang.
        target.executor = Executors.newCachedThreadPool(DaemonThreads("nuvio-wt"))

        target.createContext("/wt/v1/health") { exchange -> guarded(exchange) { handleHealth(it) } }
        target.createContext("/wt/v1/s/") { exchange -> guarded(exchange) { handleStream(it) } }
        target.createContext("/wt/v1/join") { exchange -> guarded(exchange) { handleJoin(it) } }
        target.createContext("/wt/v1/events") { exchange -> guarded(exchange) { handleEvents(it) } }
        target.createContext("/wt/v1/cmd") { exchange -> guarded(exchange) { handleCommand(it) } }
        target.createContext("/wt/v1/time") { exchange -> guarded(exchange) { handleTime(it) } }
    }

    // -----------------------------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------------------------

    /** Also the firewall self-check: the host hits this on its own LAN address before inviting. */
    private fun handleHealth(exchange: HttpExchange) {
        val body = watchTogetherJson.encodeToString(WtHealth.serializer(), WtHealth(roomOpen = roomOpen))
        respondText(exchange, 200, body, "application/json; charset=utf-8")
    }

    private fun handleStream(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        if (method != "GET" && method != "HEAD") {
            respondText(exchange, 405, "", "text/plain")
            return
        }

        val token = streamTokenFromPath(exchange.requestURI.path)
        // A uniform 404 for a bad or expired token: a 403 would confirm the path shape.
        if (token == null || !tokens.isValid(token)) {
            respondText(exchange, 404, "", "text/plain")
            return
        }

        val origin = originProvider()
        if (origin == null) {
            respondText(exchange, 404, "", "text/plain")
            return
        }

        val builder = Request.Builder().url(origin.url)
        origin.headers.forEach { (name, value) -> builder.header(name, value) }
        // `sanitizePlaybackHeaders` strips Range from the host's own headers, so the guest's
        // is the only one in play and there is nothing to collide with.
        exchange.requestHeaders.getFirst("Range")?.let { builder.header("Range", it) }
        exchange.requestHeaders.getFirst("If-Range")?.let { builder.header("If-Range", it) }
        if (method == "HEAD") builder.head()

        val call = proxyClient.newCall(builder.build())
        try {
            call.execute().use { upstream ->
                val body = upstream.body
                PASSTHROUGH_RESPONSE_HEADERS.forEach { name ->
                    upstream.header(name)?.let { exchange.responseHeaders.set(name, it) }
                }
                origin.responseHeaders.forEach { (name, value) ->
                    if (!isHopByHop(name)) exchange.responseHeaders.set(name, value)
                }
                if (upstream.code == 206) exchange.responseHeaders.set("Accept-Ranges", "bytes")

                if (method == "HEAD" || body == null) {
                    // -1 means "no body". 0 would mean "chunked, length unknown" — the
                    // opposite of the intuitive reading.
                    exchange.sendResponseHeaders(upstream.code, -1)
                    return
                }

                val declaredLength = upstream.header("Content-Length")?.toLongOrNull()
                exchange.sendResponseHeaders(upstream.code, declaredLength ?: 0L)

                // Streamed, never buffered: readBytes() here would pull a whole film into
                // the host's heap.
                body.byteStream().use { input ->
                    input.copyTo(exchange.responseBody, COPY_BUFFER_BYTES)
                }
            }
        } catch (e: IOException) {
            // Normal: the guest seeked, so libmpv dropped this connection. Cancelling matters
            // — without it an upstream connection leaks per seek and host upload saturates.
            call.cancel()
            log.d { "Stream connection closed early: ${e.message}" }
        } catch (e: Exception) {
            call.cancel()
            log.w(e) { "Stream proxy failed" }
            runCatching { exchange.sendResponseHeaders(502, -1) }
        }
    }

    // -----------------------------------------------------------------------------------
    // Control plane
    // -----------------------------------------------------------------------------------

    private fun handleJoin(exchange: HttpExchange) {
        val control = controlPlane ?: run { respondText(exchange, 503, "", "text/plain"); return }
        if (exchange.requestMethod.uppercase() != "POST") {
            respondText(exchange, 405, "", "text/plain")
            return
        }

        val request = runCatching {
            watchTogetherJson.decodeFromString(
                WtJoinRequest.serializer(),
                exchange.requestBody.readBytes().decodeToString(),
            )
        }.getOrNull()

        if (request == null) {
            respondText(exchange, 400, "", "text/plain")
            return
        }

        val result = control.join(request)
        // A rejection is still a well-formed answer: the guest needs to be told *why*, or
        // "it didn't work" is all anyone ever learns.
        val code = if (result is WtJoinResult.Accepted) 200 else 403
        respondJson(exchange, code, watchTogetherJson.encodeToString(WtJoinResult.serializer(), result))
    }

    /**
     * The host→guest push channel, as Server-Sent Events.
     *
     * Real `text/event-stream` framing rather than bare newline-delimited JSON: tunnels and
     * reverse proxies special-case that content type and won't sit on it buffering.
     */
    private fun handleEvents(exchange: HttpExchange) {
        val control = controlPlane ?: run { respondText(exchange, 503, "", "text/plain"); return }
        val participantId = control.participantFor(bearerToken(exchange).orEmpty())
        if (participantId == null) {
            respondText(exchange, 404, "", "text/plain")
            return
        }

        val queue = LinkedBlockingQueue<WtServerEvent>(EVENT_QUEUE_CAPACITY)
        // Never block the caller: a guest whose socket has wedged must not stall the host's
        // broadcast loop, so a full queue drops the oldest state frame instead.
        val sink = WatchTogetherEventSink { event ->
            if (!queue.offer(event)) {
                queue.poll()
                queue.offer(event)
            }
        }

        exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-cache, no-store")
        exchange.responseHeaders.set("X-Accel-Buffering", "no")
        exchange.sendResponseHeaders(200, 0) // 0 = chunked, length unknown

        control.onEventStreamOpened(participantId, sink)
        val out = exchange.responseBody
        try {
            while (true) {
                val event = queue.poll(SSE_KEEPALIVE_MS, TimeUnit.MILLISECONDS)
                if (event == null) {
                    // An SSE comment: invisible to the client, but a write, so a dead peer
                    // surfaces as an IOException here rather than being held open for ever.
                    out.write(": keepalive\n\n".encodeToByteArray())
                } else {
                    out.write(encodeSseFrame(event))
                }
                out.flush()
            }
        } catch (e: IOException) {
            log.d { "Event stream for $participantId closed: ${e.message}" }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            control.onEventStreamClosed(participantId)
        }
    }

    private fun handleCommand(exchange: HttpExchange) {
        val control = controlPlane ?: run { respondText(exchange, 503, "", "text/plain"); return }
        if (exchange.requestMethod.uppercase() != "POST") {
            respondText(exchange, 405, "", "text/plain")
            return
        }
        val participantId = control.participantFor(bearerToken(exchange).orEmpty())
        if (participantId == null) {
            respondText(exchange, 404, "", "text/plain")
            return
        }

        val command = runCatching {
            watchTogetherJson.decodeFromString(
                WtClientCommand.serializer(),
                exchange.requestBody.readBytes().decodeToString(),
            )
        }.getOrNull()

        if (command == null) {
            respondText(exchange, 400, "", "text/plain")
            return
        }

        control.onCommand(participantId, command)
        // Echo the host clock on every command: this doubles as the guest's clock probe, so
        // ordinary traffic keeps the offset estimate fresh for free.
        respondJson(
            exchange,
            200,
            watchTogetherJson.encodeToString(
                WtTimeResponse.serializer(),
                WtTimeResponse(sentAtMs = 0, hostTimeMs = control.hostTimeMs()),
            ),
        )
    }

    private fun handleTime(exchange: HttpExchange) {
        val control = controlPlane ?: run { respondText(exchange, 503, "", "text/plain"); return }
        if (control.participantFor(bearerToken(exchange).orEmpty()) == null) {
            respondText(exchange, 404, "", "text/plain")
            return
        }
        val request = runCatching {
            watchTogetherJson.decodeFromString(
                WtTimeRequest.serializer(),
                exchange.requestBody.readBytes().decodeToString(),
            )
        }.getOrNull() ?: WtTimeRequest(0)

        respondJson(
            exchange,
            200,
            watchTogetherJson.encodeToString(
                WtTimeResponse.serializer(),
                WtTimeResponse(sentAtMs = request.sentAtMs, hostTimeMs = control.hostTimeMs()),
            ),
        )
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private fun encodeSseFrame(event: WtServerEvent): ByteArray {
        val payload = watchTogetherJson.encodeToString(WtServerEvent.serializer(), event)
        // A newline inside data would end the frame early; the JSON encoder never emits one,
        // but splitting defensively costs nothing and makes that invariant explicit.
        val data = payload.lineSequence().joinToString("\n") { "data: $it" }
        return "$data\n\n".encodeToByteArray()
    }

    private fun bearerToken(exchange: HttpExchange): String? =
        exchange.requestHeaders.getFirst("Authorization")
            ?.trim()
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun respondJson(exchange: HttpExchange, code: Int, body: String) =
        respondText(exchange, code, body, "application/json; charset=utf-8")

    /** `/wt/v1/s/{token}/stream` — the token is a path segment, never a query parameter. */
    private fun streamTokenFromPath(path: String): String? {
        val parts = path.trim('/').split('/')
        // wt / v1 / s / {token} / stream
        if (parts.size != 5) return null
        if (parts[0] != "wt" || parts[1] != "v1" || parts[2] != "s" || parts[4] != "stream") return null
        return parts[3].takeIf { it.isNotBlank() }
    }

    private fun respondText(exchange: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", contentType)
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(code, -1)
            return
        }
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun guarded(exchange: HttpExchange, block: (HttpExchange) -> Unit) {
        try {
            block(exchange)
        } catch (e: Exception) {
            log.w(e) { "Unhandled error serving ${exchange.requestURI.path}" }
            runCatching { exchange.sendResponseHeaders(500, -1) }
        } finally {
            exchange.close()
        }
    }

    private class DaemonThreads(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger(1)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$prefix-${counter.getAndIncrement()}").apply { isDaemon = true }
    }

    companion object {
        const val DEFAULT_PORT: Int = 47500
        const val PORT_SCAN_RANGE: Int = 21
        private const val BACKLOG = 8
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val STREAM_TOKEN_GRACE_MS = 10_000L
        private const val SSE_KEEPALIVE_MS = 1_000L

        /** Deep enough to absorb a burst, shallow enough that a wedged guest stays current. */
        private const val EVENT_QUEUE_CAPACITY = 64

        /**
         * Everything else is dropped. `Content-Length` in particular is set by
         * `sendResponseHeaders`, and hop-by-hop headers (`Connection`, `Transfer-Encoding`,
         * `Keep-Alive`, `TE`, `Trailer`, `Upgrade`, `Proxy-*`) describe the upstream
         * connection rather than this one, so forwarding them corrupts the response.
         */
        private val PASSTHROUGH_RESPONSE_HEADERS = listOf(
            "Content-Type",
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
            "Content-Disposition",
        )

        private val HOP_BY_HOP = setOf(
            "connection", "transfer-encoding", "keep-alive", "te", "trailer", "upgrade",
            "proxy-authenticate", "proxy-authorization", "content-length",
        )

        private fun isHopByHop(name: String): Boolean {
            val lower = name.lowercase()
            return lower in HOP_BY_HOP || lower.startsWith("proxy-")
        }
    }
}

/**
 * Live stream tokens, with a grace period on retirement.
 *
 * Tokens are compared in constant time and only ever logged as a short fingerprint.
 */
private class StreamTokenStore {
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** token -> the monotonic ms after which it stops working; [Long.MAX_VALUE] while live. */
    private val tokens = ConcurrentHashMap<String, Long>()

    fun issue(): String {
        val bytes = ByteArray(20) // 160 bits
        random.nextBytes(bytes)
        val token = encoder.encodeToString(bytes)
        tokens[token] = Long.MAX_VALUE
        return token
    }

    fun isValid(candidate: String): Boolean {
        val now = nowMs()
        var matched = false
        for ((token, expiresAt) in tokens) {
            // No early exit: every entry is compared so the timing carries no information.
            if (constantTimeEquals(token, candidate) && now < expiresAt) matched = true
        }
        return matched
    }

    fun revoke(token: String, graceMs: Long) {
        if (!tokens.containsKey(token)) return
        tokens[token] = nowMs() + graceMs
    }

    fun clear() = tokens.clear()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
