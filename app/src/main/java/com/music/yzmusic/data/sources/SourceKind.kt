package com.music.yzmusic.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose. **Declaration order here is the order sources
 * are preferred** - see `SourceRegistry.active()`, which sorts on
 * `kind.ordinal` - so a custom module comes before the built-in one, then
 * YouTube Music, then JioSaavn. Adding a source means adding a [MusicSource]
 * implementation and an entry here, which is the point - every protocol the app
 * speaks is one someone can read in this repo, and a source can't teach the app
 * a new way to behave after it ships.
 *
 * ## Being below YouTube is not being switched off
 *
 * A source ranked under YouTube Music is not merely less preferred — it is
 * unreachable by the substitution path, which only asks what is ranked
 * *above*. JioSaavn below YouTube is therefore reached by
 * `SourceResolver.fallbackForYouTube`, and only when the YouTube walk for that
 * track comes back with nothing. That is the whole meaning of "fallback" here:
 * the default answers, and this answers when it cannot.
 *
 * What varies per *instance* - which index, whose module - is [SourceConfig].
 */
enum class SourceKind(
    val label: String,
    val detail: String,
    /** The chips under the name on the sources screen. */
    val labels: List<String>,
    /** Whether an instance needs a URL before it can do anything — and so needs an editor. */
    val needsServer: Boolean,
    /** Whether this kind can serve bit-exact audio when asked. */
    val canServeLossless: Boolean,
    /**
     * Whether this kind answers quickly enough to be worth asking *before* a
     * track is played, so its copy can be pinned and cached ahead of time.
     *
     * Measured on this device, the gap is not close: JioSaavn answers a search
     * and hands back a stream URL in about 0.4s, while a module index takes
     * 7-13s to walk its backends — and read-ahead runs on the track *after* the
     * one playing, so a lookup that slow is usually still going when the
     * listener arrives. A wasted JioSaavn resolve costs one HTTP round trip; a
     * wasted module resolve costs a QuickJS engine, an index fetch and several
     * backend searches. The first is worth spending speculatively and the
     * second is not.
     *
     * False for [YOUTUBE] as well, though it *is* warmed ahead of time — that
     * happens through its own read-ahead in
     * [AudioCache][com.music.yzmusic.playback.AudioCache], which speaks video
     * ids directly and needs no cross-source match to find the track.
     */
    val worthPrefetching: Boolean = false,
) {
    /**
     * A module index the user pointed at themselves, tried ahead of the one
     * baked into the build.
     *
     * Same protocol as [MODULE] and served by the same [ModuleSource] — the
     * only thing this kind carries that the other doesn't is its place in the
     * order, which is what being declared first here decides. There is at most
     * one at a time; see [SourceRegistry.setCustomModule].
     */
    CUSTOM_MODULE(
        label = "Custom module",
        detail = "Your own compatible module index. Tried before the built-in one.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
    ),

    /**
     * A URL to a compatible module-index JSON.
     *
     * The index lists JS plugin descriptors; each plugin ships a JS file
     * that exports `searchTracks()` and `getTrackStreamUrl()`. The app
     * fetches the index, loads each plugin's JS into a QuickJS sandbox,
     * and calls those functions — the same mechanism Convx uses to support
     * services like Tidal, Qobuz, Apple Music, etc.
     *
     * The JS runs in a sandboxed QuickJS VM with no access to the Android
     * runtime, only to a wired-in `fetch()` implementation.
     */
    MODULE(
        label = "Module source",
        detail = "A URL to a compatible module index. Modules are JS plugins that " +
            "can search and stream from services like Tidal, Qobuz, Apple Music and more.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
    ),

    /**
     * The source the app was built on, listed here so it always has a fixed
     * place: third, behind the module sources and ahead of JioSaavn. It cannot
     * be removed - see [SourceRegistry]. Nothing else in the app can supply a
     * home feed, a radio station or a related-tracks queue, and it is the only
     * one whose catalogue matches what was searched for, which is why it is the
     * default rather than the thing everything else races to beat.
     */
    YOUTUBE(
        label = "YouTube Music",
        detail = "The full catalogue, at Opus up to about 171 kbps. Lossy - there is no " +
            "lossless rendition to ask for. The default: every other source is a " +
            "fallback for the cases this one cannot serve.",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        canServeLossless = false,
    ),

    /**
     * Higher bitrate than YouTube, reached only when YouTube cannot answer.
     *
     * Ranked below rather than above on purpose. Both of its virtues are real
     * and one of its costs is: its catalogue is broader and its matching is
     * looser, so a fallback that fires on a track YouTube simply lacks will
     * sometimes hand back a different recording under the right title. Better
     * than silence, not better than the default — which is why it is the
     * fallback and not the preference.
     */
    JIOSAAVN(
        label = "JioSaavn",
        detail = "High-quality streams up to 320kbps AAC/MP4 — better bitrate than " +
            "YouTube Music, but some results may be an unrelated recording. Used " +
            "only as a fallback, when YouTube Music cannot serve a track.",
        labels = listOf("High Quality", "320kbps", "Fallback"),
        needsServer = false,
        canServeLossless = false,
        worthPrefetching = true,
    ),
}
