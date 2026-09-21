package com.nuvio.app.features.watchtogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchTogetherStreamEligibilityTest {
    @Test
    fun aTorrentIsSharedByHashSoTheHostUploadsNothing() {
        val result = classifyWatchTogetherShareability(
            // For torrents the active URL is a sentinel and is not fetchable at all.
            sourceUrl = "torrent://ABCDEF0123456789ABCDEF0123456789ABCDEF01?index=2",
            torrentInfoHash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            torrentFileIdx = 2,
            torrentFilename = "episode.mkv",
            torrentTrackers = listOf("udp://tracker.example:80/announce"),
        )

        val torrent = assertIs<WatchTogetherShareability.Torrent>(result)
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", torrent.infoHash)
        assertEquals(2, torrent.fileIdx)
        assertEquals("episode.mkv", torrent.filename)
    }

    @Test
    fun aTorrentWinsEvenWhenTheStreamTypeLooksAdaptive() {
        val result = classifyWatchTogetherShareability(
            sourceUrl = "torrent://abc?index=0",
            streamType = "hls",
            torrentInfoHash = "abc",
        )
        assertIs<WatchTogetherShareability.Torrent>(result)
    }

    @Test
    fun aProgressiveFileIsProxyable() {
        val result = classifyWatchTogetherShareability(
            sourceUrl = "https://cdn.example/videos/episode.mkv",
        )
        assertEquals(WatchTogetherShareability.Direct, result)
    }

    @Test
    fun anExtensionlessUrlIsAssumedProgressive() {
        // Debrid and many addons hand out opaque URLs with no extension at all.
        val result = classifyWatchTogetherShareability(
            sourceUrl = "https://cdn.example/dl/9f3a2b1c",
        )
        assertEquals(WatchTogetherShareability.Direct, result)
    }

    @Test
    fun theAddonDeclaredStreamTypeIsTheStrongestAdaptiveSignal() {
        // `hls` is a real value here — StreamParser normalises it and its tests assert it.
        val result = classifyWatchTogetherShareability(
            sourceUrl = "https://cdn.example/dl/9f3a2b1c",
            streamType = "HLS",
        )
        assertEquals("HLS", assertIs<WatchTogetherShareability.AdaptiveManifest>(result).kind)
    }

    @Test
    fun theResponseContentTypeCatchesAManifestTheUrlHides() {
        val result = classifyWatchTogetherShareability(
            sourceUrl = "https://cdn.example/play/9f3a2b1c",
            responseHeaders = mapOf("content-type" to "application/vnd.apple.mpegurl; charset=utf-8"),
        )
        assertEquals("HLS", assertIs<WatchTogetherShareability.AdaptiveManifest>(result).kind)
    }

    @Test
    fun aManifestExtensionIsDetectedThroughAQueryString() {
        val result = classifyWatchTogetherShareability(
            sourceUrl = "https://cdn.example/live/master.m3u8?token=abc123&t=99",
        )
        assertEquals("HLS", assertIs<WatchTogetherShareability.AdaptiveManifest>(result).kind)
    }

    @Test
    fun aManifestNamedOnlyInsideTheQueryIsStillDetected() {
        val result = classifyWatchTogetherShareability(
            sourceUrl = "https://cdn.example/play?id=42&format=m3u8",
        )
        assertEquals("HLS", assertIs<WatchTogetherShareability.AdaptiveManifest>(result).kind)
    }

    @Test
    fun dashIsRefusedTooAndNamedCorrectly() {
        assertEquals(
            "DASH",
            assertIs<WatchTogetherShareability.AdaptiveManifest>(
                classifyWatchTogetherShareability("https://cdn.example/v/manifest.mpd"),
            ).kind,
        )
        assertEquals(
            "DASH",
            assertIs<WatchTogetherShareability.AdaptiveManifest>(
                classifyWatchTogetherShareability("https://cdn.example/v/x", streamType = "dash"),
            ).kind,
        )
    }

    @Test
    fun aMagnetOrLocalFileCannotBeShared() {
        assertEquals(
            WatchTogetherShareability.Unshareable,
            classifyWatchTogetherShareability("magnet:?xt=urn:btih:abc"),
        )
        assertEquals(
            WatchTogetherShareability.Unshareable,
            classifyWatchTogetherShareability("file:///C:/videos/episode.mkv"),
        )
        assertEquals(
            WatchTogetherShareability.Unshareable,
            classifyWatchTogetherShareability("   "),
        )
    }

    @Test
    fun customRequestHeadersDoNotBlockSharingBecauseTheProxySuppliesThem() {
        // `notWebReady` sources need headers the guest does not have — which is exactly what
        // the proxy adds upstream, so they are shareable rather than refused.
        val result = classifyWatchTogetherShareability(
            sourceUrl = "https://cdn.example/videos/episode.mp4",
            responseHeaders = mapOf("Content-Type" to "video/mp4"),
        )
        assertEquals(WatchTogetherShareability.Direct, result)
    }
}

/**
 * The descriptor is the whole contract with the guest: everything it needs to rebuild a
 * launch, and nothing that only makes sense on the host's machine.
 */
class WatchTogetherProtocolTest {
    private val session = WtSession(
        sessionId = "url:https://cdn.example/e.mkv",
        title = "Synthetic Show",
        parentMetaId = "tt0000000",
        parentMetaType = "series",
        videoId = "tt0000000:1:2",
        seasonNumber = 1,
        episodeNumber = 2,
        streamTitle = "1080p",
        providerName = "Synthetic Provider",
        durationMs = 1_400_000,
        externalSubtitles = listOf(WtSubtitle("https://subs.example/a.srt", "pt")),
        source = WtSource.Proxy(streamToken = "SYNTHETIC_TOKEN", contentLengthBytes = 123_456),
    )

    @Test
    fun aSessionSurvivesTheWire() {
        val encoded = watchTogetherJson.encodeToString(WtSession.serializer(), session)
        assertEquals(session, watchTogetherJson.decodeFromString(WtSession.serializer(), encoded))
    }

    @Test
    fun theSourceKindIsDiscriminatedExplicitly() {
        val torrent = session.copy(
            source = WtSource.Torrent(infoHash = "abc", fileIdx = 1, trackers = listOf("udp://t")),
        )
        val encoded = watchTogetherJson.encodeToString(WtSession.serializer(), torrent)
        assertTrue(encoded.contains("\"op\":\"torrent\""), encoded)
        assertIs<WtSource.Torrent>(
            watchTogetherJson.decodeFromString(WtSession.serializer(), encoded).source,
        )
    }

    @Test
    fun commandsAndEventsRoundTrip() {
        val command: WtClientCommand = WtClientCommand.IntentSeek(90_000)
        val encodedCommand = watchTogetherJson.encodeToString(WtClientCommand.serializer(), command)
        assertEquals(command, watchTogetherJson.decodeFromString(WtClientCommand.serializer(), encodedCommand))

        val event: WtServerEvent = WtServerEvent.State(
            WtHostState(
                sampleTimeMs = 1_000,
                positionMs = 90_000,
                durationMs = 1_400_000,
                isPlaying = true,
                isLoading = false,
                pauseCause = WtPauseCause.NONE,
                sessionId = session.sessionId,
                sequence = 7,
            ),
        )
        val encodedEvent = watchTogetherJson.encodeToString(WtServerEvent.serializer(), event)
        assertEquals(event, watchTogetherJson.decodeFromString(WtServerEvent.serializer(), encodedEvent))
    }

    /** A newer host adding a field must not break an older guest outright. */
    @Test
    fun anUnknownFieldIsIgnoredRatherThanFatal() {
        val withExtra = """{"op":"ping","serverTimeMs":42,"somethingNew":true}"""
        assertIs<WtServerEvent.Ping>(
            watchTogetherJson.decodeFromString(WtServerEvent.serializer(), withExtra),
        )
    }
}
