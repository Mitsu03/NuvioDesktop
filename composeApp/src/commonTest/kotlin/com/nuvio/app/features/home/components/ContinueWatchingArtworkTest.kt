package com.nuvio.app.features.home.components

import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val Thumbnail = "https://episodes.metahub.space/tt0388629/5/7/w780.jpg"
private const val Backdrop = "https://images.metahub.space/background/medium/tt0388629/img"
private const val Poster = "https://images.metahub.space/poster/small/tt0388629/img"

private fun animeEpisode(
    episodeThumbnail: String? = Thumbnail,
    background: String? = Backdrop,
    poster: String? = Poster,
    isNextUp: Boolean = false,
): ContinueWatchingItem = ContinueWatchingItem(
    parentMetaId = "tt0388629",
    parentMetaType = "series",
    videoId = "tt0388629:5:7",
    title = "One Piece",
    subtitle = "S5E7",
    imageUrl = episodeThumbnail ?: background ?: poster,
    poster = poster,
    background = background,
    seasonNumber = 5,
    episodeNumber = 7,
    episodeThumbnail = episodeThumbnail,
    isNextUp = isNextUp,
    resumePositionMs = 0L,
    durationMs = 0L,
    progressFraction = 0.4f,
)

class ContinueWatchingArtworkTest {

    @BeforeTest
    fun resetBefore() = clearContinueWatchingBrokenArtwork()

    @AfterTest
    fun resetAfter() = clearContinueWatchingBrokenArtwork()

    @Test
    fun `card artwork prefers the episode thumbnail while it still loads`() {
        val url = animeEpisode().continueWatchingCardArtworkUrl(
            useEpisodeThumbnails = true,
            preferBackdropForNextUp = false,
        )

        assertEquals(Thumbnail, url)
    }

    @Test
    fun `card artwork falls through to the backdrop once the thumbnail is known broken`() {
        markContinueWatchingArtworkBroken(Thumbnail)

        val url = animeEpisode().continueWatchingCardArtworkUrl(
            useEpisodeThumbnails = true,
            preferBackdropForNextUp = false,
        )

        assertEquals(Backdrop, url)
    }

    @Test
    fun `card artwork keeps demoting until a candidate survives`() {
        markContinueWatchingArtworkBroken(Thumbnail)
        markContinueWatchingArtworkBroken(Backdrop)

        val url = animeEpisode().continueWatchingCardArtworkUrl(
            useEpisodeThumbnails = true,
            preferBackdropForNextUp = false,
        )

        assertEquals(Poster, url)
    }

    @Test
    fun `poster artwork demotes a broken poster to the backdrop`() {
        markContinueWatchingArtworkBroken(Poster)

        val url = animeEpisode().continueWatchingPosterArtworkUrl(useEpisodeThumbnails = true)

        assertEquals(Backdrop, url)
    }

    @Test
    fun `next up artwork demotes a broken thumbnail`() {
        markContinueWatchingArtworkBroken(Thumbnail)

        val url = animeEpisode(isNextUp = true).continueWatchingArtworkUrl(useEpisodeThumbnails = true)

        assertEquals(Poster, url)
    }

    // Everything failing is usually the network rather than the URLs, so the card keeps asking
    // for its last known artwork instead of going blank.
    @Test
    fun `all candidates broken keeps the first non blank candidate`() {
        markContinueWatchingArtworkBroken(Thumbnail)
        markContinueWatchingArtworkBroken(Backdrop)
        markContinueWatchingArtworkBroken(Poster)

        val url = animeEpisode().continueWatchingCardArtworkUrl(
            useEpisodeThumbnails = true,
            preferBackdropForNextUp = false,
        )

        assertEquals(Thumbnail, url)
    }

    @Test
    fun `marking ignores blank urls`() {
        markContinueWatchingArtworkBroken(null)
        markContinueWatchingArtworkBroken("   ")

        val url = animeEpisode(episodeThumbnail = null, background = null)
            .continueWatchingCardArtworkUrl(
                useEpisodeThumbnails = true,
                preferBackdropForNextUp = false,
            )

        assertEquals(Poster, url)
    }
}
