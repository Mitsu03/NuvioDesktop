package com.nuvio.app.features.filler

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimeFillerListTest {

    // Rows copied verbatim from https://www.animefillerlist.com/shows/bleach.
    private val bleachPage = """
        <tr class="manga_canon odd" id="eps-1"><td class="Number">1</td><td class="Title"><a href="/shows/bleach/day-i-became-shinigami" rel="nofollow">The Day I Became a Shinigami</a></td><td class="Type"><span>Manga Canon</span></td><td class="Date">2004-10-05</td> </tr>
        <tr class="mixed_canon/filler even" id="eps-8"><td class="Number">8</td><td class="Title"><a href="/shows/bleach/june-17-memories-rain" rel="nofollow">June 17, Memories in the Rain</a></td><td class="Type"><span>Mixed Canon/Filler</span></td><td class="Date">2004-11-23</td> </tr>
        <tr class="manga_canon even" id="eps-28"><td class="Number">28</td><td class="Title"><a href="/shows/bleach/orihime-targeted" rel="nofollow">Orihime Targeted</a></td><td class="Type"><span>Manga Canon</span></td><td class="Date">2005-04-19</td> </tr>
        <tr class="filler odd" id="eps-33"><td class="Number">33</td><td class="Title"><a href="/shows/bleach/miracle-mysterious-new-hero" rel="nofollow">Miracle! The Mysterious New Hero</a></td><td class="Type"><span>Filler</span></td><td class="Date">2005-05-26</td> </tr>
        <tr class="manga_canon odd" id="eps-49"><td class="Number">49</td><td class="Title"><a href="/shows/bleach/rukias-nightmare" rel="nofollow">Rukia&#039;s Nightmare</a></td><td class="Type"><span>Manga Canon</span></td><td class="Date">2005-09-13</td> </tr>
    """.trimIndent()

    // Links copied verbatim from https://www.animefillerlist.com/shows.
    private val showIndex = """
        <li><a href="/shows/bleach">Bleach</a></li>
        <li><a href="/shows/certain-magical-index">A Certain Magical Index (Toaru Majutsu No Index)</a></li>
        <li><a href="/shows/hunter-x-hunter-2011-films">Hunter x Hunter (2011) Films</a></li>
        <li><a href="/shows/hunter-x-hunter-1999">Hunter × Hunter </a></li>
        <li><a href="/shows/hunter-x-hunter">Hunter × Hunter (2011)</a></li>
    """.trimIndent()

    @Test
    fun onlyPureFillerRowsAreCounted() {
        val episodes = AnimeFillerListParser.parseEpisodes(bleachPage)

        assertEquals(setOf(33), episodes.fillerEpisodes)
        assertEquals(2004, episodes.firstAiredYear)
    }

    @Test
    fun showIndexSplitsAlternativeTitlesAndYears() {
        val shows = AnimeFillerListParser.parseShowIndex(showIndex).associateBy { it.slug }

        assertEquals(listOf("A Certain Magical Index", "Toaru Majutsu No Index"), shows.getValue("certain-magical-index").names)
        assertEquals(listOf("Hunter × Hunter"), shows.getValue("hunter-x-hunter").names)
        assertEquals(2011, shows.getValue("hunter-x-hunter").yearHint)
        assertNull(shows.getValue("hunter-x-hunter-1999").yearHint)
    }

    @Test
    fun seasonalNumberingIsFlattenedBeforeLookup() {
        // TMDB splits Bleach into seasons of 20, 21 and 22 episodes, so S2E13 is episode 33 and S3E8 is 49.
        val videos = bleachVideos(20, 21, 22)

        val keys = FillerEpisodeMatcher.fillerEpisodeKeys(videos, setOf(33))

        assertEquals(setOf(2 to 13), keys)
        assertFalse(keys.isFiller(2, 8)) // episode 28, canon
        assertFalse(keys.isFiller(3, 8)) // episode 49, canon
    }

    @Test
    fun specialsDoNotShiftTheCount() {
        val videos = listOf(video(0, 1)) + bleachVideos(20, 21)

        assertEquals(setOf(2 to 13), FillerEpisodeMatcher.fillerEpisodeKeys(videos, setOf(33)))
    }

    @Test
    fun absolutelyNumberedSeasonsAreUsedAsTheyAre() {
        // TMDB numbers One Piece absolutely inside each season.
        val videos = (1..3).map { video(1, it) } + (131..133).map { video(5, it) }

        assertEquals(setOf(5 to 131), FillerEpisodeMatcher.fillerEpisodeKeys(videos, setOf(131, 200)))
    }

    @Test
    fun remakesAreToldApartByYear() {
        val index = AnimeFillerListParser.parseShowIndex(showIndex)
        val meta = meta(name = "Hunter x Hunter", releaseInfo = "2011-2014")
        val candidates = FillerEpisodeMatcher.candidateShows(meta, index)

        assertEquals(setOf("hunter-x-hunter-1999", "hunter-x-hunter"), candidates.map { it.slug }.toSet())

        val old = AnimeFillerListEpisodes(fillerEpisodes = setOf(1), firstAiredYear = 1999)
        val new = AnimeFillerListEpisodes(fillerEpisodes = setOf(2), firstAiredYear = 2011)
        val picked = FillerEpisodeMatcher.pickShow(
            metaYear = FillerEpisodeMatcher.releaseYear(meta),
            candidates = candidates.map { show -> show to if (show.slug == "hunter-x-hunter") new else old },
        )
        assertEquals(new, picked)
    }

    @Test
    fun aMatchingTitleFromAnotherYearIsRejected() {
        val show = AnimeFillerListShow(slug = "monster", names = listOf("Monster"), yearHint = null)
        val episodes = AnimeFillerListEpisodes(fillerEpisodes = setOf(1), firstAiredYear = 2004)

        assertNull(FillerEpisodeMatcher.pickShow(metaYear = 2022, candidates = listOf(show to episodes)))
    }

    @Test
    fun aliasesAndRomajiTitlesMatch() {
        val index = AnimeFillerListParser.parseShowIndex(showIndex)
        val meta = meta(name = "Toaru Majutsu no Index", aliases = emptyList())

        assertEquals(listOf("certain-magical-index"), FillerEpisodeMatcher.candidateShows(meta, index).map { it.slug })
    }

    @Test
    fun liveActionSeriesAreNotLookedUp() {
        assertFalse(FillerEpisodeMatcher.isAnimeCandidate(meta(name = "Monster", genres = listOf("Crime", "Drama"))))
        assertTrue(FillerEpisodeMatcher.isAnimeCandidate(meta(name = "Bleach", genres = listOf("Animação", "Ação"))))
        assertTrue(FillerEpisodeMatcher.isAnimeCandidate(meta(id = "kitsu:244", name = "Bleach", genres = listOf("Action"))))
        assertFalse(FillerEpisodeMatcher.isAnimeCandidate(meta(name = "Bleach", type = "movie")))
    }

    @Test
    fun tagIsAppendedOnlyToTitledEpisodes() {
        assertEquals("Miracle! [Filler]", "Miracle!".withFillerTag("[Filler]"))
        assertEquals("[Filler]", "".withFillerTag("[Filler]"))
    }

    private fun bleachVideos(vararg seasonSizes: Int): List<MetaVideo> =
        seasonSizes.withIndex().flatMap { (index, size) -> (1..size).map { video(index + 1, it) } }

    private fun video(season: Int, episode: Int) =
        MetaVideo(id = "tt0434665:$season:$episode", title = "S${season}E$episode", season = season, episode = episode)

    private fun meta(
        id: String = "tt0000001",
        name: String,
        type: String = "series",
        releaseInfo: String? = null,
        genres: List<String> = listOf("Animation"),
        aliases: List<String> = emptyList(),
    ) = MetaDetails(id = id, type = type, name = name, releaseInfo = releaseInfo, genres = genres, aliases = aliases)
}
