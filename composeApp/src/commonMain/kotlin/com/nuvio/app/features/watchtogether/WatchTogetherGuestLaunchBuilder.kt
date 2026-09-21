package com.nuvio.app.features.watchtogether

import com.nuvio.app.features.p2p.buildP2pSentinelUrl
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.streams.StreamSubtitle

/**
 * Rebuilds a playable launch on the guest's machine from the host's descriptor.
 *
 * The host's own `launchId` is meaningless here — `PlayerLaunchStore` is a process-local map
 * — so nothing is carried across but the descriptor, and the guest constructs its own.
 *
 * @param p2pEnabled whether this machine may resolve torrents itself. When it can, a torrent
 *   costs the host no upload at all; when it cannot, the host proxies its own resolved URL.
 */
internal fun WatchTogetherGuestLaunch.toPlayerLaunch(
    profileId: Int,
    p2pEnabled: Boolean,
): PlayerLaunch? {
    val source = session.source

    val sourceUrl: String
    var infoHash: String? = null
    var fileIdx: Int? = null
    var filename: String? = null
    var trackers: List<String> = emptyList()

    when (source) {
        is WtSource.Proxy -> {
            sourceUrl = WatchTogetherGuestEngine.streamUrl(invite, source.streamToken)
        }

        is WtSource.Torrent -> {
            if (p2pEnabled) {
                // The good path: this machine fetches the same torrent, so the host uploads
                // nothing. The sentinel is replaced by the local engine's URL on open.
                infoHash = source.infoHash
                fileIdx = source.fileIdx
                filename = source.filename
                trackers = source.trackers
                sourceUrl = buildP2pSentinelUrl(source.infoHash, source.fileIdx)
            } else {
                // Falls back to the proxy rather than refusing: the host's TorrServer output
                // is ordinary HTTP with Range, so it re-serves byte-for-byte.
                val token = source.proxyFallbackToken ?: return null
                sourceUrl = WatchTogetherGuestEngine.streamUrl(invite, token)
            }
        }
    }

    return PlayerLaunch(
        profileId = profileId,
        title = session.title,
        sourceUrl = sourceUrl,
        // Empty: the room token travels in the path, so the guest's player needs no headers
        // of its own, and the host supplies the origin's upstream.
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        externalSubtitles = session.externalSubtitles.map {
            StreamSubtitle(url = it.url, language = it.language, name = it.name)
        },
        streamType = session.streamType,
        logo = session.logo,
        poster = session.poster,
        background = session.background,
        seasonNumber = session.seasonNumber,
        episodeNumber = session.episodeNumber,
        episodeTitle = session.episodeTitle,
        episodeThumbnail = session.episodeThumbnail,
        streamTitle = session.streamTitle,
        streamSubtitle = session.streamSubtitle,
        providerName = session.providerName,
        // Deliberately null: the guest may not have the host's addon installed, and this is
        // what any "re-resolve the stream" path would key off.
        providerAddonId = null,
        contentType = session.contentType,
        videoId = session.videoId,
        parentMetaId = session.parentMetaId,
        parentMetaType = session.parentMetaType,
        torrentInfoHash = infoHash,
        torrentFileIdx = fileIdx,
        torrentFilename = filename,
        torrentTrackers = trackers,
        // Start where the host is, not where this guest last left off.
        initialPositionMs = hostState.positionMs.coerceAtLeast(0L),
        initialProgressFraction = null,
    )
}
