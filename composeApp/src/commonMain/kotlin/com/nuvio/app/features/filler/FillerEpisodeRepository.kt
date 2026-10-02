package com.nuvio.app.features.filler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episode_filler_tag
import org.jetbrains.compose.resources.stringResource

/**
 * Tags fully filler anime episodes using AnimeFillerList (animefillerlist.com).
 *
 * The tag is display-only: episode titles stored in watch progress or sent to Trakt/Simkl are never touched,
 * because those services match episodes by title. Shows the site does not list, or cannot be matched with
 * confidence, simply get no tags.
 */
object FillerEpisodeRepository {
    private class Cached<T>(val value: T, val expiresAtMs: Long)
    private class Resolved(val videos: List<MetaVideo>, val keys: Set<Pair<Int, Int>>, val expiresAtMs: Long)

    private val log = Logger.withTag("FillerEpisodeRepo")
    private val mutex = Mutex()
    private var index: Cached<List<AnimeFillerListShow>>? = null
    private val episodesBySlug = mutableMapOf<String, Cached<AnimeFillerListEpisodes?>>()
    private val resolvedByMetaId = mutableMapOf<String, Resolved>()

    private var hasLoaded = false
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _enabled.value = FillerEpisodeSettingsStorage.loadEnabled() ?: false
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        _enabled.value = enabled
        FillerEpisodeSettingsStorage.saveEnabled(enabled)
    }

    /** `(season, episode)` keys of the meta's episodes that are pure filler; empty when unknown. */
    suspend fun fillerEpisodeKeys(meta: MetaDetails): Set<Pair<Int, Int>> {
        if (meta.videos.isEmpty() || !FillerEpisodeMatcher.isAnimeCandidate(meta)) return emptySet()
        // Every visible episode row asks; answer repeat questions about the same video list from memory.
        mutex.withLock {
            resolvedByMetaId[meta.id]
                ?.takeIf { it.videos === meta.videos && it.expiresAtMs > now() }
                ?.let { return it.keys }
        }
        val keys = resolve(meta)
        mutex.withLock { resolvedByMetaId[meta.id] = Resolved(meta.videos, keys, now() + RESOLVED_TTL_MS) }
        return keys
    }

    private suspend fun resolve(meta: MetaDetails): Set<Pair<Int, Int>> {
        return try {
            val shows = FillerEpisodeMatcher.candidateShows(meta, loadIndex())
            if (shows.isEmpty()) return emptySet()
            val candidates = shows.mapNotNull { show -> loadEpisodes(show.slug)?.let { show to it } }
            val episodes = FillerEpisodeMatcher.pickShow(FillerEpisodeMatcher.releaseYear(meta), candidates)
                ?: return emptySet()
            withContext(Dispatchers.Default) {
                FillerEpisodeMatcher.fillerEpisodeKeys(meta.videos, episodes.fillerEpisodes)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Filler lookup failed for ${meta.id}: ${error.message}" }
            emptySet()
        }
    }

    private suspend fun loadIndex(): List<AnimeFillerListShow> = mutex.withLock {
        index?.takeIf { it.expiresAtMs > now() }?.let { return@withLock it.value }
        val html = fetch("$BASE_URL/shows")
        val shows = withContext(Dispatchers.Default) { AnimeFillerListParser.parseShowIndex(html) }
        index = Cached(shows, now() + CACHE_TTL_MS)
        shows
    }

    private suspend fun loadEpisodes(slug: String): AnimeFillerListEpisodes? = mutex.withLock {
        episodesBySlug[slug]?.takeIf { it.expiresAtMs > now() }?.let { return@withLock it.value }
        val episodes = try {
            val html = fetch("$BASE_URL/shows/$slug")
            withContext(Dispatchers.Default) { AnimeFillerListParser.parseEpisodes(html) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "AnimeFillerList page $slug failed: ${error.message}" }
            null
        }
        // A failed page is remembered briefly so a flaky connection does not refetch on every screen.
        val ttl = if (episodes != null) CACHE_TTL_MS else FAILURE_TTL_MS
        episodesBySlug[slug] = Cached(episodes, now() + ttl)
        episodes
    }

    private suspend fun fetch(url: String): String =
        httpGetTextWithHeaders(url, mapOf("Accept" to "text/html"))

    private fun now(): Long = LibraryClock.nowEpochMs()

    private const val BASE_URL = "https://www.animefillerlist.com"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private const val FAILURE_TTL_MS = 10L * 60L * 1000L
    private const val RESOLVED_TTL_MS = 10L * 60L * 1000L
}

/** Filler `(season, episode)` keys for [meta], or an empty set while loading or when the setting is off. */
@Composable
fun rememberFillerEpisodeKeys(meta: MetaDetails?): Set<Pair<Int, Int>> {
    remember { FillerEpisodeRepository.ensureLoaded() }
    val enabled by FillerEpisodeRepository.enabled.collectAsState()
    var keys by remember(meta?.id) { mutableStateOf(emptySet<Pair<Int, Int>>()) }
    LaunchedEffect(meta, enabled) {
        keys = if (enabled && meta != null) FillerEpisodeRepository.fillerEpisodeKeys(meta) else emptySet()
    }
    return if (enabled) keys else emptySet()
}

fun Set<Pair<Int, Int>>.isFiller(season: Int?, episode: Int?): Boolean =
    season != null && episode != null && (season to episode) in this

/** Appends the localised "[Filler]" tag when [isFiller]. */
@Composable
fun fillerTaggedTitle(title: String, isFiller: Boolean): String =
    if (isFiller) title.withFillerTag(stringResource(Res.string.episode_filler_tag)) else title

/** Non-composable form for loops: resolve [tag] once with `stringResource(Res.string.episode_filler_tag)`. */
fun String.withFillerTag(tag: String): String = if (isBlank()) tag else "$this $tag"
