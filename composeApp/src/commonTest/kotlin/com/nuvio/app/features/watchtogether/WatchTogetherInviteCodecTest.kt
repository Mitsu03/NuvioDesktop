package com.nuvio.app.features.watchtogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherInviteCodecTest {
    private val invite = WatchTogetherInvite(
        baseUrl = "http://192.168.1.10:47500",
        joinToken = "SYNTHETIC-TOKEN-abc123",
    )

    @Test
    fun anInviteSurvivesTheRoundTrip() {
        val parsed = parseWatchTogetherInvite(buildWatchTogetherInviteUrl(invite))
        assertEquals(invite, parsed)
    }

    @Test
    fun aTailscaleAddressIsJustAnotherBaseUrl() {
        // The whole point of carrying a base URL rather than host+port: the NAT fallback is
        // a settings substitution, not a code path.
        val tailscale = invite.copy(baseUrl = "http://100.101.102.103:47500")
        assertEquals(tailscale, parseWatchTogetherInvite(buildWatchTogetherInviteUrl(tailscale)))
    }

    @Test
    fun aTrailingSlashIsNormalisedAwaySoTheStreamPathIsNeverDoubled() {
        val parsed = parseWatchTogetherInvite(
            buildWatchTogetherInviteUrl(invite.copy(baseUrl = "http://192.168.1.10:47500/")),
        )
        assertEquals("http://192.168.1.10:47500", parsed?.baseUrl)
    }

    @Test
    fun surroundingWhitespaceFromAPasteIsTolerated() {
        val url = buildWatchTogetherInviteUrl(invite)
        assertEquals(invite, parseWatchTogetherInvite("  $url\n"))
    }

    @Test
    fun aNonNuvioLinkIsNotAnInvite() {
        assertNull(parseWatchTogetherInvite("https://example.com/watch-together?u=x&k=y"))
        assertNull(parseWatchTogetherInvite("stremio://watch-together?u=x&k=y"))
    }

    @Test
    fun anotherNuvioDeepLinkIsNotMistakenForAnInvite() {
        assertNull(parseWatchTogetherInvite("nuvio://meta?type=series&id=tt0000000"))
        assertNull(parseWatchTogetherInvite("nuvio://downloads"))
    }

    @Test
    fun anInviteMissingItsTokenOrAddressIsRejected() {
        assertNull(parseWatchTogetherInvite("nuvio://watch-together?u=http%3A%2F%2F192.168.1.10%3A47500&v=1"))
        assertNull(parseWatchTogetherInvite("nuvio://watch-together?k=SYNTHETIC&v=1"))
        assertNull(parseWatchTogetherInvite("nuvio://watch"))
    }

    /**
     * The base URL becomes the player's source URL, so a pasted link must not be able to
     * aim the player at an arbitrary scheme.
     */
    @Test
    fun onlyHttpAddressesAreAccepted() {
        assertNull(parseWatchTogetherInvite("nuvio://watch-together?u=file%3A%2F%2F%2FC%3A%2Fwindows&k=T&v=1"))
        assertNull(parseWatchTogetherInvite("nuvio://watch-together?u=javascript%3Aalert(1)&k=T&v=1"))
        assertNull(parseWatchTogetherInvite("nuvio://watch-together?u=192.168.1.10%3A47500&k=T&v=1"))
    }

    @Test
    fun anUnknownProtocolVersionIsPreservedSoTheJoinCanRejectItProperly() {
        // Parsing must not silently coerce it to 1, or the mismatch is never reported.
        val parsed = parseWatchTogetherInvite(
            "nuvio://watch-together?u=http%3A%2F%2F192.168.1.10%3A47500&k=T&v=99",
        )
        assertEquals(99, parsed?.protocolVersion)
    }

    @Test
    fun theDisplayCodeIsStableAndAvoidsTheCharactersPeopleMishear() {
        val code = invite.displayCode
        assertEquals(6, code.length)
        assertEquals(code, watchTogetherDisplayCode(invite.joinToken))
        assertTrue(code.none { it in "IO01" }, "code was '$code'")
    }

    @Test
    fun aTruncatedTokenProducesADifferentCodeSoTheMismatchIsAudible() {
        assertNotEquals(
            watchTogetherDisplayCode("SYNTHETIC-TOKEN-abc123"),
            watchTogetherDisplayCode("SYNTHETIC-TOKEN-abc12"),
        )
    }
}
