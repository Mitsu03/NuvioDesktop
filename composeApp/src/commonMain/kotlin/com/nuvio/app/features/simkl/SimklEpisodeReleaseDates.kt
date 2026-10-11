package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.tracking.parseTrackingExternalIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val RELEASES_CACHE_TTL_MS = 6 * 60 * 60_000L
private const val RELEASES_FAILURE_CACHE_TTL_MS = 10 * 60_000L
private const val MAX_SIMKL_ENTRIES_PER_SERIES = 6

private const val MILLIS_PER_DAY = 86_400_000L

/** A Simkl entry whose episode list can be fetched: `/tv/episodes/{id}` or `/anime/episodes/{id}`. */
internal data class SimklEpisodeSource(val path: String, val simklId: Long)

/**
 * Meta addons date an episode by its television broadcast, so a series that streams ahead of the
 * broadcast is listed as unaired while it is already out. Simkl dates the first availability, so
 * when it has an earlier date for the same season and episode, that date replaces the addon's.
 */
internal object SimklEpisodeReleaseDates {
    private val log = Logger.withTag("SimklReleaseDates")
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<SimklEpisodeSource, CachedReleases>()
    private val searchCache = mutableMapOf<String, CachedSources>()

    suspend fun enrichMeta(meta: MetaDetails, fallbackItemId: String? = null): MetaDetails {
        val nowEpochMs = EpisodeReleaseDatePlatform.nowEpochMs()
        // Only an episode the addon still lists as unaired can be wrong in a way that matters.
        if (!meta.videos.hasUpcomingEpisode(nowEpochMs)) return meta

        val contentIds = listOfNotNull(meta.id, meta.imdbId, fallbackItemId)
            .filter(::usesTvdbStyleNumbering)
            .distinct()
        if (contentIds.isEmpty()) return meta

        return try {
            val sources = SimklSyncRepository.state.value.snapshot.episodeSourcesFor(contentIds)
                .ifEmpty { searchEpisodeSources(contentIds, nowEpochMs) }
            if (sources.isEmpty()) return meta

            val releases = mutableMapOf<Pair<Int, Int>, Long>()
            sources.forEach { source ->
                releasesFor(source, nowEpochMs).forEach { (key, epochMs) ->
                    val known = releases[key]
                    if (known == null || epochMs < known) releases[key] = epochMs
                }
            }
            meta.copy(videos = meta.videos.withEarlierSimklReleases(releases))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.w { "Failed to load Simkl release dates for ${meta.id}: ${error.message}" }
            meta
        }
    }

    private suspend fun releasesFor(source: SimklEpisodeSource, nowEpochMs: Long): Map<Pair<Int, Int>, Long> {
        cacheMutex.withLock {
            cache[source]?.takeIf { cached -> nowEpochMs < cached.expiresAtEpochMs }?.let { return it.releases }
        }
        val releases = try {
            // Public data, fetched outside SimklApi.client so a meta load never queues behind sync.
            val payload = httpGetText(
                buildSimklApiUrl(
                    path = "/${source.path}/episodes/${source.simklId}",
                    query = mapOf("extended" to "full"),
                ),
            )
            parseSimklEpisodeReleases(json, payload, isAnime = source.path == "anime")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.w { "Failed to fetch Simkl episodes ${source.path}/${source.simklId}: ${error.message}" }
            null
        }
        cacheMutex.withLock {
            cache[source] = CachedReleases(
                releases = releases.orEmpty(),
                expiresAtEpochMs = nowEpochMs +
                    if (releases == null) RELEASES_FAILURE_CACHE_TTL_MS else RELEASES_CACHE_TTL_MS,
            )
        }
        return releases.orEmpty()
    }

    /** Series the user has not added to Simkl yet. `/search/id` only answers with a user token. */
    private suspend fun searchEpisodeSources(
        contentIds: List<String>,
        nowEpochMs: Long,
    ): List<SimklEpisodeSource> {
        SimklAuthRepository.ensureLoaded()
        if (!SimklAuthRepository.isAuthenticated.value) return emptyList()
        val imdbId = contentIds.firstNotNullOfOrNull { parseTrackingExternalIds(it).imdb } ?: return emptyList()
        cacheMutex.withLock {
            searchCache[imdbId]?.takeIf { cached -> nowEpochMs < cached.expiresAtEpochMs }?.let { return it.sources }
        }
        val response = SimklApi.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/search/id",
                query = mapOf("imdb" to imdbId),
                retryPolicy = SimklRetryPolicy.NEVER,
            ),
        )
        val sources = parseSimklSearchEpisodeSources(json, response.body)
        cacheMutex.withLock {
            searchCache[imdbId] = CachedSources(sources, nowEpochMs + RELEASES_CACHE_TTL_MS)
        }
        return sources
    }

    private data class CachedReleases(
        val releases: Map<Pair<Int, Int>, Long>,
        val expiresAtEpochMs: Long,
    )

    private data class CachedSources(
        val sources: List<SimklEpisodeSource>,
        val expiresAtEpochMs: Long,
    )
}

/**
 * Simkl numbers anime episodes per entry, so only its TVDB season/episode pair lines up with the
 * numbering of IMDB, TMDB and TVDB based meta. Kitsu, MAL and similar IDs number differently.
 */
private fun usesTvdbStyleNumbering(contentId: String): Boolean {
    val ids = parseTrackingExternalIds(contentId)
    return !ids.imdb.isNullOrBlank() || ids.tmdb != null || !ids.tvdb.isNullOrBlank()
}

private fun List<MetaVideo>.hasUpcomingEpisode(nowEpochMs: Long): Boolean = any { video ->
    video.season != null && video.episode != null &&
        parseEpisodeReleaseEpochMs(video.released)?.let { it > nowEpochMs } == true
}

/** Every anime season is its own Simkl entry sharing the franchise IDs, so several can match. */
internal fun SimklSyncSnapshot.episodeSourcesFor(contentIds: List<String>): List<SimklEpisodeSource> =
    entries
        .asSequence()
        .filter { entry -> entry.mediaType != SimklMediaType.MOVIES }
        .filter { entry -> contentIds.any(entry::matchesContentId) }
        .mapNotNull { entry ->
            val simklId = entry.media?.ids?.simklIdValue()?.toLongOrNull()?.takeIf { it > 0L }
                ?: return@mapNotNull null
            SimklEpisodeSource(
                path = if (entry.mediaType == SimklMediaType.ANIME) "anime" else "tv",
                simklId = simklId,
            )
        }
        .distinct()
        .sortedByDescending(SimklEpisodeSource::simklId)
        .take(MAX_SIMKL_ENTRIES_PER_SERIES)
        .toList()

internal fun parseSimklSearchEpisodeSources(json: Json, payload: String): List<SimklEpisodeSource> {
    val results = json.parseToJsonElement(payload) as? JsonArray ?: return emptyList()
    return results
        .mapNotNull { result ->
            val entry = result as? JsonObject ?: return@mapNotNull null
            val path = when ((entry["type"] as? JsonPrimitive)?.content?.lowercase()) {
                "anime" -> "anime"
                "tv", "show" -> "tv"
                else -> return@mapNotNull null
            }
            val simklId = ((entry["ids"] as? JsonObject)?.get("simkl") as? JsonPrimitive)?.longOrNull
                ?.takeIf { it > 0L }
                ?: return@mapNotNull null
            SimklEpisodeSource(path = path, simklId = simklId)
        }
        .distinct()
        .take(MAX_SIMKL_ENTRIES_PER_SERIES)
}

/**
 * Maps season/episode to the release instant from a Simkl `/episodes` payload. Specials and
 * episodes without a zoned date are left out. Anime is keyed by its TVDB mapping only.
 */
internal fun parseSimklEpisodeReleases(
    json: Json,
    payload: String,
    isAnime: Boolean,
): Map<Pair<Int, Int>, Long> {
    val episodes = json.parseToJsonElement(payload) as? JsonArray ?: return emptyMap()
    val releases = mutableMapOf<Pair<Int, Int>, Long>()
    episodes.forEach { element ->
        val episode = element as? JsonObject ?: return@forEach
        if (episode.string("type")?.lowercase() != "episode") return@forEach
        val numbering = if (isAnime) episode["tvdb"] as? JsonObject ?: return@forEach else episode
        val season = numbering.int("season")?.takeIf { it > 0 } ?: return@forEach
        val number = numbering.int("episode")?.takeIf { it > 0 } ?: return@forEach
        val releasedEpochMs = episode.string("date")?.let(::parseZonedIsoDateTimeToEpochMs) ?: return@forEach
        val key = season to number
        val known = releases[key]
        if (known == null || releasedEpochMs < known) releases[key] = releasedEpochMs
    }
    return releases
}

internal fun List<MetaVideo>.withEarlierSimklReleases(releases: Map<Pair<Int, Int>, Long>): List<MetaVideo> {
    if (releases.isEmpty()) return this
    return map { video ->
        val season = video.season ?: return@map video
        val episode = video.episode ?: return@map video
        val simklEpochMs = releases[season to episode] ?: return@map video
        val addonEpochMs = parseEpisodeReleaseEpochMs(video.released) ?: return@map video
        if (simklEpochMs < addonEpochMs) video.copy(released = utcIsoDateTime(simklEpochMs)) else video
    }
}

/** Same shape meta addons use for `released`, e.g. `2026-10-11T14:30:00.000Z`. */
internal fun utcIsoDateTime(epochMs: Long): String {
    val epochDay = epochMs.floorDiv(MILLIS_PER_DAY)
    val millisOfDay = epochMs.mod(MILLIS_PER_DAY)

    val shiftedDay = epochDay + 719_468L
    val era = shiftedDay.floorDiv(146_097L)
    val dayOfEra = shiftedDay - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val shiftedMonth = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * shiftedMonth + 2L) / 5L + 1L
    val month = if (shiftedMonth < 10L) shiftedMonth + 3L else shiftedMonth - 9L
    val year = yearOfEra + era * 400L + if (month <= 2L) 1L else 0L

    val hour = millisOfDay / 3_600_000L
    val minute = millisOfDay / 60_000L % 60L
    val second = millisOfDay / 1_000L % 60L
    val millisecond = millisOfDay % 1_000L

    return year.toString().padStart(4, '0') + "-" +
        month.toString().padStart(2, '0') + "-" +
        day.toString().padStart(2, '0') + "T" +
        hour.toString().padStart(2, '0') + ":" +
        minute.toString().padStart(2, '0') + ":" +
        second.toString().padStart(2, '0') + "." +
        millisecond.toString().padStart(3, '0') + "Z"
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
