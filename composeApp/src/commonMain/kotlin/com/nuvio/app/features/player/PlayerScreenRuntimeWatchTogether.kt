package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.features.watchtogether.WatchTogetherHostBinding
import com.nuvio.app.features.watchtogether.WatchTogetherHostSample
import com.nuvio.app.features.watchtogether.WatchTogetherInvite
import com.nuvio.app.features.watchtogether.WatchTogetherOrigin
import com.nuvio.app.features.watchtogether.WatchTogetherRepository
import com.nuvio.app.features.watchtogether.WatchTogetherRole
import com.nuvio.app.features.watchtogether.WatchTogetherShareability
import com.nuvio.app.features.watchtogether.WatchTogetherStallSignal
import com.nuvio.app.features.watchtogether.WatchTogetherSyncDecision
import com.nuvio.app.features.watchtogether.WtClientCommand
import com.nuvio.app.features.watchtogether.WtHostState
import com.nuvio.app.features.watchtogether.WtPauseCause
import com.nuvio.app.features.watchtogether.WtSession
import com.nuvio.app.features.watchtogether.WtSource
import com.nuvio.app.features.watchtogether.WtSubtitle
import com.nuvio.app.features.watchtogether.classifyWatchTogetherShareability
import com.nuvio.app.features.watchtogether.nowMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The host broadcasts four times a second: well inside the guest's 120 ms deadband. */
private const val HOST_TICK_MS = 250L

/**
 * The guest corrects five times a second.
 *
 * The shared snapshot loop runs at 500 ms, which is more than three times the deadband —
 * far too coarse to hold a 120 ms band — but it also drives progress persistence, the
 * controls timeline and next-episode logic, so retuning it would have a wide blast radius.
 * This loop reads the player directly instead and leaves that one alone.
 */
private const val GUEST_TICK_MS = 200L

private const val GUEST_REPORT_MS = 1_000L

/** Long enough for mpv to settle after a seek before its position means anything again. */
private const val SEEK_SETTLE_MS = 1_600L

// ---------------------------------------------------------------------------------------
// Transport interception
// ---------------------------------------------------------------------------------------

/**
 * True while a room is open.
 *
 * Read on the hottest path in the player, so it is a plain state read and never a flow
 * collection or a suspend call. When false, every transport action keeps its existing native
 * fast path untouched.
 */
internal val PlayerScreenRuntime.watchTogetherInterceptsTransport: Boolean
    get() = WatchTogetherRepository.uiState.value.isActive

internal val PlayerScreenRuntime.watchTogetherRole: WatchTogetherRole?
    get() = WatchTogetherRepository.uiState.value.role

internal val PlayerScreenRuntime.isWatchTogetherGuest: Boolean
    get() = watchTogetherRole == WatchTogetherRole.GUEST

/**
 * Play/pause while a room is open.
 *
 * The guest only ever *asks*: it sends the intent and waits for the host's next state frame
 * to commit it. One serialization point means the room can never split-brain.
 */
internal fun PlayerScreenRuntime.watchTogetherTogglePlayback() {
    val wantsToPlay = !playbackSnapshot.isPlaying
    controlsVisible = true

    if (isWatchTogetherGuest) {
        val position = playbackSnapshot.positionMs
        WatchTogetherRepository.sendIntent(
            if (wantsToPlay) {
                WtClientCommand.IntentPlay(position)
            } else {
                WtClientCommand.IntentPause(position)
            },
        )
        return
    }

    // The host is the authority, so it applies locally and the broadcast follows.
    watchTogetherPauseCause = if (wantsToPlay) WtPauseCause.NONE else WtPauseCause.USER
    togglePlayback()
}

internal fun PlayerScreenRuntime.watchTogetherSeekBy(offsetMs: Long) {
    val target = (playbackSnapshot.positionMs + offsetMs).coerceAtLeast(0L)
    watchTogetherSeekTo(target)
}

internal fun PlayerScreenRuntime.watchTogetherSeekTo(positionMs: Long) {
    controlsVisible = true
    if (isWatchTogetherGuest) {
        WatchTogetherRepository.sendIntent(WtClientCommand.IntentSeek(positionMs))
        return
    }
    playerController?.seekTo(positionMs)
    scheduleProgressSyncAfterSeek()
}

// ---------------------------------------------------------------------------------------
// Host side
// ---------------------------------------------------------------------------------------

/**
 * The fetchable URL for the host's current source.
 *
 * `p2pResolvedSourceUrl` first: for a torrent the active URL is a `torrent://` sentinel that
 * is not HTTP at all. Re-read on every request rather than captured, because the debrid
 * credential-refresh path swaps the URL mid-session.
 */
internal fun PlayerScreenRuntime.watchTogetherOrigin(): WatchTogetherOrigin? {
    val url = p2pResolvedSourceUrl
        ?: activeSourceUrl.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        ?: return null

    return WatchTogetherOrigin(
        url = url,
        headers = activeSourceHeaders,
        responseHeaders = activeSourceResponseHeaders,
    )
}

/**
 * Describes the current source for a guest, or null when it cannot be shared.
 *
 * Built from the runtime's `active*` fields rather than the launch arguments, because
 * `switchToSource` / `switchToEpisodeStream` replace the stream mid-session.
 */
internal fun PlayerScreenRuntime.watchTogetherSession(streamToken: String): WtSession? {
    val shareability = classifyWatchTogetherShareability(
        sourceUrl = activeSourceUrl,
        streamType = activeStreamType,
        responseHeaders = activeSourceResponseHeaders,
        torrentInfoHash = activeTorrentInfoHash,
        torrentFileIdx = activeTorrentFileIdx,
        torrentFilename = activeTorrentFilename,
        torrentTrackers = activeTorrentTrackers,
    )

    val source = when (shareability) {
        is WatchTogetherShareability.Torrent -> WtSource.Torrent(
            infoHash = shareability.infoHash,
            fileIdx = shareability.fileIdx,
            filename = shareability.filename,
            trackers = shareability.trackers,
            // The host's own TorrServer output is plain HTTP with Range, so it proxies
            // byte-for-byte when the guest has P2P turned off.
            proxyFallbackToken = streamToken,
        )

        WatchTogetherShareability.Direct -> WtSource.Proxy(streamToken)

        // v1 cannot re-serve an adaptive manifest: its segment URLs point at the origin.
        is WatchTogetherShareability.AdaptiveManifest -> return null
        WatchTogetherShareability.Unshareable -> return null
    }

    val snapshot = playerController?.probeSnapshot() ?: playbackSnapshot
    return WtSession(
        sessionId = activeSourceIdentityKey ?: activeSourceUrl,
        title = title,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        contentType = contentType,
        videoId = activeVideoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        streamTitle = activeStreamTitle,
        streamSubtitle = activeStreamSubtitle,
        providerName = activeProviderName,
        streamType = activeStreamType,
        logo = logo,
        poster = poster,
        background = background,
        durationMs = snapshot.durationMs,
        externalSubtitles = externalSubtitles.map {
            WtSubtitle(url = it.url, language = it.language, name = it.name)
        },
        source = source,
    )
}

internal fun PlayerScreenRuntime.watchTogetherHostState(): WtHostState {
    val snapshot = playerController?.probeSnapshot() ?: playbackSnapshot
    return WtHostState(
        // Stamped at the sample, not at transmit: the guest projects forward from this, so a
        // transmit-time stamp would bake the network delay into every position it computes.
        sampleTimeMs = nowMs(),
        positionMs = snapshot.positionMs,
        durationMs = snapshot.durationMs,
        isPlaying = snapshot.isPlaying,
        isLoading = snapshot.isLoading,
        playbackSpeed = snapshot.playbackSpeed,
        pauseCause = watchTogetherPauseCause,
        sessionId = activeSourceIdentityKey ?: activeSourceUrl,
        sequence = ++watchTogetherSequence,
    )
}

/** Opens a room around whatever the host is watching right now. */
internal fun PlayerScreenRuntime.openWatchTogetherRoom(): WatchTogetherInvite? =
    WatchTogetherRepository.openRoom(
        WatchTogetherHostBinding(
            origin = { watchTogetherOrigin() },
            session = { token -> watchTogetherSession(token) },
            hostState = { watchTogetherHostState() },
            // Arrives on an HTTP worker thread; hop to the runtime scope before
            // touching player state.
            onGuestIntent = { command -> scope.launch { applyGuestIntent(command) } },
            onParticipantsChanged = { },
        ),
    )

/**
 * A guest asked for something. The host decides, and the decision reaches everyone as the
 * next state frame.
 */
private fun PlayerScreenRuntime.applyGuestIntent(command: WtClientCommand) {
    when (command) {
        is WtClientCommand.IntentPlay -> {
            watchTogetherPauseCause = WtPauseCause.NONE
            shouldPlay = true
            playerController?.play()
        }

        is WtClientCommand.IntentPause -> {
            watchTogetherPauseCause = WtPauseCause.USER
            shouldPlay = false
            playerController?.pause()
        }

        is WtClientCommand.IntentSeek -> {
            playerController?.seekTo(command.toPositionMs)
            scheduleProgressSyncAfterSeek()
        }

        is WtClientCommand.Stall -> {
            // Wait for them rather than playing on alone — but only if nobody had
            // deliberately paused, or resuming later would override that.
            if (watchTogetherPauseCause == WtPauseCause.NONE && playbackSnapshot.isPlaying) {
                watchTogetherPauseCause = WtPauseCause.STALLED_PEER
                shouldPlay = false
                playerController?.pause()
            }
        }

        is WtClientCommand.StallCleared -> {
            val stillStalled = WatchTogetherRepository.uiState.value.participants.any { it.isStalled }
            if (watchTogetherPauseCause == WtPauseCause.STALLED_PEER && !stillStalled) {
                watchTogetherPauseCause = WtPauseCause.NONE
                // Give up on automatic recovery once it is clearly not working: an endless
                // pause/resume cycle is worse than saying the link cannot sustain the stream.
                if (!WatchTogetherRepository.thrashGuard.onAutoResume(nowMs())) {
                    shouldPlay = true
                    playerController?.play()
                }
            }
        }

        else -> Unit
    }
}

// ---------------------------------------------------------------------------------------
// Effects
// ---------------------------------------------------------------------------------------

@Composable
internal fun PlayerScreenRuntime.BindWatchTogetherEffects() {
    // Collected, not read: uiState is a StateFlow rather than Compose state, so a
    // `.value` read would leave these effects keyed on a value that never changes.
    val room by WatchTogetherRepository.uiState.collectAsState()

    // Host: broadcast, and re-publish whenever the source underneath changes.
    LaunchedEffect(room.isHost) {
        if (!room.isHost) return@LaunchedEffect
        while (isActive) {
            WatchTogetherRepository.publishHostState(watchTogetherHostState())
            delay(HOST_TICK_MS)
        }
    }

    LaunchedEffect(room.isHost, activeSourceIdentityKey) {
        if (!room.isHost) return@LaunchedEffect
        WatchTogetherRepository.rotateSource()
    }

    // Guest: mirror the host, and stay level with it.
    LaunchedEffect(room.isGuest) {
        if (!room.isGuest) return@LaunchedEffect
        WatchTogetherRepository.drift.reset()

        var settleAtMs: Long? = null
        var lastReportMs = 0L
        // Joining is always behind by the handshake plus however long mpv took to open.
        // That gap is usually under the seek threshold, so nothing would ever close it.
        var caughtUp = false

        while (isActive) {
            if (!caughtUp && catchUpToHostOnJoin()) caughtUp = true
            runGuestSyncTick(
                settleAtMs = settleAtMs,
                onSettled = { settleAtMs = null },
                onSeekIssued = { settleAtMs = nowMs() + SEEK_SETTLE_MS },
                shouldReport = nowMs() - lastReportMs >= GUEST_REPORT_MS,
                onReported = { lastReportMs = nowMs() },
            )
            delay(GUEST_TICK_MS)
        }
    }
}

private fun PlayerScreenRuntime.runGuestSyncTick(
    settleAtMs: Long?,
    onSettled: () -> Unit,
    onSeekIssued: () -> Unit,
    shouldReport: Boolean,
    onReported: () -> Unit,
) {
    val controller = playerController ?: return
    val remote = WatchTogetherRepository.remoteHostState.value ?: return
    val snapshot = controller.probeSnapshot() ?: playbackSnapshot
    val now = nowMs()
    val bufferedAheadMs = (snapshot.bufferedPositionMs - snapshot.positionMs).coerceAtLeast(0L)

    if (settleAtMs != null && now >= settleAtMs) {
        // Learn how far the seek actually landed from where it was aimed, so the controller
        // stops asking for corrections seeking cannot deliver.
        WatchTogetherRepository.drift.onSeekSettled(snapshot.positionMs)
        onSettled()
    }

    WatchTogetherRepository.stallDetector.update(now, snapshot.isLoading, bufferedAheadMs)?.let { signal ->
        WatchTogetherRepository.sendIntent(
            when (signal) {
                WatchTogetherStallSignal.STALL -> WtClientCommand.Stall
                WatchTogetherStallSignal.CLEARED -> WtClientCommand.StallCleared(bufferedAheadMs)
            },
        )
    }

    if (shouldReport) {
        WatchTogetherRepository.sendIntent(
            WtClientCommand.GuestState(
                sampleTimeMs = now,
                positionMs = snapshot.positionMs,
                isLoading = snapshot.isLoading,
                bufferedAheadMs = bufferedAheadMs,
                appliedSpeed = snapshot.playbackSpeed,
            ),
        )
        onReported()
    }

    // Mirror the transport first: a paused host means stop, not "drift towards".
    if (remote.isPlaying && !snapshot.isPlaying && !snapshot.isLoading && !snapshot.isEnded) {
        shouldPlay = true
        controller.play()
    } else if (!remote.isPlaying && snapshot.isPlaying) {
        shouldPlay = false
        controller.pause()
    }

    val offsetMs = WatchTogetherRepository.clockOffset.offsetMs ?: return
    val decision = WatchTogetherRepository.drift.decide(
        nowMs = now,
        host = WatchTogetherHostSample(
            sampleTimeMs = remote.sampleTimeMs,
            positionMs = remote.positionMs,
            isPlaying = remote.isPlaying,
            isLoading = remote.isLoading,
        ),
        clockOffsetMs = offsetMs,
        localPositionMs = snapshot.positionMs,
        guestIsLoading = snapshot.isLoading,
        currentSpeed = snapshot.playbackSpeed,
    )

    when (decision) {
        WatchTogetherSyncDecision.Hold -> Unit
        is WatchTogetherSyncDecision.Nudge -> controller.setPlaybackSpeed(decision.speed)
        is WatchTogetherSyncDecision.HardSeek -> {
            WatchTogetherRepository.drift.onSeekIssued(now, decision.positionMs)
            controller.seekTo(decision.positionMs)
            onSeekIssued()
        }
    }
}

/**
 * The one unconditional seek: land where the host actually is, once, as soon as this
 * player is ready. Everything after this is drift correction.
 *
 * @return true once it has run, so it never runs twice.
 */
private fun PlayerScreenRuntime.catchUpToHostOnJoin(): Boolean {
    val controller = playerController ?: return false
    val remote = WatchTogetherRepository.remoteHostState.value ?: return false
    val offsetMs = WatchTogetherRepository.clockOffset.offsetMs ?: return false
    val snapshot = controller.probeSnapshot() ?: playbackSnapshot
    if (snapshot.isLoading || snapshot.durationMs <= 0L) return false

    val now = nowMs()
    val elapsed = (now - (remote.sampleTimeMs - offsetMs)).coerceIn(0L, 5_000L)
    val target = remote.positionMs + if (remote.isPlaying) elapsed else 0L

    WatchTogetherRepository.drift.onSeekIssued(now, target)
    controller.seekTo(target)
    return true
}
