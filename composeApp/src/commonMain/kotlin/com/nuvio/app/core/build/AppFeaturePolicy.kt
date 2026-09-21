package com.nuvio.app.core.build

enum class TrailerPlaybackMode {
    IN_APP,
    EXTERNAL,
}

expect object AppFeaturePolicy {
    val pluginsEnabled: Boolean
    val downloadsEnabled: Boolean
    val notificationsEnabled: Boolean
    val supportersContributorsPageEnabled: Boolean
    val donationActionsEnabled: Boolean
    val donationProgressEnabled: Boolean
    val accountDeletionEnabled: Boolean
    val personalMediaAddonCopyEnabled: Boolean
    val p2pEnabled: Boolean

    /** Watch Together hosts an HTTP server on the LAN; desktop-only in v1. */
    val watchTogetherEnabled: Boolean
    val externalPlayerSupported: Boolean
    val trailerPlaybackMode: TrailerPlaybackMode
    val heroTrailerPlaybackSupported: Boolean
    val inAppUpdaterEnabled: Boolean
    val imdbRatingLogoEnabled: Boolean
    val mediaPlaybackForegroundServiceEnabled: Boolean
    val customServerConnectionsEnabled: Boolean
}
