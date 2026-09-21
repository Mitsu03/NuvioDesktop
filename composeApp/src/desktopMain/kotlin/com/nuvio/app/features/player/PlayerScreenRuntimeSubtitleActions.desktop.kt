package com.nuvio.app.features.player

import com.nuvio.app.features.player.desktop.autosync.AutoSyncApply
import com.nuvio.app.features.player.desktop.autosync.AutoSyncPreferences
import com.nuvio.app.features.player.desktop.autosync.AutomaticSubtitleSync
import kotlinx.coroutines.launch

/**
 * Real AutoSync V2 trigger. Called from [PlayerScreenRuntime.refreshTracks] on every track-list
 * refresh; safe to call repeatedly because [AutoSyncPreferences.claimStartupRun] dedupes on
 * (player session, selected subtitle) so it only actually runs once per selection.
 */
internal actual fun PlayerScreenRuntime.maybeRunAutomaticSubtitleSyncV2() {
    if (!AutoSyncPreferences.isEnabled()) return

    val subtitle = selectedAddonSubtitle ?: return
    val sourceUrl = playerControllerSourceUrl?.takeIf { it.isNotBlank() } ?: return

    val sessionKey = System.identityHashCode(this)
    val playbackKey = "${playbackSession.videoId}|${subtitle.id}|${subtitle.url}"
    if (!AutoSyncPreferences.claimStartupRun(sessionKey, playbackKey)) return

    scope.launch {
        val outcome = AutomaticSubtitleSync.run(
            sourceUrl = sourceUrl,
            sourceHeaders = activeSourceHeaders,
            subtitle = subtitle,
        ) ?: return@launch
        AutoSyncApply.apply(
            runtime = this@maybeRunAutomaticSubtitleSyncV2,
            result = outcome.result,
            originalCues = outcome.originalCues,
        )
    }
}
