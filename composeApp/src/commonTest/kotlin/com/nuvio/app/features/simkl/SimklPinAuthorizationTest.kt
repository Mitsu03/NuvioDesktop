package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklPinAuthorizationTest {
    @Test
    fun `device initialization uses documented codes url interval and expiry`() {
        val pending = SimklDeviceResponse(
            deviceCode = "DEVICE_CODE",
            userCode = "BDWP-HQPK",
            verificationUri = "https://simkl.com/pin",
            verificationUriComplete = "https://simkl.com/pin?user_code=BDWP-HQPK",
            expiresIn = 900,
            interval = 5,
        ).toPendingAuthorization(nowEpochMs = 1_000L)

        assertEquals("BDWP-HQPK", pending?.userCode)
        assertEquals("DEVICE_CODE", pending?.deviceCode)
        assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", pending?.verificationUrl)
        assertEquals(5, pending?.intervalSeconds)
        assertEquals(901_000L, pending?.expiresAtEpochMs)
    }

    @Test
    fun `device initialization falls back to the bare verification uri`() {
        val pending = SimklDeviceResponse(
            deviceCode = "DEVICE_CODE",
            userCode = "FGHIJ",
            verificationUri = "https://simkl.com/pin",
        ).toPendingAuthorization(nowEpochMs = 2_000L)

        assertEquals("https://simkl.com/pin", pending?.verificationUrl)
        assertEquals(5, pending?.intervalSeconds)
        assertEquals(902_000L, pending?.expiresAtEpochMs)
    }

    @Test
    fun `invalid device initialization responses are rejected`() {
        assertNull(SimklDeviceResponse().toPendingAuthorization(0L))
        assertNull(
            SimklDeviceResponse(userCode = "ABCDE").toPendingAuthorization(0L),
        )
        assertNull(
            SimklDeviceResponse(
                deviceCode = "DEVICE_CODE",
                userCode = "ABCDE",
            ).toPendingAuthorization(0L),
        )
    }

    @Test
    fun `device authorization expiry uses the server supplied deadline`() {
        assertFalse(isSimklPinAuthorizationExpired(10_000L, 9_999L))
        assertTrue(isSimklPinAuthorizationExpired(10_000L, 10_000L))
        assertTrue(isSimklPinAuthorizationExpired(null, 1L))
    }
}
