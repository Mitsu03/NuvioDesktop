package com.nuvio.app.features.simkl

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry

internal fun SimklSyncSnapshot.reconcileWatchedPlayback(): SimklSyncSnapshot {
    if (playback.isEmpty()) return this
    val watchedItems = toSimklWatchedProjection().items
    val retainedPlayback = playback.filterNot { session ->
        session.toWatchProgressEntry(entries)?.let { progress ->
            entries.any { entry -> entry.hidesPlayback(progress) } ||
                watchedItems.any { watched -> watched.supersedes(progress) }
        } == true
    }
    return if (retainedPlayback.size == playback.size) this else copy(playback = retainedPlayback)
}

internal fun SimklSyncSnapshot.isHiddenFromContinueWatching(contentId: String): Boolean =
    entries.any { entry ->
        entry.status.hidesContinueWatching() &&
            entry.matchesContent(contentId = contentId, trackingProviderItemId = null)
    }

internal fun SimklSyncSnapshot.hiddenFromContinueWatchingContentIds(): Set<String> =
    entries.asSequence()
        .filter { entry -> entry.status.hidesContinueWatching() }
        .mapNotNull { entry -> entry.media?.canonicalContentId() }
        .toSet()

internal fun SimklListStatus?.hidesContinueWatching(): Boolean =
    this == SimklListStatus.ON_HOLD || this == SimklListStatus.DROPPED

/**
 * True when any Simkl entry behind [contentId] is still on the Watching list.
 *
 * Simkl splits a franchise into one entry per season, cour or arc, while a meta addon serves the
 * whole run under a single ID. Finishing one entry therefore leaves plenty of episodes ahead in the
 * addon's list, and Next Up - which only asks "is there an episode after the furthest one watched?"
 * - keeps offering them. Grouping by content ID means a franchise counts as watching while any of
 * its entries is, which is what the viewer sees on Simkl.
 */
internal fun SimklSyncSnapshot.isTrackedAsWatching(contentId: String): Boolean =
    entries.any { entry ->
        entry.status == SimklListStatus.WATCHING &&
            entry.matchesContent(contentId = contentId, trackingProviderItemId = null)
    }

private fun WatchedItem.supersedes(progress: WatchProgressEntry): Boolean {
    if (!type.equals(progress.contentType, ignoreCase = true)) return false
    if (season != progress.seasonNumber || episode != progress.episodeNumber) return false
    val sameProviderItem = trackingProviderItemId
        ?.takeIf(String::isNotBlank)
        ?.equals(progress.trackingProviderItemId, ignoreCase = true) == true
    val sameContent = id.equals(progress.parentMetaId, ignoreCase = true)
    return (sameProviderItem || sameContent) && markedAtEpochMs >= progress.lastUpdatedEpochMs
}

private fun SimklLibraryEntry.hidesPlayback(progress: WatchProgressEntry): Boolean {
    if (
        !matchesContent(
            contentId = progress.parentMetaId,
            trackingProviderItemId = progress.trackingProviderItemId,
        )
    ) {
        return false
    }
    return when {
        status.hidesContinueWatching() -> true
        status == SimklListStatus.COMPLETED ->
            parseSimklUtcEpochMs(lastWatchedAt)?.let { completedAt ->
                completedAt >= progress.lastUpdatedEpochMs
            } == true
        else -> false
    }
}

private fun SimklLibraryEntry.matchesContent(
    contentId: String,
    trackingProviderItemId: String?,
): Boolean {
    val providerItemId = media?.simklTrackingProviderItemId()
    val sameProviderItem = providerItemId != null &&
        providerItemId.equals(trackingProviderItemId, ignoreCase = true)
    return sameProviderItem || matchesContentId(contentId)
}
