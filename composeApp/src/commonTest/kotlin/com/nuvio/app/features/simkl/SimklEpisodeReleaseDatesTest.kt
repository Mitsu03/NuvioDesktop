package com.nuvio.app.features.simkl

import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.details.MetaVideo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class SimklEpisodeReleaseDatesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `anime releases are keyed by the tvdb mapping and repeated episodes collapse`() {
        val releases = parseSimklEpisodeReleases(json, EarlyStreamAnimeEpisodes, isAnime = true)

        assertEquals(
            mapOf(
                (1 to 1) to epochMs("2026-09-27T14:30:00Z"),
                (1 to 2) to epochMs("2026-10-04T14:30:00Z"),
                (1 to 3) to epochMs("2026-10-11T14:30:00Z"),
            ),
            releases,
        )
    }

    @Test
    fun `show releases use their own season numbering and utc offset`() {
        val releases = parseSimklEpisodeReleases(json, ShowEpisodes, isAnime = false)

        assertEquals(
            mapOf(
                (1 to 9) to epochMs("2023-03-13T01:00:00Z"),
                (2 to 1) to epochMs("2025-04-14T01:00:00Z"),
            ),
            releases,
        )
    }

    @Test
    fun `an earlier simkl date replaces the broadcast date`() {
        val releases = parseSimklEpisodeReleases(json, EarlyStreamAnimeEpisodes, isAnime = true)
        val videos = listOf(
            video(season = 1, episode = 2, released = "2026-10-09T00:00:00.000Z"),
            video(season = 1, episode = 3, released = "2026-10-16T00:00:00.000Z"),
        )

        assertEquals(
            listOf("2026-10-04T14:30:00.000Z", "2026-10-11T14:30:00.000Z"),
            videos.withEarlierSimklReleases(releases).map(MetaVideo::released),
        )
    }

    @Test
    fun `a later or missing simkl date keeps the addon date`() {
        val releases = mapOf(
            (1 to 1) to epochMs("2026-10-20T00:00:00Z"),
            (1 to 2) to epochMs("2026-10-01T00:00:00Z"),
        )
        val videos = listOf(
            video(season = 1, episode = 1, released = "2026-10-16T00:00:00.000Z"),
            video(season = 1, episode = 2, released = null),
            video(season = 4, episode = 2, released = "2026-10-09T16:15:00.000Z"),
            video(season = null, episode = null, released = "2026-10-16T00:00:00.000Z"),
        )

        assertEquals(videos, videos.withEarlierSimklReleases(releases))
    }

    @Test
    fun `a date only addon release is compared from midnight utc`() {
        val videos = listOf(video(season = 1, episode = 3, released = "2026-10-16"))

        assertEquals(
            listOf("2026-10-15T18:00:00.000Z"),
            videos
                .withEarlierSimklReleases(mapOf((1 to 3) to epochMs("2026-10-16T03:00:00+09:00")))
                .map(MetaVideo::released),
        )
    }

    @Test
    fun `search results map shows and anime to their episode endpoints`() {
        val sources = parseSimklSearchEpisodeSources(
            json,
            """
            [
              {"type":"tv","title":"The Last of Us","ids":{"simkl":1482218}},
              {"type":"anime","title":"Tempal: Item no Chikara","ids":{"simkl":3140896}},
              {"type":"movie","title":"A Movie","ids":{"simkl":7}},
              {"type":"show","title":"Legacy type","ids":{"simkl":42}}
            ]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                SimklEpisodeSource(path = "tv", simklId = 1482218L),
                SimklEpisodeSource(path = "anime", simklId = 3140896L),
                SimklEpisodeSource(path = "tv", simklId = 42L),
            ),
            sources,
        )
    }

    @Test
    fun `library lookup returns every season entry of a series and skips movies`() {
        val snapshot = SimklSyncSnapshot(
            entries = listOf(
                libraryEntry(SimklMediaType.ANIME, simkl = 1372805L, imdb = "tt13196080"),
                libraryEntry(SimklMediaType.ANIME, simkl = 2600001L, imdb = "tt13196080"),
                libraryEntry(SimklMediaType.SHOWS, simkl = 1482218L, imdb = "tt3581920"),
                libraryEntry(SimklMediaType.MOVIES, simkl = 99L, imdb = "tt13196080"),
            ),
        )

        assertEquals(
            listOf(
                SimklEpisodeSource(path = "anime", simklId = 2600001L),
                SimklEpisodeSource(path = "anime", simklId = 1372805L),
            ),
            snapshot.episodeSourcesFor(listOf("tt13196080")),
        )
        assertEquals(
            listOf(SimklEpisodeSource(path = "tv", simklId = 1482218L)),
            snapshot.episodeSourcesFor(listOf("tt3581920")),
        )
        assertEquals(emptyList(), snapshot.episodeSourcesFor(listOf("tt0000001")))
    }

    @Test
    fun `utc formatting matches the addon release shape`() {
        assertEquals("1970-01-01T00:00:00.000Z", utcIsoDateTime(0L))
        assertEquals("1969-12-31T23:59:59.999Z", utcIsoDateTime(-1L))
        listOf(
            "2024-02-29T23:59:59.000Z",
            "2026-10-11T14:30:00.000Z",
            "2026-12-31T00:00:00.000Z",
            "2027-01-01T00:00:00.000Z",
            "2100-03-01T12:00:00.000Z",
        ).forEach { iso ->
            assertEquals(iso, utcIsoDateTime(epochMs(iso)))
        }
    }

    private fun epochMs(iso: String): Long = requireNotNull(parseZonedIsoDateTimeToEpochMs(iso))

    private fun video(season: Int?, episode: Int?, released: String?): MetaVideo = MetaVideo(
        id = "tt0000000:$season:$episode",
        title = "Episode $episode",
        released = released,
        season = season,
        episode = episode,
    )

    private fun libraryEntry(type: SimklMediaType, simkl: Long, imdb: String): SimklLibraryEntry {
        val media = SimklMedia(
            title = "Title $simkl",
            ids = buildJsonObject {
                put("simkl", simkl)
                put("imdb", imdb)
            },
        )
        return SimklLibraryEntry(
            mediaType = type,
            status = SimklListStatus.WATCHING,
            show = media.takeIf { type != SimklMediaType.MOVIES },
            movie = media.takeIf { type == SimklMediaType.MOVIES },
        )
    }
}

/** Shape of `/anime/episodes/{id}?extended=full` for a series streamed five days before broadcast. */
private val EarlyStreamAnimeEpisodes = """
    [
      {"type":"episode","episode":1,"date":"2026-09-27T23:30:00+09:00","aired":true,"tvdb":{"season":1,"episode":1}},
      {"type":"episode","episode":2,"date":"2026-10-04T23:30:00+09:00","aired":true,"tvdb":{"season":1,"episode":2}},
      {"type":"episode","episode":2,"date":"2026-10-04T23:30:00+09:00","aired":true,"tvdb":{"season":1,"episode":2}},
      {"type":"episode","episode":3,"date":"2026-10-11T23:30:00+09:00","aired":true,"tvdb":{"season":1,"episode":3}},
      {"type":"episode","episode":4,"date":null,"aired":false,"tvdb":{"season":1,"episode":4}},
      {"type":"episode","episode":5,"date":"2026-10-25T23:30:00+09:00","aired":false,"tvdb":null},
      {"type":"special","episode":1,"date":"2026-09-20T23:30:00+09:00","aired":true,"tvdb":{"season":1,"episode":1}}
    ]
""".trimIndent()

/** Shape of `/tv/episodes/{id}?extended=full`. */
private val ShowEpisodes = """
    [
      {"type":"episode","season":1,"episode":9,"date":"2023-03-12T21:00:00-04:00","aired":true},
      {"type":"episode","season":2,"episode":1,"date":"2025-04-13T21:00:00-04:00","aired":true},
      {"type":"special","season":0,"episode":55,"date":"2025-04-23T21:00:00-04:00","aired":true},
      {"type":"episode","season":2,"episode":2,"date":"2025-04-20","aired":true}
    ]
""".trimIndent()
