package com.nuvio.app.features.player.desktop.autosync

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.app.features.player.autosync.AutoSyncTimelineRetimer
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop AutoSync V2 orchestrator -- a deliberately simplified version of NuvioTV's
 * `AutomaticSubtitleSync.kt` (2393 lines: multi-candidate alternative-subtitle prefetch/ranking,
 * SDH-aware margin relaxation, cross-run caching of parsed subtitles/activity profiles, a
 * delay-only "preflight" fast path run ahead of the full matcher).
 *
 * This v1 port keeps the two things that actually determine correctness -- the reference-timeline
 * load and the retiming algorithm itself, both already ported and tested -- and reduces the
 * orchestration around them to the minimum: load the embedded reference, download+parse the one
 * subtitle the caller asked to sync, retime it, done. No alternative-candidate search, no caching
 * beyond what [EmbeddedSubtitleTimelineLoader] already does internally.
 */
internal object AutomaticSubtitleSync {
    private const val MIN_TARGET_CUES = 8

    /**
     * Owns its own dispatcher rather than trusting the caller's: the container indexing and the
     * retiming matcher are both seconds of CPU work on a full-length episode, and the natural
     * caller is a Compose `rememberCoroutineScope()`, which is the UI thread. Running there
     * freezes the player for as long as the sync takes.
     */
    suspend fun run(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        subtitle: AddonSubtitle,
    ): AutoSyncRunOutcome? = withContext(Dispatchers.Default) { runOffMainThread(sourceUrl, sourceHeaders, subtitle) }

    private suspend fun runOffMainThread(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        subtitle: AddonSubtitle,
    ): AutoSyncRunOutcome? {
        val timeline = EmbeddedSubtitleTimelineLoader.load(sourceUrl, sourceHeaders)
        if (timeline == null || timeline.tracks.isEmpty()) {
            AutoSyncDebugLog.info { "run skipped reason=no-embedded-reference url=${sourceUrl.take(80)}" }
            return null
        }

        val referenceTrack = selectReferenceTrack(timeline.tracks, subtitle.language)
        if (referenceTrack == null) {
            AutoSyncDebugLog.info { "run skipped reason=no-usable-reference-track" }
            return null
        }

        val targetCues = try {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(sourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            AutoSyncDebugLog.warn { "run failed reason=target-subtitle-fetch-failed" }
            return null
        }

        if (targetCues.size < MIN_TARGET_CUES) {
            AutoSyncDebugLog.info { "run skipped reason=target-too-short count=${targetCues.size}" }
            return null
        }

        val result = AutoSyncTimelineRetimer.retime(
            reference = referenceTrack.cues,
            target = targetCues,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
            referenceEstimatedEndStartsMs = referenceTrack.estimatedEndStartsMs,
        )

        if (result == null) {
            AutoSyncDebugLog.info { "run skipped reason=no-confident-match" }
            return null
        }

        return AutoSyncRunOutcome(result = result, originalCues = targetCues)
    }

    /**
     * NuvioTV ranks candidate reference tracks by SDH-likelihood, cue density, and dialogue-
     * completeness heuristics. Simplified here to: prefer a non-forced track whose language
     * matches the subtitle being synced, otherwise fall back to whichever track has the most
     * indexed cues (a reasonable proxy for "most complete dialogue track").
     */
    private fun selectReferenceTrack(
        tracks: List<ReferenceTrack>,
        targetLanguage: String?,
    ): ReferenceTrack? {
        val usable = tracks.filterNot { it.isForced }.ifEmpty { tracks }
        val languageMatch = targetLanguage
            ?.takeIf { it.isNotBlank() }
            ?.let { language -> usable.firstOrNull { it.language?.startsWith(language, ignoreCase = true) == true } }
        return languageMatch ?: usable.maxByOrNull { it.cues.size }
    }
}

internal data class AutoSyncRunOutcome(
    val result: AutoSyncTimelineRetimeResult,
    val originalCues: List<SubtitleSyncCue>,
)
