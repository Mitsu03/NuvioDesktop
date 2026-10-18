package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContinueWatchingEnrichmentCacheTest {

    @Test
    fun `legacy undated next up is refreshed without dropping dated episodes`() {
        val profileId = 987
        val source = WatchProgressSource.TRAKT
        val key = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(profileId, source)
        ContinueWatchingEnrichmentStorage.savePayload(
            key,
            """{"nextUp":[{"contentId":"tt7441658","contentType":"series","name":"Black Clover","videoId":"tt7441658:2:4","season":2,"episode":4,"released":null,"lastWatched":1,"sortTimestamp":1},{"contentId":"tt2","contentType":"series","name":"Dated","videoId":"tt2:1:4","season":1,"episode":4,"released":"2026-10-24T14:00:00.000Z","lastWatched":2,"sortTimestamp":2}]}""",
        )
        ContinueWatchingEnrichmentCache.clearLocalState()

        val snapshot = ContinueWatchingEnrichmentCache.getNextUpSnapshot(profileId, source)
        assertEquals(listOf("tt2"), snapshot.map(CachedNextUpItem::contentId))

        ContinueWatchingEnrichmentCache.clearAll(profileId)
    }

    @Test
    fun `storage keys are scoped by profile and effective source`() {
        val traktKey = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(
            profileId = 2,
            source = WatchProgressSource.TRAKT,
        )
        val nuvioKey = ContinueWatchingEnrichmentCache.continueWatchingEnrichmentStorageKey(
            profileId = 2,
            source = WatchProgressSource.NUVIO_SYNC,
        )

        assertEquals("cw_enrichment_cache_trakt_2", traktKey)
        assertEquals("cw_enrichment_cache_nuvio_sync_2", nuvioKey)
        assertNotEquals(traktKey, nuvioKey)
        assertEquals("cw_enrichment_cache_2", ContinueWatchingEnrichmentCache.legacyStorageKey(profileId = 2))
    }

    @Test
    fun `stale resolver generation cannot write snapshots after invalidation`() {
        val profileId = 4
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.invalidate(
            profileId = profileId,
            source = WatchProgressSource.TRAKT,
        )

        assertFalse(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = profileId,
                source = WatchProgressSource.TRAKT,
                generation = staleGeneration,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )
        assertTrue(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = profileId,
                source = WatchProgressSource.TRAKT,
                generation = ContinueWatchingEnrichmentCache.generation.value,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )

        ContinueWatchingEnrichmentCache.clearAll(profileId)
    }

    @Test
    fun `account clear invalidates resolver writes from the previous account`() {
        val staleGeneration = ContinueWatchingEnrichmentCache.generation.value

        ContinueWatchingEnrichmentCache.clearLocalState()

        assertFalse(
            ContinueWatchingEnrichmentCache.saveSnapshots(
                profileId = 1,
                source = WatchProgressSource.NUVIO_SYNC,
                generation = staleGeneration,
                nextUp = emptyList(),
                inProgress = emptyList(),
            ),
        )
        ContinueWatchingEnrichmentCache.clearAll(profileId = 1)
    }
}
