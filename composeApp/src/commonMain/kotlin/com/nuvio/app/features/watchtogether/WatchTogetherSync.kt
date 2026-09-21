package com.nuvio.app.features.watchtogether

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The pure sync core: clock-offset estimation, drift correction and stall debouncing.
 *
 * Everything here is deterministic given its inputs — callers pass `nowMs` in rather than
 * reading a clock — so the whole thing is unit-testable without a player or a network.
 *
 * All times are **monotonic** milliseconds (`System.nanoTime() / 1_000_000` on the JVM),
 * never wall clock: the two machines' wall clocks routinely differ by seconds, which would
 * make the guest hard-seek on every tick forever.
 */
data class WatchTogetherSyncConfig(
    /** Below this the correction is worse than the error — mpv's own A/V jitter lives here. */
    val deadbandMs: Long = 120,
    /**
     * The floor for seeking rather than nudging. The effective threshold is
     * `max(this, 2 × seek granularity)`, so on a platform that seeks by keyframe it starts
     * out at twice [initialSeekGranularityMs] and only falls to this once seeks are measured
     * to be accurate.
     */
    val hardSeekThresholdMs: Long = 2_000,
    /** `speed = 1 + drift / gain`, so a 400 ms drift asks for +5 %. */
    val nudgeGainMs: Long = 8_000,
    val maxNudge: Float = 0.05f,
    /**
     * Used when a hard seek is forbidden because it could not land accurately enough.
     * Closing a 4 s residual at 5 % takes 80 s; at 10 % it takes 40 s.
     */
    val maxNudgeWide: Float = 0.10f,
    /** Once inside this band, drop back to 1.0 rather than hunting around zero. */
    val releaseNudgeMs: Long = 60,
    /** After any seek, mpv needs to settle before its position means anything. */
    val seekSettleMs: Long = 1_500,
    /** A nudge that never converges is a symptom of something else; escalate. */
    val maxNudgeDurationMs: Long = 30_000,
    /**
     * Windows and macOS seek with `absolute+keyframes` and `hr-seek=no`, so a seek lands on
     * the nearest keyframe — up to a whole GOP away. Linux seeks exactly and drives this
     * estimate down to ~0 on its own, so there is no platform branch anywhere.
     */
    val initialSeekGranularityMs: Long = 4_000,
    /** A host sample older than this is stale; don't project a position from it. */
    val maxProjectionMs: Long = 5_000,
)

sealed interface WatchTogetherSyncDecision {
    /** In band, or not safe to act. Leave playback alone. */
    data object Hold : WatchTogetherSyncDecision

    /** Set mpv's `speed`. [speed] of exactly 1.0 means "stop nudging". */
    data class Nudge(val speed: Float) : WatchTogetherSyncDecision

    data class HardSeek(val positionMs: Long) : WatchTogetherSyncDecision
}

/** The host's last broadcast playback state, as the guest received it. */
data class WatchTogetherHostSample(
    /** Host monotonic ms **at sample time**, not at transmit time. */
    val sampleTimeMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isLoading: Boolean,
)

/**
 * Estimates `hostMonotonic - guestMonotonic` from round-trip probes.
 *
 * Keeps the **minimum-RTT** sample of the last [window]: the shortest round trip is the one
 * least contaminated by queueing, so its midpoint is the best estimate of the host's clock.
 */
class WatchTogetherClockOffsetEstimator(private val window: Int = 8) {
    private data class Sample(val offsetMs: Long, val rttMs: Long)

    private val samples = ArrayDeque<Sample>()

    /**
     * @param sentAtMs guest monotonic ms when the probe was sent
     * @param hostTimeMs host monotonic ms stamped by the host on receipt
     * @param receivedAtMs guest monotonic ms when the reply arrived
     */
    fun record(sentAtMs: Long, hostTimeMs: Long, receivedAtMs: Long) {
        val rtt = receivedAtMs - sentAtMs
        if (rtt < 0) return
        val midpoint = sentAtMs + rtt / 2
        samples.addLast(Sample(offsetMs = hostTimeMs - midpoint, rttMs = rtt))
        while (samples.size > window) samples.removeFirst()
    }

    /** `hostMonotonic - guestMonotonic`, or null until a probe has landed. */
    val offsetMs: Long?
        get() = samples.minByOrNull { it.rttMs }?.offsetMs

    val rttMs: Long?
        get() = samples.minByOrNull { it.rttMs }?.rttMs

    val hasEstimate: Boolean get() = samples.isNotEmpty()

    fun reset() = samples.clear()
}

/**
 * Learns how far off a seek actually lands, so the drift controller never asks for a
 * correction that seeking cannot deliver.
 *
 * Without this, a 4 s keyframe residual re-trips the 2 s hard-seek threshold immediately and
 * the guest seeks forever, on loop.
 */
class WatchTogetherSeekGranularityEstimator(private var assumedMs: Long) {
    private var observedMs: Long? = null

    /**
     * The assumed value stands only until a real seek has been measured — otherwise a
     * platform that seeks exactly (Linux) could never converge downwards, because the
     * running max would be pinned by an assumption it never made.
     */
    val granularityMs: Long get() = observedMs ?: assumedMs

    fun observe(requestedMs: Long, settledMs: Long) {
        val residual = abs(settledMs - requestedMs)
        // A running max over observations: one accurate seek does not prove the next one will be.
        observedMs = observedMs?.let { max(it, residual) } ?: residual
    }

    fun reset(initialMs: Long) {
        assumedMs = initialMs
        observedMs = null
    }
}

/**
 * Decides, tick by tick, what the guest should do to stay level with the host.
 *
 * The host never runs this — it is the clock, and adjusting its own speed would mean chasing
 * itself.
 */
class WatchTogetherDriftController(
    private val config: WatchTogetherSyncConfig = WatchTogetherSyncConfig(),
) {
    private val seekGranularity = WatchTogetherSeekGranularityEstimator(config.initialSeekGranularityMs)

    private var suppressUntilMs: Long = Long.MIN_VALUE
    private var nudgeSinceMs: Long? = null
    private var pendingSeekTargetMs: Long? = null

    val seekGranularityMs: Long get() = seekGranularity.granularityMs

    /**
     * A seek was just issued locally, whether mirrored from the host or to catch up.
     * Correction pauses until mpv settles; a mirrored seek then re-baselines rather than
     * being corrected, because both players snap to the same keyframe of the same stream.
     */
    fun onSeekIssued(nowMs: Long, targetMs: Long) {
        suppressUntilMs = nowMs + config.seekSettleMs
        pendingSeekTargetMs = targetMs
        nudgeSinceMs = null
    }

    /** Called once mpv has settled after [onSeekIssued], with the position it actually reached. */
    fun onSeekSettled(settledPositionMs: Long) {
        pendingSeekTargetMs?.let { seekGranularity.observe(it, settledPositionMs) }
        pendingSeekTargetMs = null
    }

    /** Room closed, or the source switched — none of the learned state carries over. */
    fun reset() {
        suppressUntilMs = Long.MIN_VALUE
        nudgeSinceMs = null
        pendingSeekTargetMs = null
        seekGranularity.reset(config.initialSeekGranularityMs)
    }

    /**
     * @param clockOffsetMs `hostMonotonic - guestMonotonic`, from [WatchTogetherClockOffsetEstimator]
     * @param currentSpeed mpv's current `speed`, so an already-neutral player isn't told to be neutral
     */
    fun decide(
        nowMs: Long,
        host: WatchTogetherHostSample,
        clockOffsetMs: Long,
        localPositionMs: Long,
        guestIsLoading: Boolean,
        currentSpeed: Float,
    ): WatchTogetherSyncDecision {
        // While the player settles after a seek, its position is meaningless.
        if (nowMs < suppressUntilMs) return neutralise(currentSpeed)

        // A paused or buffering host is handled by mirroring its transport state, not by drift.
        if (!host.isPlaying || host.isLoading) return neutralise(currentSpeed)

        // Correcting against a stalled local player just banks error it will never spend.
        if (guestIsLoading) return neutralise(currentSpeed)

        val expected = projectHostPosition(nowMs, host, clockOffsetMs) ?: return neutralise(currentSpeed)
        val drift = expected - localPositionMs
        val magnitude = abs(drift)

        // Hysteresis: it takes a clear error to start correcting, but once correcting we hold
        // on until the gap is properly closed. A single threshold would toggle the speed on
        // and off every tick while the drift wobbled around it.
        val exitBand = if (nudgeSinceMs != null) config.releaseNudgeMs else config.deadbandMs
        if (magnitude < exitBand) {
            nudgeSinceMs = null
            return neutralise(currentSpeed)
        }

        // Only seek when a seek is expected to at least halve the error. A seek lands within
        // roughly one granularity of its target, so at a drift of exactly that much it buys
        // nothing — and the residual re-trips this branch on the next tick, which is the
        // oscillation this whole estimator exists to prevent.
        val seekThreshold = max(config.hardSeekThresholdMs, 2 * seekGranularity.granularityMs)

        if (magnitude >= seekThreshold) {
            nudgeSinceMs = null
            return WatchTogetherSyncDecision.HardSeek(max(0L, expected))
        }

        val startedAt = nudgeSinceMs ?: nowMs.also { nudgeSinceMs = it }
        if (nowMs - startedAt > config.maxNudgeDurationMs) {
            // Speed has had its chance and the gap is not closing; take the seek's inaccuracy
            // over an indefinite pitch shift.
            nudgeSinceMs = null
            return WatchTogetherSyncDecision.HardSeek(max(0L, expected))
        }

        // Past the plain seek threshold but forbidden from seeking: speed is the only tool
        // left, so let it work harder.
        val cap = if (magnitude >= config.hardSeekThresholdMs) config.maxNudgeWide else config.maxNudge
        val raw = drift.toDouble() / config.nudgeGainMs.toDouble()
        val bounded = min(cap.toDouble(), max(-cap.toDouble(), raw))
        return WatchTogetherSyncDecision.Nudge(roundSpeed(1.0 + bounded))
    }

    private fun neutralise(currentSpeed: Float): WatchTogetherSyncDecision =
        if (abs(currentSpeed - 1.0f) < 0.001f) {
            WatchTogetherSyncDecision.Hold
        } else {
            WatchTogetherSyncDecision.Nudge(1.0f)
        }

    private fun projectHostPosition(
        nowMs: Long,
        host: WatchTogetherHostSample,
        clockOffsetMs: Long,
    ): Long? {
        val sampleInGuestTime = host.sampleTimeMs - clockOffsetMs
        val elapsed = nowMs - sampleInGuestTime
        if (elapsed < 0) return host.positionMs
        if (elapsed > config.maxProjectionMs) return null
        return host.positionMs + elapsed
    }

    private fun roundSpeed(value: Double): Float = ((value * 1000).roundToLong() / 1000.0).toFloat()
}

/** What the stall detector wants the guest to tell the host, if anything. */
enum class WatchTogetherStallSignal { STALL, CLEARED }

/**
 * Debounces the local buffering signal.
 *
 * mpv's `core-idle` — which `isLoading` folds in — flickers on every seek, so a raw edge
 * would pause the room constantly. Clearing additionally requires real runway ahead, or the
 * room resumes straight back into the same stall.
 */
class WatchTogetherStallDetector(
    private val stallAfterMs: Long = 700,
    private val clearAfterMs: Long = 500,
    private val requiredBufferAheadMs: Long = 3_000,
) {
    private var loadingSinceMs: Long? = null
    private var readySinceMs: Long? = null
    private var stalled = false

    val isStalled: Boolean get() = stalled

    fun update(nowMs: Long, isLoading: Boolean, bufferedAheadMs: Long): WatchTogetherStallSignal? {
        if (isLoading) {
            readySinceMs = null
            val since = loadingSinceMs ?: nowMs.also { loadingSinceMs = it }
            if (!stalled && nowMs - since >= stallAfterMs) {
                stalled = true
                return WatchTogetherStallSignal.STALL
            }
            return null
        }

        loadingSinceMs = null
        if (!stalled) {
            readySinceMs = null
            return null
        }

        if (bufferedAheadMs < requiredBufferAheadMs) {
            readySinceMs = null
            return null
        }

        val since = readySinceMs ?: nowMs.also { readySinceMs = it }
        if (nowMs - since >= clearAfterMs) {
            stalled = false
            readySinceMs = null
            return WatchTogetherStallSignal.CLEARED
        }
        return null
    }

    fun reset() {
        loadingSinceMs = null
        readySinceMs = null
        stalled = false
    }
}

/**
 * Gives up on automatic stall handling once it is clearly not working.
 *
 * A link that cannot sustain the stream produces an endless pause/resume cycle, which is a
 * worse experience than saying so plainly and letting the pair pick a smaller source.
 */
class WatchTogetherStallThrashGuard(
    private val maxCycles: Int = 3,
    private val windowMs: Long = 60_000,
) {
    private val resumeTimes = ArrayDeque<Long>()
    private var degraded = false

    val isDegraded: Boolean get() = degraded

    /** @return true when the caller should stop auto-pausing and surface the degraded state. */
    fun onAutoResume(nowMs: Long): Boolean {
        resumeTimes.addLast(nowMs)
        while (resumeTimes.isNotEmpty() && nowMs - resumeTimes.first() > windowMs) {
            resumeTimes.removeFirst()
        }
        if (resumeTimes.size >= maxCycles) degraded = true
        return degraded
    }

    fun reset() {
        resumeTimes.clear()
        degraded = false
    }
}
