package com.nuvio.app.features.watchtogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherClockOffsetEstimatorTest {
    @Test
    fun offsetIsUnknownUntilAProbeLands() {
        val estimator = WatchTogetherClockOffsetEstimator()
        assertNull(estimator.offsetMs)
        assertTrue(!estimator.hasEstimate)
    }

    @Test
    fun symmetricRoundTripRecoversTheOffsetExactly() {
        val estimator = WatchTogetherClockOffsetEstimator()
        // Guest sends at 1000, reply arrives at 1100 (100 ms RTT). The host stamped 5050,
        // i.e. the host clock reads 4000 ahead of the guest's at the midpoint (1050).
        estimator.record(sentAtMs = 1_000, hostTimeMs = 5_050, receivedAtMs = 1_100)
        assertEquals(4_000, estimator.offsetMs)
        assertEquals(100, estimator.rttMs)
    }

    @Test
    fun theMinimumRttSampleWinsBecauseItIsLeastContaminatedByQueueing() {
        val estimator = WatchTogetherClockOffsetEstimator()
        // A badly queued probe: 2 s RTT, and its midpoint estimate is far off.
        estimator.record(sentAtMs = 0, hostTimeMs = 6_000, receivedAtMs = 2_000)
        // A clean probe: 20 ms RTT, true offset 4000.
        estimator.record(sentAtMs = 10_000, hostTimeMs = 14_010, receivedAtMs = 10_020)

        assertEquals(4_000, estimator.offsetMs)
        assertEquals(20, estimator.rttMs)
    }

    @Test
    fun theWindowDiscardsOldSamples() {
        val estimator = WatchTogetherClockOffsetEstimator(window = 2)
        estimator.record(sentAtMs = 0, hostTimeMs = 1_000, receivedAtMs = 2) // rtt 2, offset ~999
        estimator.record(sentAtMs = 100, hostTimeMs = 5_150, receivedAtMs = 200) // rtt 100
        estimator.record(sentAtMs = 300, hostTimeMs = 5_350, receivedAtMs = 400) // rtt 100

        // The very low-RTT first sample has aged out, so it can no longer win.
        assertEquals(5_000, estimator.offsetMs)
    }

    @Test
    fun aNegativeRoundTripIsIgnoredRatherThanPoisoningTheEstimate() {
        val estimator = WatchTogetherClockOffsetEstimator()
        estimator.record(sentAtMs = 500, hostTimeMs = 1_000, receivedAtMs = 400)
        assertNull(estimator.offsetMs)
    }
}

class WatchTogetherDriftControllerTest {
    private val config = WatchTogetherSyncConfig()

    private fun playingHost(sampleTimeMs: Long, positionMs: Long) = WatchTogetherHostSample(
        sampleTimeMs = sampleTimeMs,
        positionMs = positionMs,
        isPlaying = true,
        isLoading = false,
    )

    @Test
    fun aDriftInsideTheDeadbandIsLeftAlone() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 1_000,
            host = playingHost(sampleTimeMs = 1_000, positionMs = 60_000),
            clockOffsetMs = 0,
            localPositionMs = 59_950, // 50 ms behind
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        assertEquals(WatchTogetherSyncDecision.Hold, decision)
    }

    @Test
    fun aSmallLagSpeedsTheGuestUp() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 1_000,
            host = playingHost(sampleTimeMs = 1_000, positionMs = 60_000),
            clockOffsetMs = 0,
            localPositionMs = 59_600, // 400 ms behind
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        val nudge = assertIs<WatchTogetherSyncDecision.Nudge>(decision)
        assertEquals(1.05f, nudge.speed)
    }

    @Test
    fun runningAheadSlowsTheGuestDown() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 1_000,
            host = playingHost(sampleTimeMs = 1_000, positionMs = 60_000),
            clockOffsetMs = 0,
            localPositionMs = 60_400, // 400 ms ahead
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        val nudge = assertIs<WatchTogetherSyncDecision.Nudge>(decision)
        assertEquals(0.95f, nudge.speed)
    }

    @Test
    fun theHostClockIsTranslatedIntoGuestTimeBeforeProjecting() {
        val controller = WatchTogetherDriftController(config)
        // Host monotonic runs 1_000_000 ahead of the guest's. The sample was taken at host
        // 1_001_000, i.e. guest 1_000, and it is now guest 1_500 — so 500 ms have elapsed
        // and the host should be at 60_500. The guest is there: no correction.
        val decision = controller.decide(
            nowMs = 1_500,
            host = playingHost(sampleTimeMs = 1_001_000, positionMs = 60_000),
            clockOffsetMs = 1_000_000,
            localPositionMs = 60_500,
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        assertEquals(WatchTogetherSyncDecision.Hold, decision)
    }

    @Test
    fun aLargeGapSeeksRatherThanCrawling() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 1_000,
            host = playingHost(sampleTimeMs = 1_000, positionMs = 120_000),
            clockOffsetMs = 0,
            localPositionMs = 60_000,
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        val seek = assertIs<WatchTogetherSyncDecision.HardSeek>(decision)
        assertEquals(120_000, seek.positionMs)
    }

    /**
     * The regression this whole design exists for: on Windows and macOS a seek lands on the
     * nearest keyframe, so asking for 120 s can settle at 116 s. A naive 2 s threshold then
     * re-fires immediately and the guest seeks forever.
     */
    @Test
    fun aKeyframeResidualDoesNotProduceASecondSeek() {
        val controller = WatchTogetherDriftController(config)

        controller.onSeekIssued(nowMs = 1_000, targetMs = 120_000)
        controller.onSeekSettled(settledPositionMs = 116_000) // 4 s short
        assertEquals(4_000, controller.seekGranularityMs)

        // Settle window has passed; the host has moved on to ~123 s, guest sits at ~119 s.
        val decision = controller.decide(
            nowMs = 4_000,
            host = playingHost(sampleTimeMs = 4_000, positionMs = 123_000),
            clockOffsetMs = 0,
            localPositionMs = 119_000, // still 4 s behind — exactly one GOP
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )

        // A seek cannot do better than 4 s, so speed must close it instead.
        val nudge = assertIs<WatchTogetherSyncDecision.Nudge>(decision)
        assertEquals(1.10f, nudge.speed, "a residual seeking cannot fix must use the wide cap")
    }

    @Test
    fun correctionIsSuspendedWhileThePlayerSettlesAfterASeek() {
        val controller = WatchTogetherDriftController(config)
        controller.onSeekIssued(nowMs = 1_000, targetMs = 120_000)

        val decision = controller.decide(
            nowMs = 1_200, // well inside the 1500 ms settle window
            host = playingHost(sampleTimeMs = 1_200, positionMs = 120_000),
            clockOffsetMs = 0,
            localPositionMs = 0, // mpv has not caught up yet; position is meaningless
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        assertEquals(WatchTogetherSyncDecision.Hold, decision)
    }

    @Test
    fun aNudgeIsWoundBackToNeutralWhenTheGapCloses() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 1_000,
            host = playingHost(sampleTimeMs = 1_000, positionMs = 60_000),
            clockOffsetMs = 0,
            localPositionMs = 59_990, // 10 ms — inside the deadband
            guestIsLoading = false,
            currentSpeed = 1.05f, // but the player is still running fast
        )
        val nudge = assertIs<WatchTogetherSyncDecision.Nudge>(decision)
        assertEquals(1.0f, nudge.speed)
    }

    /**
     * Starting a correction takes a clear error, but stopping one takes a properly closed
     * gap. With a single threshold, a drift wobbling either side of it would switch the
     * speed on and off every tick.
     */
    @Test
    fun aCorrectionAlreadyUnderWayIsHeldThroughTheEntryThreshold() {
        val controller = WatchTogetherDriftController(config)

        // 400 ms opens the nudge.
        assertIs<WatchTogetherSyncDecision.Nudge>(
            controller.decide(0, playingHost(0, 60_000), 0, 59_600, false, 1.0f),
        )

        // 100 ms would be inside the entry deadband, but the correction is already running
        // and 100 ms is still above the release band, so it keeps going.
        val held = controller.decide(1_000, playingHost(1_000, 61_000), 0, 60_900, false, 1.05f)
        assertIs<WatchTogetherSyncDecision.Nudge>(held)
        assertTrue(held.speed > 1.0f, "expected to still be catching up, got ${held.speed}")

        // 30 ms is inside the release band: wind back to neutral.
        val released = controller.decide(2_000, playingHost(2_000, 62_000), 0, 61_970, false, 1.01f)
        assertEquals(1.0f, assertIs<WatchTogetherSyncDecision.Nudge>(released).speed)
    }

    @Test
    fun aPausedHostIsMirroredElsewhereAndNeverDriftCorrected() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 1_000,
            host = WatchTogetherHostSample(1_000, 120_000, isPlaying = false, isLoading = false),
            clockOffsetMs = 0,
            localPositionMs = 60_000,
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        assertEquals(WatchTogetherSyncDecision.Hold, decision)
    }

    @Test
    fun aBufferingGuestIsNotCorrectedAgainst() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 1_000,
            host = playingHost(sampleTimeMs = 1_000, positionMs = 120_000),
            clockOffsetMs = 0,
            localPositionMs = 60_000,
            guestIsLoading = true,
            currentSpeed = 1.0f,
        )
        assertEquals(WatchTogetherSyncDecision.Hold, decision)
    }

    @Test
    fun aStaleHostSampleIsNotProjectedForward() {
        val controller = WatchTogetherDriftController(config)
        val decision = controller.decide(
            nowMs = 30_000, // 29 s after the sample: the host has gone quiet
            host = playingHost(sampleTimeMs = 1_000, positionMs = 60_000),
            clockOffsetMs = 0,
            localPositionMs = 60_000,
            guestIsLoading = false,
            currentSpeed = 1.0f,
        )
        assertEquals(WatchTogetherSyncDecision.Hold, decision)
    }

    @Test
    fun aNudgeThatNeverConvergesEscalatesToASeek() {
        val controller = WatchTogetherDriftController(config)
        val host = playingHost(sampleTimeMs = 0, positionMs = 60_000)

        // Open the nudge.
        controller.decide(0, host, 0, 59_500, false, 1.0f)

        // 31 s later the same gap is still there.
        val later = playingHost(sampleTimeMs = 31_000, positionMs = 91_000)
        val decision = controller.decide(31_000, later, 0, 90_500, false, 1.05f)

        assertIs<WatchTogetherSyncDecision.HardSeek>(decision)
    }
}

class WatchTogetherStallDetectorTest {
    @Test
    fun aBriefLoadingFlickerDoesNotPauseTheRoom() {
        val detector = WatchTogetherStallDetector()
        assertNull(detector.update(nowMs = 0, isLoading = true, bufferedAheadMs = 0))
        // mpv's core-idle blips on every seek; 300 ms is well inside the debounce.
        assertNull(detector.update(nowMs = 300, isLoading = false, bufferedAheadMs = 8_000))
        assertTrue(!detector.isStalled)
    }

    @Test
    fun sustainedBufferingRaisesAStall() {
        val detector = WatchTogetherStallDetector()
        assertNull(detector.update(0, isLoading = true, bufferedAheadMs = 0))
        assertEquals(
            WatchTogetherStallSignal.STALL,
            detector.update(800, isLoading = true, bufferedAheadMs = 0),
        )
        assertTrue(detector.isStalled)
    }

    @Test
    fun clearingRequiresRealRunwayAheadNotJustAnIdleFlag() {
        val detector = WatchTogetherStallDetector()
        detector.update(0, isLoading = true, bufferedAheadMs = 0)
        detector.update(800, isLoading = true, bufferedAheadMs = 0)

        // Loading dropped, but only 500 ms of buffer: resuming here stalls again at once.
        assertNull(detector.update(1_400, isLoading = false, bufferedAheadMs = 500))
        assertNull(detector.update(2_000, isLoading = false, bufferedAheadMs = 500))
        assertTrue(detector.isStalled)

        // Real runway, held long enough.
        assertNull(detector.update(2_100, isLoading = false, bufferedAheadMs = 6_000))
        assertEquals(
            WatchTogetherStallSignal.CLEARED,
            detector.update(2_700, isLoading = false, bufferedAheadMs = 6_000),
        )
        assertTrue(!detector.isStalled)
    }

    @Test
    fun aStallIsRaisedOnlyOncePerEpisode() {
        val detector = WatchTogetherStallDetector()
        detector.update(0, isLoading = true, bufferedAheadMs = 0)
        assertEquals(WatchTogetherStallSignal.STALL, detector.update(800, true, 0))
        assertNull(detector.update(1_600, isLoading = true, bufferedAheadMs = 0))
        assertNull(detector.update(2_400, isLoading = true, bufferedAheadMs = 0))
    }
}

class WatchTogetherStallThrashGuardTest {
    @Test
    fun aFewIsolatedStallsAreToleratedSilently() {
        val guard = WatchTogetherStallThrashGuard()
        assertTrue(!guard.onAutoResume(0))
        assertTrue(!guard.onAutoResume(90_000))
        assertTrue(!guard.onAutoResume(200_000))
        assertTrue(!guard.isDegraded)
    }

    @Test
    fun threeStallsInsideTheWindowGiveUpOnAutomaticRecovery() {
        val guard = WatchTogetherStallThrashGuard()
        assertTrue(!guard.onAutoResume(0))
        assertTrue(!guard.onAutoResume(10_000))
        assertTrue(guard.onAutoResume(20_000))
        assertTrue(guard.isDegraded)
    }
}
