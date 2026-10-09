# Changelog

All notable changes to YZ Music are recorded here.

## [1.6.5] — 2026-10-09

YouTube Music is now the default source, with JioSaavn as a fallback. The
artist page, Replay and Library are rebuilt against BitChord v1.8, and detail
pages get real glassmorphism.

51 files changed, 5765 insertions(+), 896 deletions(-)

### Sources

- **YouTube Music is tried first** by default. Previously JioSaavn was ranked
  above it, so the fallback was silently unreachable.
- **JioSaavn is now a fallback, not a substitution.** A bare reorder would have
  made it unreachable entirely, so `SourceResolver.fallbackForYouTube()` was
  added alongside the existing `canSubstituteForYouTube()`, and the
  `PlaybackService` gate was widened to allow either.
- `PlaybackService` falls back to JioSaavn when YouTube fails to resolve a
  track, and not the other way round.
- The JioSaavn source description now states plainly that it has higher quality
  but some music may be unrelated, rather than advertising it as primary.

### Artist page

Rebuilt 1:1 against BitChord v1.8.

- **Apple artist artwork.** `AppleArtistArtRepository` looks the artist up via
  the iTunes API and `music.apple.com`, backed by a 64-entry LRU, and publishes
  the artwork's own key colour.
- **Wordmark header.** The artist's own logo at 82% width / 30% height, lifted
  64dp, with two 800ms retries. Falls back to text when Apple publishes no art.
- **70dp Play circle** with the triangle punched out via `BlendMode.Clear`,
  filled with Apple's own colour where one was found.
- **Subscribe star.** `SubscriptionState` is parsed off the artist header's own
  subscribe button — the same nodes the subscriber count already came from — and
  toggled optimistically, reverting if YouTube refuses the write. Not offered to
  a signed-out account, where the button is only an invitation to sign in.
- **Top release card** with a working save toggle. `releaseLibraryState()` reads
  the answer and the playlist id off the release's own page, since a card that
  only knows the browse id has neither.
- **About section** moved to the page bottom with stats folded in.
- **Uniform square-card carousel**, plus a top release card and an in-page
  `ArtistShelfGridPage` reached through `AnimatedContent`.
- Pager width is measured rather than assumed, and playing rows animate via
  `SearchPlayingBars`.
- `MergeBand` removed; a `SectionCard` gained a `showRank` flag instead of
  losing the `#N` badge globally.
  - Note: three KDoc comments in `DetailScreen.kt` still reference `[MergeBand]`
    by name. Those are dangling documentation links only — no code refers to it.

### Glassmorphism

- `artworkPageSurface()` and `ArtworkPageBackButton()` in `FrostedTopBar.kt`,
  with `artworkPageChrome` and `backButtonHazeState` parameters.
- `lightweightLiquidGlass` in `LiquidGlass.kt`, and a new `OptimizedHaze.kt`.
- Enabled in `MainActivity` for detail, grid and Replay pages.
- **Glassy trailing action pill** — `ArtworkPageActions` groups the top-bar
  controls on one surface so they read as a single control over the artwork.
- **Share action** for artists, sending the channel link. Gated on a `UC`
  browse id, since that is what makes it a real channel.

### Replay

- 85 new keys across all 10 locales: 80 strings and 5 plurals. The default
  locale goes from 484 to 569 keys (560 strings + 9 plurals), and each
  translation from 208 to 293. Counts use real Android plurals rather than
  `countOf(n, noun)`, covering minutes, song/artist/play counts and track
  counts.
- Card order is Minutes, Top song, Top artist, Top album.
- Theme-aware ReplayScreen; landing page auto-scrolls; stories key off
  `settledPage` rather than the raw page index.
- Dwell time 6s → 14s. Next on the last page now closes the sheet.
- Save crossfades to a check + "Saved".
- `ReplayCardRow` de-duplicated; `DEFAULT_HOLDER` removed.

### Library

- `LibraryLinkList` replaces the folder cards: icon rows with chevrons and
  hairlines.
- **On Device** grid now lists downloaded playlists only.
- An empty Playlists shelf when signed out.
- `ReplayCardRowSkeleton` while loading.

### Fixes

- Shuffle and Star rendered as **rounded squares** on the artist page.
  `CircleIconButton`'s `lightFill` branch was missing the `.clip(CircleShape)`
  that precedes the background, so the fill and border were laid out on a square
  with only the border drawn round.
- The sort control no longer appears on artist pages. It was showing on every
  detail page, where it does not belong: an artist page's Top songs is a preview
  of twenty rows, not one ordered track list. Album and playlist pages keep it.

### Deliberately not ported from v1.8

- BitChord's palette rewrite (`PlayerPlatform`, `paletteSwatches`,
  `forPixelAccess`) — it needs the Compose Multiplatform layer. Only
  `ArtworkKeyColors` was added surgically.
- The three-tier `QueueCoordinator`, the Float32 `PrecisionAudioSink` rewrite,
  bundled FFmpeg, DSD/DST, the desktop app, Discord, `IosOverscroll`,
  `PlayerSheetMotion` and Chromecast.

### Verification

- 898 unit tests pass, 0 failures.
- `assembleProdRelease` and `assembleDevDebug` both BUILD SUCCESSFUL.
- The prod APK is R8-minified; every new symbol was confirmed present via
  `mapping.txt`, and both APKs install and launch without crashing on an
  Android 16 device.

### Known pre-existing issues, not fixed here

- `AppUpdateChecker.kt:130` writes updates to `bitchord-<version>.apk`.
- Lint reports 317 errors, all pre-existing: 276 `MissingTranslation` on
  `listen_together_*` and 36 media3 `UnsafeOptInUsageError`.
- `MissingSuperCall` in `LoudnessBoostProcessor.kt:142`.
- Lint `PropertyEscape` on `local.properties`.