package com.nuvio.app.features.player.desktop.autosync

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * Exercises [EmbeddedSubtitleTimelineLoader] end-to-end against a hand-built, minimal Matroska
 * byte stream served locally over HTTP with real Range-request handling -- the same transport
 * path used against real debrid/streaming URLs. There's no real MKV fixture file; the bytes are
 * synthesized directly from the EBML IDs the loader itself parses (Segment/Tracks/Cues), which
 * keeps this test self-contained and avoids depending on ffmpeg or a checked-in binary fixture.
 */
class EmbeddedSubtitleTimelineLoaderTest {

    @Test
    fun loadsSubtitleCueTimelineFromRangedMatroskaCues() = runBlocking {
        val cueCount = 12
        val cueSpacingMs = 4000L
        val mkvBytes = MatroskaFixture.build(
            trackNumber = 1,
            language = "eng",
            cueStartTimesMs = List(cueCount) { it * cueSpacingMs },
        )

        val server = RangeHttpServer(mkvBytes)
        try {
            val timeline = EmbeddedSubtitleTimelineLoader.load(server.url)

            val loaded = assertNotNull(timeline, "loader returned null for a well-formed fixture")
            assertEquals("matroska-cues", loaded.source)
            assertEquals(1, loaded.tracks.size)

            val track = loaded.tracks.single()
            assertEquals("eng", track.language)
            assertEquals(cueCount, track.cues.size)
            assertEquals(0L, track.cues.first().startTimeMs)
            assertEquals((cueCount - 1) * cueSpacingMs, track.cues.last().startTimeMs)
        } finally {
            server.stop()
        }
    }

    @Test
    fun returnsNullWhenSubtitleTimelineIsTooShortToTrust() = runBlocking {
        // Below MIN_INDEXED_CUES (8): AutoSync must not treat a sparse index as a usable reference.
        val mkvBytes = MatroskaFixture.build(
            trackNumber = 1,
            language = "eng",
            cueStartTimesMs = listOf(0L, 4000L, 8000L),
        )

        val server = RangeHttpServer(mkvBytes)
        try {
            val timeline = EmbeddedSubtitleTimelineLoader.load(server.url)
            assertNull(timeline, "a 3-cue index should be rejected as unusably short")
        } finally {
            server.stop()
        }
    }

    @Test
    fun returnsNullForNonMatroskaBytes() = runBlocking {
        val server = RangeHttpServer(ByteArray(4096) { 0x00 })
        try {
            val timeline = EmbeddedSubtitleTimelineLoader.load(server.url)
            assertNull(timeline, "arbitrary non-EBML bytes must not be mistaken for a container")
        } finally {
            server.stop()
        }
    }
}

/** A tiny local HTTP server that honors byte-Range requests, mirroring a real streaming source. */
private class RangeHttpServer(private val bytes: ByteArray) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/fixture.mkv") { exchange ->
            exchange.use {
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                val match = rangeHeader?.let { RANGE_PATTERN.find(it) }
                if (match != null) {
                    val start = match.groupValues[1].toInt().coerceIn(0, bytes.size)
                    val end = match.groupValues[2].toInt().coerceIn(start, bytes.size - 1)
                    val slice = bytes.copyOfRange(start, end + 1)
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                    exchange.sendResponseHeaders(206, slice.size.toLong())
                    exchange.responseBody.write(slice)
                } else {
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            }
        }
        start()
    }

    val url: String = "http://127.0.0.1:${server.address.port}/fixture.mkv"

    fun stop() = server.stop(0)

    private companion object {
        val RANGE_PATTERN = Regex("""bytes=(\d+)-(\d+)""")
    }
}

/** Hand-rolled minimal EBML/Matroska byte builder covering only what the loader reads. */
internal object MatroskaFixture {
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val TRACK_TYPE_SUBTITLE = 17L

    fun build(trackNumber: Int, language: String, cueStartTimesMs: List<Long>): ByteArray {
        val tracks = element(
            ID_TRACKS,
            element(
                ID_TRACK_ENTRY,
                element(ID_TRACK_NUMBER, uint(trackNumber.toLong())) +
                    element(ID_TRACK_TYPE, uint(TRACK_TYPE_SUBTITLE)) +
                    element(ID_LANGUAGE, language.encodeToByteArray()),
            ),
        )

        val cues = element(
            ID_CUES,
            cueStartTimesMs.fold(ByteArrayOutputStream()) { out, startMs ->
                out.write(
                    element(
                        ID_CUE_POINT,
                        element(ID_CUE_TIME, uint(startMs)) +
                            element(
                                ID_CUE_TRACK_POSITIONS,
                                element(ID_CUE_TRACK, uint(trackNumber.toLong())),
                            ),
                    ),
                )
                out
            }.toByteArray(),
        )

        return element(ID_SEGMENT, tracks + cues)
    }

    /** [id]'s own bit-length already encodes its canonical byte width (standard Matroska IDs). */
    private fun element(id: Long, payload: ByteArray): ByteArray =
        idBytes(id) + sizeVint(payload.size.toLong()) + payload

    private fun idBytes(id: Long): ByteArray {
        val length = when {
            id > 0xFFFFFFL -> 4
            id > 0xFFFFL -> 3
            id > 0xFFL -> 2
            else -> 1
        }
        return ByteArray(length) { index ->
            ((id shr (8 * (length - 1 - index))) and 0xFF).toByte()
        }
    }

    /** Mirrors [EmbeddedSubtitleTimelineLoader]'s VINT decode exactly, in reverse. */
    private fun sizeVint(value: Long): ByteArray {
        var length = 1
        while (length < 8 && value > (1L shl (7 * length)) - 2) length++
        val bytes = ByteArray(length)
        var remaining = value
        for (index in length - 1 downTo 1) {
            bytes[index] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        val marker = 1 shl (8 - length)
        bytes[0] = ((remaining.toInt() and (marker - 1)) or marker).toByte()
        return bytes
    }

    /**
     * Fixed-width (4-byte) big-endian unsigned integer -- big enough for ~49 days of milliseconds,
     * so real-length movie timelines never silently wrap around a narrower fixed width.
     */
    private fun uint(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
