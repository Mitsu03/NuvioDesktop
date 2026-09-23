package com.nuvio.app.features.player.desktop.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

/**
 * An embedded subtitle track's parsed cue timeline, used as the AutoSync reference timeline.
 *
 * Ported from NuvioTV's AutoSync V2 `ReferenceTrack`. The Media3 `selectionFlags`/`roleFlags`
 * bitmasks (`androidx.media3.common.C.*`) had no desktop equivalent, so they were replaced with
 * plain booleans carrying the same information.
 */
internal data class ReferenceTrack(
    val key: String,
    val language: String?,
    val cues: List<SubtitleSyncCue>,
    val label: String? = null,
    val isForced: Boolean = false,
    val isCommentary: Boolean = false,
    val isHearingOrVisuallyImpaired: Boolean = false,
    val generation: Long = 0L,
    val estimatedEndStartsMs: Set<Long> = emptySet(),
)
