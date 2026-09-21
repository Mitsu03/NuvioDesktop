package com.nuvio.app.features.watchtogether

/**
 * Whether the host's active stream can be shared with a guest, and by which route.
 *
 * The proxy re-serves a single origin URL byte-for-byte, so it only works for a progressive
 * file. Adaptive manifests name their segments relative to the origin, which a URL proxy
 * cannot rewrite; `isSupportedDownloadUrl` in DownloadsRepository draws the same line for
 * downloads.
 */
sealed interface WatchTogetherShareability {
    /** The guest resolves the same torrent locally. No host upload at all — always preferred. */
    data class Torrent(
        val infoHash: String,
        val fileIdx: Int?,
        val filename: String?,
        val trackers: List<String>,
    ) : WatchTogetherShareability

    /** A progressive file the host can proxy. */
    data object Direct : WatchTogetherShareability

    /** HLS/DASH/SmoothStreaming — refused in v1, the room degrades to control-only. */
    data class AdaptiveManifest(val kind: String) : WatchTogetherShareability

    /** Not an http(s) URL at all, or nothing recognisable. */
    data object Unshareable : WatchTogetherShareability
}

/**
 * Classifies the host's active source.
 *
 * Order matters: the addon-declared `streamType` is the most reliable signal, then the
 * response `Content-Type` the origin actually sent, then the URL shape. This mirrors
 * `inferPlaybackMimeType` in PlaybackMediaItems.android.kt so all targets agree.
 *
 * `notWebReady` / `proxyHeaders` deliberately do **not** block sharing: they mean the source
 * needs custom request headers, which the proxy supplies upstream on the guest's behalf. A
 * `notWebReady` source the guest could never fetch directly works fine through the proxy.
 */
fun classifyWatchTogetherShareability(
    sourceUrl: String,
    streamType: String? = null,
    responseHeaders: Map<String, String> = emptyMap(),
    torrentInfoHash: String? = null,
    torrentFileIdx: Int? = null,
    torrentFilename: String? = null,
    torrentTrackers: List<String> = emptyList(),
): WatchTogetherShareability {
    val infoHash = torrentInfoHash?.trim()?.takeIf { it.isNotBlank() }
    if (infoHash != null) {
        return WatchTogetherShareability.Torrent(
            infoHash = infoHash.lowercase(),
            fileIdx = torrentFileIdx,
            filename = torrentFilename,
            trackers = torrentTrackers,
        )
    }

    adaptiveKindFromStreamType(streamType)?.let { return WatchTogetherShareability.AdaptiveManifest(it) }
    adaptiveKindFromContentType(responseHeaders)?.let { return WatchTogetherShareability.AdaptiveManifest(it) }

    val url = sourceUrl.trim()
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        return WatchTogetherShareability.Unshareable
    }
    adaptiveKindFromUrl(url)?.let { return WatchTogetherShareability.AdaptiveManifest(it) }

    return WatchTogetherShareability.Direct
}

private fun adaptiveKindFromStreamType(streamType: String?): String? =
    when (streamType?.trim()?.lowercase()?.takeIf { it.isNotBlank() }) {
        "hls", "m3u8" -> "HLS"
        "dash", "mpd" -> "DASH"
        "smoothstreaming", "ss" -> "SmoothStreaming"
        else -> null
    }

private fun adaptiveKindFromContentType(headers: Map<String, String>): String? {
    val contentType = headers.entries
        .firstOrNull { (key, _) -> key.equals("Content-Type", ignoreCase = true) }
        ?.value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?: return null
    return when (contentType) {
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        -> "HLS"

        "application/dash+xml" -> "DASH"
        "application/vnd.ms-sstr+xml" -> "SmoothStreaming"
        else -> null
    }
}

/**
 * The extension can sit before a query (`/master.m3u8?token=…`) or only inside one
 * (`/play?format=m3u8`), so both the path and the query are inspected.
 */
private fun adaptiveKindFromUrl(url: String): String? {
    val withoutFragment = url.substringBefore('#')
    val path = withoutFragment.substringBefore('?').lowercase()
    val query = withoutFragment.substringAfter('?', missingDelimiterValue = "").lowercase()

    if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return "HLS"
    if (path.endsWith(".mpd")) return "DASH"
    if (path.endsWith(".ism") || path.endsWith("/manifest")) return "SmoothStreaming"

    if (query.isNotBlank()) {
        val tokens = query.split('&', '=', ',', ';', '/').map(String::trim)
        if (tokens.any { it == "m3u8" || it == "hls" }) return "HLS"
        if (tokens.any { it == "mpd" || it == "dash" }) return "DASH"
    }
    return null
}
