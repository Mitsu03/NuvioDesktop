package com.nuvio.app.features.player.desktop.autosync

import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `setSubtitleDelay` coerces into +/-60s, so routing a larger offset through it applies the clamp
 * instead of the offset -- which looks, to the viewer, exactly like a subtitle that never synced.
 * A real run produced -102300 ms and landed on -60000.
 */
class AutoSyncApplyPathTest {
    @Test
    fun `a constant offset within the delay range uses the delay sink`() {
        assertTrue(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = 2_500))
        assertTrue(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MIN_MS))
        assertTrue(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MAX_MS))
    }

    @Test
    fun `an offset the delay sink would clamp does not use it`() {
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = -102_300))
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MIN_MS - 1))
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.0, offsetMs = SUBTITLE_DELAY_MAX_MS + 1))
    }

    @Test
    fun `real scale drift never uses the delay sink`() {
        assertFalse(AutoSyncApply.fitsDelayOnly(scale = 1.042, offsetMs = 0))
    }
}
