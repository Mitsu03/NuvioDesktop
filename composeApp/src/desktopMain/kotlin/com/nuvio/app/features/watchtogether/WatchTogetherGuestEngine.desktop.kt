package com.nuvio.app.features.watchtogether

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val log = Logger.withTag("WatchTogetherGuest")
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

internal actual object WatchTogetherGuestEngine {
    actual val isSupported: Boolean = true

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // The host sends an SSE keepalive every second, so 30 s of silence really does
            // mean the connection is dead. Unlimited would park a thread on a vanished host.
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var baseUrl: String? = null

    @Volatile
    private var participantToken: String? = null

    @Volatile
    private var eventCall: Call? = null

    actual suspend fun join(invite: WatchTogetherInvite, request: WtJoinRequest): WtJoinResult =
        withContext(Dispatchers.IO) {
            val body = watchTogetherJson.encodeToString(WtJoinRequest.serializer(), request)
            val http = Request.Builder()
                .url("${invite.baseUrl}/wt/v1/join")
                .post(body.toRequestBody(jsonMediaType))
                .build()

            try {
                client.newCall(http).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    val result = watchTogetherJson.decodeFromString(WtJoinResult.serializer(), payload)
                    if (result is WtJoinResult.Accepted) {
                        baseUrl = invite.baseUrl
                        participantToken = result.participantToken
                    }
                    result
                }
            } catch (e: Exception) {
                log.w(e) { "Join failed" }
                // A network failure and a refusal are different things to the person waiting,
                // so this does not pretend to be a rejection by the host.
                WtJoinResult.Rejected(WtRejectReason.ROOM_CLOSED, e.message ?: "Could not reach the host.")
            }
        }

    /**
     * The host's event stream, parsed as SSE by hand.
     *
     * `okhttp-sse` would be a new dependency for about forty lines of framing, and the
     * framing is fixed by the spec.
     */
    actual fun events(): Flow<WtServerEvent> = flow {
        val base = baseUrl ?: return@flow
        val token = participantToken ?: return@flow

        val http = Request.Builder()
            .url("$base/wt/v1/events")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .build()

        val call = client.newCall(http)
        eventCall = call

        // A blocking socket read does not answer to coroutine cancellation: the host sends a
        // keepalive every second, so the read never times out and a cancelled collector would
        // wait for ever on a thread nobody can reach. Closing the call is the only thing that
        // unblocks it, so cancellation is wired straight to it.
        val cancelOnClose = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                val source = response.body?.source() ?: return@use
                val buffer = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict()
                    when {
                        // A blank line terminates the frame.
                        line.isEmpty() -> {
                            val payload = buffer.toString()
                            buffer.clear()
                            if (payload.isNotBlank()) {
                                runCatching {
                                    watchTogetherJson.decodeFromString(WtServerEvent.serializer(), payload)
                                }
                                    .onSuccess { emit(it) }
                                    // An unknown event from a newer host must not kill the
                                    // stream; skipping it keeps the room alive.
                                    .onFailure { log.d { "Skipping unreadable event: ${it.message}" } }
                            }
                        }

                        line.startsWith("data:") -> buffer.append(line.removePrefix("data:").trim())
                        // ":" comment lines are keepalives; nothing to do but stay alive.
                        else -> Unit
                    }
                }
            }
        } catch (e: Exception) {
            log.d { "Event stream ended: ${e.message}" }
        } finally {
            cancelOnClose?.dispose()
            eventCall = null
        }
    }.flowOn(Dispatchers.IO)

    actual suspend fun send(command: WtClientCommand) {
        val base = baseUrl ?: return
        val token = participantToken ?: return
        withContext(Dispatchers.IO) {
            val body = watchTogetherJson.encodeToString(WtClientCommand.serializer(), command)
            val http = Request.Builder()
                .url("$base/wt/v1/cmd")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            runCatching { client.newCall(http).execute().use { } }
        }
    }

    actual suspend fun probeTime(): WtTimeResponse? {
        val base = baseUrl ?: return null
        val token = participantToken ?: return null
        return withContext(Dispatchers.IO) {
            // The shared monotonic clock, not a fresh nanoTime reading: two monotonic
            // clocks with different origins would bake a constant error into the offset.
            val sentAt = nowMs()
            val body = watchTogetherJson.encodeToString(WtTimeRequest.serializer(), WtTimeRequest(sentAt))
            val http = Request.Builder()
                .url("$base/wt/v1/time")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            runCatching {
                client.newCall(http).execute().use { response ->
                    watchTogetherJson.decodeFromString(
                        WtTimeResponse.serializer(),
                        response.body?.string().orEmpty(),
                    )
                }
            }.getOrNull()
        }
    }

    actual fun leave() {
        eventCall?.cancel()
        eventCall = null
        baseUrl = null
        participantToken = null
    }

    actual fun streamUrl(invite: WatchTogetherInvite, streamToken: String): String =
        "${invite.baseUrl}/wt/v1/s/$streamToken/stream"
}
