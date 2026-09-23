package com.nuvio.app.features.player.desktop.autosync

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.app.features.player.playbackSession
import com.nuvio.app.features.player.setSubtitleDelay
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies an [AutoSyncTimelineRetimeResult] to the mpv-backed desktop player.
 *
 * Unlike NuvioTV/ExoPlayer (which swaps a Kotlin-owned "sidecar" cue list it renders itself),
 * desktop subtitle rendering is entirely delegated to libmpv/libass -- Kotlin never owns the cue
 * list being displayed. Two apply paths exist, both reusing plumbing the app already ships:
 *
 * - Near-unity scale (the common case: a subtitle that's simply early/late): a single constant
 *   offset covers it, applied via the existing [setSubtitleDelay] sink (same one the manual
 *   "capture a line" AutoSync tool already uses, so it's exercised, persisted-per-video code).
 * - Real scale drift (FPS mismatch), or an offset larger than `sub-delay` can hold: mpv has no
 *   per-cue timeline hook, so this regenerates the subtitle as a new .srt with the algorithm's
 *   per-cue retimed timestamps baked in, and swaps it in via `setSubtitleUri` (already wired to
 *   mpv's `sub-add ... select`, which auto-selects the new track -- see player_bridge.cpp).
 *
 * `sub-delay` is a global mpv property that survives a `sub-add` unchanged, so the regenerated-
 * file path must explicitly zero it afterwards -- otherwise a stale manual offset from before
 * would double up on top of the timestamps already baked into the new file.
 */
internal object AutoSyncApply {
    // Matches AutoSyncTimelineRetimer's own DELAY_ONLY_SCALE_TOLERANCE: within this band a
    // "scale correction" is indistinguishable from a constant offset in practice.
    private const val DELAY_ONLY_SCALE_TOLERANCE = 0.0015

    /**
     * Returns the correction that reached the player, or null when nothing was applied -- the
     * caller turns that into the on-screen result the viewer sees.
     */
    suspend fun apply(
        runtime: PlayerScreenRuntime,
        result: AutoSyncTimelineRetimeResult,
        originalCues: List<SubtitleSyncCue>,
    ): AutoSyncCorrection? {
        if (!result.confident) {
            AutoSyncDebugLog.info { "apply skipped reason=not-confident" }
            return null
        }

        val offsetMs = result.alignmentInterceptMs.roundToInt()
        if (fitsDelayOnly(result.alignmentScale, offsetMs)) {
            AutoSyncDebugLog.info { "apply delay-only offsetMs=$offsetMs" }
            runtime.setSubtitleDelay(offsetMs)
            return AutoSyncCorrection(offsetMs = offsetMs, retimedFile = false)
        }
        if (abs(result.alignmentScale - 1.0) <= DELAY_ONLY_SCALE_TOLERANCE) {
            // A pure offset, but one `sub-delay` cannot express: setSubtitleDelay coerces into
            // +/-60s (the range the manual delay UI offers), so applying it here would silently
            // land on the clamp -- a subtitle still out of sync, and no worse-case signal than a
            // correct sync. Fall through to the regenerated-file path, which bakes the shift into
            // the timestamps and has no such ceiling.
            AutoSyncDebugLog.info {
                "apply offset-out-of-delay-range offsetMs=$offsetMs " +
                    "limit=$SUBTITLE_DELAY_MIN_MS..$SUBTITLE_DELAY_MAX_MS falling back to retimed file"
            }
        }

        if (result.cues.size != originalCues.size) {
            // Defensive: the algorithm always returns one retimed cue per input target cue
            // (see AutoSyncTimelineRetimer, `target.map { ... }`). A mismatch means a version
            // skew we don't understand well enough to render text against the wrong cue.
            AutoSyncDebugLog.warn {
                "apply skipped reason=cue-count-mismatch retimed=${result.cues.size} " +
                    "original=${originalCues.size}"
            }
            return null
        }

        // Serializing ~900 cues and writing them out has no business on the UI thread either.
        val file = withContext(Dispatchers.IO) {
            writeRetimedSubtitle(runtime.playbackSession.videoId, buildRetimedSrt(result, originalCues))
        }
        if (file == null) {
            AutoSyncDebugLog.warn { "apply skipped reason=write-failed" }
            return null
        }

        AutoSyncDebugLog.info {
            "apply scale-retimed scale=${result.alignmentScale} cues=${result.cues.size} file=$file"
        }
        runtime.playerController?.setSubtitleUri(file.toString())
        runtime.setSubtitleDelay(0)
        return AutoSyncCorrection(offsetMs = offsetMs, retimedFile = true)
    }

    /**
     * Delay-only is usable when the correction is a constant offset AND that offset is one the
     * player's `sub-delay` sink can actually hold -- [setSubtitleDelay] coerces into
     * [SUBTITLE_DELAY_MIN_MS]..[SUBTITLE_DELAY_MAX_MS], so an offset outside it would be applied
     * as the clamp rather than as itself.
     */
    internal fun fitsDelayOnly(scale: Double, offsetMs: Int): Boolean =
        abs(scale - 1.0) <= DELAY_ONLY_SCALE_TOLERANCE &&
            offsetMs in SUBTITLE_DELAY_MIN_MS..SUBTITLE_DELAY_MAX_MS

    private fun buildRetimedSrt(
        result: AutoSyncTimelineRetimeResult,
        originalCues: List<SubtitleSyncCue>,
    ): String = buildString {
        result.cues.forEachIndexed { index, cue ->
            val text = originalCues[index].text
            append(index + 1)
            append('\n')
            append(formatSrtTimestamp(cue.startTimeMs))
            append(" --> ")
            append(formatSrtTimestamp(cue.endTimeMs))
            append('\n')
            append(text)
            append("\n\n")
        }
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val minutes = (clamped / 60_000L) % 60L
        val seconds = (clamped / 1_000L) % 60L
        val millis = clamped % 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    private fun writeRetimedSubtitle(videoId: String, srt: String): Path? {
        return runCatching {
            val dir = DesktopStorage.cacheDir.resolve("autosync")
            Files.createDirectories(dir)
            val safeId = videoId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            val file = dir.resolve("${safeId}_${System.currentTimeMillis()}.srt")
            Files.writeString(file, srt)
            file
        }.getOrNull()
    }
}

/** What AutoSync actually pushed into the player, for the viewer-facing message. */
internal data class AutoSyncCorrection(
    val offsetMs: Int,
    val retimedFile: Boolean,
)
