package com.nuvio.app.features.player.desktop.autosync

import com.nuvio.app.features.player.AddonSubtitle
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * End-to-end test of the full AutoSync V2 desktop pipeline -- [AutomaticSubtitleSync.run] -- short
 * of the final mpv apply step (which needs a live [com.nuvio.app.features.player.PlayerScreenRuntime]
 * / native player and can't run in a unit test). This is the strongest correctness signal available
 * without a real running app: it proves the loader, the target-subtitle download+parse, and the
 * retiming algorithm are wired together correctly and recover the exact injected offset.
 *
 * Scenario: a synthetic MKV whose embedded subtitle Cues are the "true" timeline, and an external
 * .srt whose own timestamps are a constant 3000ms earlier than the embedded truth (i.e. the
 * subtitle as authored displays each line 3s too early and needs a +3000ms delay to correct).
 */
class AutomaticSubtitleSyncTest {

    @Test
    fun recoversAConstantOffsetFromEmbeddedReference() = runBlocking {
        val cueCount = 60
        val trueOffsetMs = 3000L

        // Irregular spacing on purpose: a perfectly uniform cadence is the one case the matcher
        // deliberately treats as ambiguous (a delay of D is indistinguishable from D + k*spacing),
        // so it correctly refuses to commit. Real dialogue timing is irregular; this mirrors the
        // same pseudo-irregular generator AutoSyncTimelineRetimeTest.kt uses (irregularTimeline).
        val referenceStartTimesMs = irregularStartTimes(cueCount, seedMs = trueOffsetMs + 30_000L)
        val mkvBytes = MatroskaFixture.build(
            trackNumber = 1,
            language = "eng",
            cueStartTimesMs = referenceStartTimesMs,
        )

        // Target subtitle as authored: each line trueOffsetMs earlier than the embedded truth.
        val targetStartTimesMs = referenceStartTimesMs.map { it - trueOffsetMs }
        val srtText = buildSrt(targetStartTimesMs, cueDurationMs = 1500L)

        val server = TestMediaServer(video = mkvBytes, subtitle = srtText)
        try {
            val outcome = AutomaticSubtitleSync.run(
                sourceUrl = server.videoUrl,
                sourceHeaders = emptyMap(),
                subtitle = AddonSubtitle(
                    id = "test-subtitle",
                    url = server.subtitleUrl,
                    language = "eng",
                    display = "English",
                ),
            )

            val result = assertNotNull(outcome, "pipeline returned null for a well-formed scenario")
                .result
            assertTrue(result.confident, "expected a confident match")
            assertEquals(cueCount, result.cues.size)
            assertApproximately(
                expected = trueOffsetMs.toDouble(),
                actual = result.alignmentInterceptMs,
                tolerance = 50.0,
            )
            assertTrue(
                kotlin.math.abs(result.alignmentScale - 1.0) < 0.01,
                "expected near-unity scale, got ${result.alignmentScale}",
            )
        } finally {
            server.stop()
        }
    }

    private fun irregularStartTimes(count: Int, seedMs: Long): List<Long> {
        var start = seedMs
        return (0 until count).map { index ->
            if (index > 0) start += 1_400L + ((index * 977L) % 4_300L)
            start
        }
    }

    private fun assertApproximately(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected +/- $tolerance, got $actual",
        )
    }

    private fun buildSrt(startTimesMs: List<Long>, cueDurationMs: Long): String = buildString {
        startTimesMs.forEachIndexed { index, startMs ->
            append(index + 1)
            append('\n')
            append(formatSrtTimestamp(startMs))
            append(" --> ")
            append(formatSrtTimestamp(startMs + cueDurationMs))
            append('\n')
            append("Line ${index + 1}")
            append("\n\n")
        }
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val hours = ms / 3_600_000L
        val minutes = (ms / 60_000L) % 60L
        val seconds = (ms / 1_000L) % 60L
        val millis = ms % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}

/** Serves a synthetic MKV (Range-aware) and a plain-text subtitle from one local HTTP server. */
internal class TestMediaServer(video: ByteArray, subtitle: String) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/video.mkv") { exchange ->
            exchange.use {
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                val match = rangeHeader?.let { RANGE_PATTERN.find(it) }
                if (match != null) {
                    val start = match.groupValues[1].toInt().coerceIn(0, video.size)
                    val end = match.groupValues[2].toInt().coerceIn(start, video.size - 1)
                    val slice = video.copyOfRange(start, end + 1)
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${video.size}")
                    exchange.sendResponseHeaders(206, slice.size.toLong())
                    exchange.responseBody.write(slice)
                } else {
                    exchange.sendResponseHeaders(200, video.size.toLong())
                    exchange.responseBody.write(video)
                }
            }
        }
        createContext("/subtitle.srt") { exchange ->
            exchange.use {
                val bytes = subtitle.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        start()
    }

    private val port = server.address.port
    val videoUrl: String = "http://127.0.0.1:$port/video.mkv"
    val subtitleUrl: String = "http://127.0.0.1:$port/subtitle.srt"

    fun stop() = server.stop(0)

    private companion object {
        val RANGE_PATTERN = Regex("""bytes=(\d+)-(\d+)""")
    }
}
