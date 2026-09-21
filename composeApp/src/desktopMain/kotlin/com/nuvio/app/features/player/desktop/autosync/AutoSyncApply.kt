package com.nuvio.app.features.player.desktop.autosync

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.autosync.AutoSyncTimelineRetimeResult
import com.nuvio.app.features.player.playbackSession
import com.nuvio.app.features.player.setSubtitleDelay
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * - Real scale drift (FPS mismatch): mpv has no per-cue timeline hook, so this regenerates the
 *   subtitle as a new .srt with the algorithm's per-cue retimed timestamps baked in, and swaps
 *   it in via `setSubtitleUri` (already wired to mpv's `sub-add ... select`, which auto-selects
 *   the new track -- see player_bridge.cpp).
 *
 * `sub-delay` is a global mpv property that survives a `sub-add` unchanged, so the regenerated-
 * file path must explicitly zero it afterwards -- otherwise a stale manual offset from before
 * would double up on top of the timestamps already baked into the new file.
 */
internal object AutoSyncApply {
    // Matches AutoSyncTimelineRetimer's own DELAY_ONLY_SCALE_TOLERANCE: within this band a
    // "scale correction" is indistinguishable from a constant offset in practice.
    private const val DELAY_ONLY_SCALE_TOLERANCE = 0.0015

    fun apply(
        runtime: PlayerScreenRuntime,
        result: AutoSyncTimelineRetimeResult,
        originalCues: List<SubtitleSyncCue>,
    ) {
        if (!result.confident) {
            AutoSyncDebugLog.info { "apply skipped reason=not-confident" }
            return
        }

        if (abs(result.alignmentScale - 1.0) <= DELAY_ONLY_SCALE_TOLERANCE) {
            val offsetMs = result.alignmentInterceptMs.roundToInt()
            AutoSyncDebugLog.info { "apply delay-only offsetMs=$offsetMs" }
            runtime.setSubtitleDelay(offsetMs)
            return
        }

        if (result.cues.size != originalCues.size) {
            // Defensive: the algorithm always returns one retimed cue per input target cue
            // (see AutoSyncTimelineRetimer, `target.map { ... }`). A mismatch means a version
            // skew we don't understand well enough to render text against the wrong cue.
            AutoSyncDebugLog.warn {
                "apply skipped reason=cue-count-mismatch retimed=${result.cues.size} " +
                    "original=${originalCues.size}"
            }
            return
        }

        val srt = buildRetimedSrt(result, originalCues)
        val file = writeRetimedSubtitle(runtime.playbackSession.videoId, srt)
        if (file == null) {
            AutoSyncDebugLog.warn { "apply skipped reason=write-failed" }
            return
        }

        AutoSyncDebugLog.info {
            "apply scale-retimed scale=${result.alignmentScale} cues=${result.cues.size} file=$file"
        }
        runtime.playerController?.setSubtitleUri(file.toString())
        runtime.setSubtitleDelay(0)
    }

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
