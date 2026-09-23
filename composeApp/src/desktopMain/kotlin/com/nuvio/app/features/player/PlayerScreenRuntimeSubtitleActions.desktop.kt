package com.nuvio.app.features.player

import com.nuvio.app.features.player.desktop.autosync.AutoSyncApply
import com.nuvio.app.features.player.desktop.autosync.AutoSyncCorrection
import com.nuvio.app.features.player.desktop.autosync.AutoSyncPreferences
import com.nuvio.app.features.player.desktop.autosync.AutomaticSubtitleSync
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_auto_sync_applied
import nuvio.composeapp.generated.resources.player_auto_sync_failed
import nuvio.composeapp.generated.resources.player_auto_sync_running
import org.jetbrains.compose.resources.getString

/**
 * Real AutoSync V2 trigger. Called from [PlayerScreenRuntime.refreshTracks] on every track-list
 * refresh, and from the two addon-subtitle selection handlers -- refreshTracks alone only fires
 * when the subtitle panel is *opened*, which is before the user has picked anything, so a fresh
 * selection would otherwise never be synced. Safe to call repeatedly because
 * [AutoSyncPreferences.claimStartupRun] dedupes on (player session, selected subtitle).
 *
 * Every path reports to the viewer: the run is invisible otherwise, and "it did nothing" and "it
 * could not run" look identical on screen.
 */
internal actual fun PlayerScreenRuntime.maybeRunAutomaticSubtitleSyncV2() {
    if (!AutoSyncPreferences.isEnabled()) return

    val subtitle = selectedAddonSubtitle ?: return
    val sourceUrl = playerControllerSourceUrl?.takeIf { it.isNotBlank() } ?: return

    val sessionKey = System.identityHashCode(this)
    val playbackKey = "${playbackSession.videoId}|${subtitle.id}|${subtitle.url}"
    if (!AutoSyncPreferences.claimStartupRun(sessionKey, playbackKey)) return

    scope.launch {
        showPlayerNotification(getString(Res.string.player_auto_sync_running))
        val correction = try {
            AutomaticSubtitleSync.run(
                sourceUrl = sourceUrl,
                sourceHeaders = activeSourceHeaders,
                subtitle = subtitle,
            )?.let { outcome ->
                AutoSyncApply.apply(
                    runtime = this@maybeRunAutomaticSubtitleSyncV2,
                    result = outcome.result,
                    originalCues = outcome.originalCues,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

        showPlayerNotification(
            if (correction == null) {
                getString(Res.string.player_auto_sync_failed)
            } else {
                getString(Res.string.player_auto_sync_applied, correction.offsetLabel())
            },
        )
    }
}

/** e.g. "-102.3s", "+1.5s", "0.0s" -- the shift the viewer just had applied. */
private fun AutoSyncCorrection.offsetLabel(): String {
    val seconds = offsetMs / 1000.0
    val sign = if (offsetMs < 0) "-" else "+"
    return "$sign%.1fs".format(abs(seconds))
}
