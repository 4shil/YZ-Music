package com.music.yzmusic.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose. **Declaration order here is the order sources
 * are tried** — see `SourceRegistry.active()`, which sorts on `kind.ordinal` —
 * so a custom module comes before the built-in one, then JioSaavn, then
 * YouTube Music. Adding a source means adding a [MusicSource] implementation
 * and an entry here, which is the point — every protocol the app speaks is one
 * someone can read in this repo, and a source can't teach the app a new way to
 * behave after it ships.
 *
 * What varies per *instance* — which index, whose module — is [SourceConfig].
 */
enum class SourceKind(
    val label: String,
    val detail: String,
    /** The chips under the name on the sources screen. */
    val labels: List<String>,
    /** Whether an instance needs a URL before it can do anything — and so needs an editor. */
