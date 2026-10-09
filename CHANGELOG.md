# Changelog

All notable changes to YZ Music are recorded here.

## [1.6.5] — 2026-10-09

YouTube Music is now the default source, with JioSaavn as a fallback. The artist
page, Replay and Library are rebuilt against BitChord v1.8, detail pages get real
glassmorphism, and the changelog now carries a full feature inventory.

### Sources

- **YouTube Music is tried first** by default. JioSaavn was previously ranked
  above it, which made the fallback silently unreachable.
- **JioSaavn is now a fallback, not a substitution.** A bare reorder would have
  made it unreachable entirely, so `SourceResolver.fallbackForYouTube()` was
  added alongside the existing `canSubstituteForYouTube()`, and the
  `PlaybackService` gate was widened to allow either.
- `PlaybackService` falls back to JioSaavn when YouTube fails to resolve a track,
  and not the other way round.
- The JioSaavn source description now says plainly that it has higher quality
  but some results may be an unrelated recording.

### Artist page

Rebuilt 1:1 against BitChord v1.8.

- **Apple artist artwork.** `AppleArtistArtRepository` looks the artist up via
  the iTunes API and `music.apple.com`, backed by a 64-entry LRU, and publishes
  the artwork's own key colour.
- **Wordmark header** at 82% width / 30% height, lifted 64dp, with two 800ms
  retries. Falls back to text where Apple publishes no art.
- **70dp Play circle** with the triangle punched out via `BlendMode.Clear`,
  filled with Apple's own colour where one was found.
- **Subscribe star.** `SubscriptionState` is parsed off the artist header's own
  subscribe button — the same nodes the subscriber count already came from — and
  toggled optimistically, reverting if YouTube refuses the write. Not offered to
  a signed-out account.
- **Top release card** with a working save toggle. `releaseLibraryState()` reads
  the answer and the playlist id off the release's own page, since a card that
  only knows the browse id has neither.
- **About** moved to the page bottom with stats folded in; uniform square-card
  carousel; in-page `ArtistShelfGridPage` via `AnimatedContent`; pager width
  measured rather than assumed; playing rows animate via `SearchPlayingBars`.
- `MergeBand` removed; `SectionCard` gained a `showRank` flag rather than losing
  the `#N` badge globally.
  - Three KDoc comments in `DetailScreen.kt` still reference `[MergeBand]` by
    name. Dangling documentation links only — no code refers to it.

### Glassmorphism

- `artworkPageSurface()` and `ArtworkPageBackButton()` in `FrostedTopBar.kt`,
  with `artworkPageChrome` and `backButtonHazeState` parameters.
- `lightweightLiquidGlass` in `LiquidGlass.kt`, and a new `OptimizedHaze.kt`.
- Enabled in `MainActivity` for detail, grid and Replay pages.
- **Glassy trailing action pill** — `ArtworkPageActions` groups the top-bar
  controls on one surface so they read as a single control over the artwork.
- **Share action** for artists, sending the channel link, gated on a `UC` browse
  id.

### Replay

- 85 new keys across all 10 locales: 80 strings and 5 plurals. The default
  locale goes from 484 to 569 keys, each translation from 208 to 293. Counts use
  real Android plurals rather than `countOf(n, noun)`, covering minutes,
  song/artist/play counts and track counts.
- Card order is Minutes, Top song, Top artist, Top album.
- Theme-aware ReplayScreen; landing page auto-scrolls; stories key off
  `settledPage` rather than the raw page index.
- Dwell time 6s → 14s. Next on the last page now closes the sheet.
- Save crossfades to a check + "Saved". `ReplayCardRow` de-duplicated;
  `DEFAULT_HOLDER` removed.

### Library

- `LibraryLinkList` replaces the folder cards: icon rows with chevrons and
  hairlines.
- **On Device** grid now lists downloaded playlists only.
- An empty Playlists shelf when signed out; `ReplayCardRowSkeleton` while loading.

### Fixes

- Shuffle and Star rendered as **rounded squares** on the artist page.
  `CircleIconButton`'s `lightFill` branch was missing the `.clip(CircleShape)`
  that precedes the background, so the fill and border were laid out on a square
  with only the border drawn round.
- The sort control no longer appears on artist pages. It was showing on every
  detail page, where it does not belong: an artist page's Top songs is a preview
  of twenty rows, not one ordered track list. Album and playlist pages keep it.

---

# Feature inventory

Everything the app currently contains. Grouped by area; each entry names the
file or symbol that implements it.

## Queue

| Feature | Where |
| --- | --- |
| Queue-wide insights and metadata analysis | `ui/player/queue/QueueInsights.kt` |
| Queue tags — genre, mood, artist, album, audio quality, explicit | `QueueTag`, `computeQueueTags()`, rendered as chips in `NowPlayingScreen.kt:3908` |
| Queue tag filtering | `activeTag` → `Song.passesFilter()` → `songMatchesTag()` |
| Queue statistics | `computeQueueStats()`, `QueueStats`, `formatQueueStats()` — **see caveat below** |
| Custom queue UI and section-specific behaviour | `NowPlayingScreen.kt` |
| Double-tap seeking | `NowPlayingNavigationModel.kt:366` (`doubleTapTimeoutMs`), `NowPlayingScreen.kt:672` |
| Drag-and-reorder with stable keys and autoscroll | `NowPlayingScreen.kt` |
| Playback tracking | `data/innertube/PlaybackTracker.kt` |

> **Caveat on Queue Statistics.** `computeQueueStats()`, `QueueStats` and
> `formatQueueStats()` are implemented and unit-tested in `QueueInsightsTest.kt`,
> but have **zero call sites in production UI** — the numbers are never
> displayed. Tags and tag filtering, by contrast, are fully wired. The statistics
> UI still needs building.

## Audio & streaming

| Feature | Where |
| --- | --- |
| Stream quality labels | `data/StreamQualityLabel.kt` |
| Resolved stream abstraction | `data/sources/ResolvedStream.kt` |
| Smart audio analysis — native `yzmusic::smart` | `native/analyzer/*.cpp/.h`, JNI in `app/src/main/cpp/jni/` |
| Native analysis library `libyzmusic_analysis.so` | CMake target `yzmusic_analysis`, `app/src/main/cpp/CMakeLists.txt` |
| Loudness boost processor | `playback/LoudnessBoostProcessor.kt` |
| Custom audio DSP components | `playback/` (43 files) |
| Mel spectrogram, tempo, resampler, vocal analysis | `native/analyzer/mel_spectrogram.cpp`, `tempo_analysis.cpp`, `resampler.cpp`, `vocal_spectrogram.cpp` |
| On-device neural model download and verification | `playback/smart/SmartAudioModelManager.kt` |
| Background-vocal detection | `playback/smart/VocalTracker.kt` |
| Crossfade | Media3 playback engine |
| High-fidelity downloads and media tagging | `download/` (12 files incl. `MediaTagger`) |

## Automix

| Feature | Where |
| --- | --- |
| Automix 2.0 policy | `playback/smart/AutomixPolicy.kt`, `AutomixDurationPolicy.kt` |
| Camelot / harmonic mixing | `playback/smart/CamelotHarmonics.kt` |
| Transition planning | `playback/smart/TransitionPlanner.kt`, `TransitionPolicy.kt` |

## Lyrics

| Feature | Where |
| --- | --- |
| Custom lyric rendering | `ui/player/LyricDraw.kt` (423 lines) |
| Lyric voices and rendering components | `ui/player/LyricVoices.kt` (1068 lines) |
| Lyric navigation model | `ui/player/NowPlayingNavigationModel.kt` (749 lines) |
| Lyrics network infrastructure | SimpMusic, Musixmatch, PaxSenix, LRCLIB providers |
| Word sync, embedded lyrics, background vocals | `playback/smart/`, lyric pipeline |

`NowPlayingNavigationModel.kt` holds `NowPlayingNavigationController`,
`PanelGestureTracker`, `FullPlayerGestureTracker`, `FullPlayerSeekCalculator`,
`QueueSwipeDownTracker` and `LyricsSwipeDownTracker`. The file is named after
the model; no type carries that exact name.

## UI & interaction

| Feature | Where |
| --- | --- |
| Liquid Glass UI | `ui/components/LiquidGlass.kt`, settings page with backdrop quality control |
| Custom history screen | `ui/screens/HistoryScreen.kt` |
| Bouncing overscroll | `ui/components/BouncingOverscroll.kt` |
| Mini-player gesture classification | `MiniPlayerGestureClassifier` (used by `GlassNavBar.kt`) |
| Haptic feedback components | `ui/components/Common.kt` and 14 files |
| Custom YZ Music icons | `YZMusicIcons` (14 files) |
| Explore screen | `ui/screens/ExploreScreen.kt` |
| Persistent list/grid toggle on the Recents shelf | home |

## Search

| Feature | Where |
| --- | --- |
| Custom search controller | `ui/screens/SearchScreen.kt` |
| Custom search state | `SearchState` (6 files) |
| Entity-based recents | search & discovery |

## Networking, sources & data

| Feature | Where |
| --- | --- |
| Qobuz and TIDAL custom modules | `data/sources/` (15 files) |
| Vercel-hosted sources index | module index URL, `MODULE_INDEX_URL` |
| KuGou source | source module with test coverage |
| Canvas artwork — Spotify, Apple Music, Tidal, Community | `data/canvas/` |
| Multi-source resolution and ranking | `data/sources/SourceResolver.kt` |

## Play Together (listening party)

| Feature | Where |
| --- | --- |
| Realtime party synchronization | `playback/PartySync.kt` |
| FastAPI backend server | bundled server |
| Default server `yzmusic-party.onrender.com` | party config |
| QR invites and Vercel share link | party UI, web invite client |
| Profile avatar from account photo | party settings |

## Branding & platform

| Feature | Where |
| --- | --- |
| YZ Music branding and identity | `YZMusicIcons`, custom logo, theme |
| Custom package and native namespace | `applicationId com.music.yzmusic`, `yzmusic::smart`, `com.music.yzmusic.data` |
| Material 3 theme and typography | theme layer |
| Localisation | 10 locales, 569 keys in the default locale |
| CI and automated releases | `.github/workflows/android.yml` |

## Testing

898 unit tests, including dedicated suites for `QueueInsights`,
`LoudnessBoost`, `NowPlayingNavigation`, autoplay, source selection, LRCLIB,
media tagging, KuGou, word sync, download sessions and queue reordering.

---

## Verification for 1.6.5

- 898 unit tests pass, 0 failures.
- `assembleProdRelease` and `assembleDevDebug` both BUILD SUCCESSFUL.
- The prod APK is R8-minified; every new symbol confirmed present via
  `mapping.txt`.
- The published artifact was re-downloaded from the release and installed over
  the existing 1.6.2 on an Android 16 device as a real upgrade: certificate
  `84a96f19…` matches, versionCode 13 → 14, launches with no crash.

## Known pre-existing issues, not fixed here

- `AppUpdateChecker.kt:130` writes updates to `bitchord-<version>.apk`. Visible
  to anyone downloading a release, so the filename is misleading.
- Lint reports 317 errors, all pre-existing: 276 `MissingTranslation` on
  `listen_together_*` and 36 media3 `UnsafeOptInUsageError`.
- `MissingSuperCall` in `LoudnessBoostProcessor.kt:142`.
- Lint `PropertyEscape` on `local.properties`.
- Three KDoc references to the removed `MergeBand`.