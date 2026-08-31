# Continue Watching thumbnails — iOS/Android verification plan

Context for whoever builds this branch on a Mac. The change itself is in
`commonMain`, so it ships to every target; only the desktop target has been
built and run so far.

## What changed and why

Two long-running anime (One Piece, Naruto Shippūden) showed blank tiles in
Continue Watching. Three independent things had to line up for that:

1. **The TMDB episode join missed.** `TmdbMetadataService.fetchEpisodeEnrichment`
   indexes stills by `(season, episode)`. TVDB-derived addons number episodes
   relative to the season; TMDB numbers these shows *absolutely* inside
   differently-cut seasons — TMDB's One Piece season 5 runs episodes 131..143,
   not 1..13. So the lookup found nothing at all for such shows.
2. **The fallback was a URL nobody validated.** With no TMDB still, the addon's
   own value survived: Cinemeta emits
   `https://episodes.metahub.space/<imdb>/<s>/<e>/w780.jpg` for *every* episode,
   whether or not the image exists. For these titles it 404s.
3. **The UI picked artwork by blankness, not loadability.** The card chose its
   image with a `firstNonBlank(...)` chain, so the dead URL won over the poster
   and backdrop sitting right behind it, and the tile rendered empty.

The fix addresses (1) and (3):

- `TmdbMetadataService.fillEpisodeGapsByAbsoluteNumber` — when the seasons
  already fetched prove TMDB is numbering absolutely (a season whose lowest
  episode number is above 1), unresolved episodes are re-resolved by their
  absolute position in the addon's own ordering. Gated deliberately: for shows
  numbered normally it never fires, because a wrong absolute mapping would show
  *the wrong episode's* still, which is worse than none.
- `HomeContinueWatchingSection` — the artwork selectors now return the whole
  ordered candidate list, and `ContinueWatchingArtworkState` walks it as each URL
  fails to load. Fallback is driven by load failure, not by string emptiness.

## What to check on macOS

1. **Build the iOS target.** This is the only part that could not be checked on
   Windows (Kotlin/Native iOS targets are disabled on non-Mac hosts):

   ```
   ./gradlew :composeApp:compileKotlinIosSimulatorArm64
   ```

   The new code uses only `androidx.compose.runtime` (`Stable`, `mutableStateOf`,
   `remember`), stdlib collections and `kotlinx.serialization`, so there is no
   expected platform issue — but it has not been compiled.

2. **Run the shared tests.** Four new cases cover the numbering logic:

   ```
   ./gradlew :composeApp:desktopTest --tests '*TmdbMetadataServiceTest*'
   ```

3. **Check it in the app.** Open Continue Watching with a long-running anime in
   progress. The tile should show the episode still. Two known-good values, taken
   from the live APIs:

   | Addon coordinates | TMDB location | Expected still |
   |---|---|---|
   | One Piece S5E8 | S2E68 | `image.tmdb.org/t/p/w500/xusCozsCpbu5obkaD15Jc5vwvkD.jpg` |
   | Naruto Shippūden S17E21 | S18E382 | `image.tmdb.org/t/p/w500/cHhCMU64MrSv2zAUOTTZpIrrBGV.jpg` |

   Requires a TMDB API key configured and TMDB episode metadata enabled, since
   the recovery path goes through TMDB.

4. **Confirm the safety gate still declines.** Some shows fail for a *different*
   reason — TMDB splits their seasons another way, or lacks the season entirely
   (Re:ZERO has no TMDB season 4). Those must NOT get an absolute-number guess;
   they should fall back to the poster via the load-failure path instead. A tile
   showing the wrong episode's image is a regression, a tile showing the series
   poster is correct behaviour.

## Android: pre-existing breakage, unrelated

`./gradlew :composeApp:compileAndroidMain` fails on this branch, and fails
identically at HEAD without these changes. Five errors, all desktop-only Compose
APIs referenced from `commonMain`:

- `features/player/PlayerScreenContent.kt` — `PointerButton`, `button`
- `MainAppContent.kt` — `PointerButton`, `button`
- `features/home/components/HomePosterHoverPreview.kt` — `onPointerEvent`

These need `expect`/`actual` or a desktop-only source set to build for Android.
Out of scope here, but worth fixing separately if the Android target is meant to
keep compiling.
