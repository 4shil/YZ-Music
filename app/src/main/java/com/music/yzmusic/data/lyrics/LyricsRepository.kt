package com.music.yzmusic.data.lyrics

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Where the player gets its lyrics.
 *
 * Eight sources, tried in [order] — the user's own priority list in Settings,
 * defaulting to [LyricsSource.entries]:
 *
 *  - [BetterLyrics] and [PaxSenix] — Apple Music TTML, per-syllable, from two
 *    independent hosts so one having a bad day doesn't cost the timing.
 *  - [LyricsPlus] — the YouLy+ backend; finest timing of the lot, flakiest hosting.
 *  - [SimpMusicLyrics] — keyed on the video id, so it can't fetch the wrong edit.
 *  - [LrcLib], [Musixmatch], [KuGou] — line-synced only, but between them
 *    almost always up, and [KuGou] carries a lot that the others don't.
 *
 * Every enabled source is asked *at the same time*, but their answers are
 * taken in [order]: the loop awaits them one at a time in that sequence, so a
 * lower-priority source finishing first never preempts one still pending
 * ahead of it. Asked one after another instead, a miss on each source would
 * cost its own round trip before the next was even tried, and a track with no
 * lyrics anywhere would spend the best part of a minute finding that out with
 * eight of them. Run together, a miss costs whatever the slowest one needed
 * to still be waited on took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist.
 */
object LyricsRepository {

    /**
     * Lyrics, which source they turned out to come from, and the alternate
     * renderings that source happened to bring with them.
     *
     * The alternates ride along on the original's timing and are null whenever
     * the source had none, so "not available" is something the player can
     * answer honestly instead of showing an empty layer.
     */
    data class Result(
        val source: LyricsSource,
        val lines: List<LyricLine>,
        val translation: List<LyricLine>? = null,
        val romanization: List<LyricLine>? = null,
    )

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off. [order] is tried first-to-last; a source
     * missing from it (an upgrade that added one after the order was last
     * saved) falls in after everything named, in [LyricsSource]'s own order.
     *
     * [prioritizeSyllableSync] decides what happens once *something* has come
     * back: off, the highest-priority source's own answer is taken as-is,
     * word-synced or not — priority is priority, and second-guessing it with
     * more network calls after it has already answered is not what "first"
     * was supposed to mean. On, a merely line-synced answer is kept only as a
     * fallback, and the search keeps going through the rest of [order] for a
     * word-synced one, taking the top-priority source that has one.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        order: List<LyricsSource> = LyricsSource.entries,
        prioritizeSyllableSync: Boolean = false,
    ): Result? = coroutineScope {
        LyricsLog.clear()
        LyricsLog.i("Repository", "Looking up lyrics for \"$title\" by \"$artist\" (${durationMs / 1000}s)")

        val sequence = order.filter { it in sources } +
            LyricsSource.entries.filter { it in sources && it !in order }

        LyricsLog.i("Repository", "Active sources order: ${sequence.joinToString { it.label }}")

        // Genius is a plain text web scraper. To preserve bandwidth and avoid rate-limiting,
        // it starts lazily and is only contacted if all higher-priority synced sources miss.
        val racing: List<Pair<LyricsSource, Deferred<LyricsPayload?>>> = sequence.map { source ->
            val startMode = if (source == LyricsSource.GENIUS) kotlinx.coroutines.CoroutineStart.LAZY else kotlinx.coroutines.CoroutineStart.DEFAULT
            source to async(Dispatchers.IO, start = startMode) {
                fetch(source, videoId, title, artist, durationMs, album)
            }
        }

        try {
            var lineSynced: Result? = null
            for ((source, job) in racing) {
                // If we already found a line-synced or better result, skip Genius completely
                if (lineSynced != null && source == LyricsSource.GENIUS) {
                    LyricsLog.i("Repository", "Skipping Genius fallback because higher-priority source answered")
                    continue
                }

                if (source == LyricsSource.GENIUS && lineSynced == null) {
                    LyricsLog.w("Repository", "All synced providers missed. Running Genius fallback...")
                }

                val found = runCatching { job.await() }.getOrNull() ?: continue
                val lines = found.lines
                if (lines.any { it.isWordSynced }) {
                    LyricsLog.s("Repository", "Word-synced match from ${source.label}")
                    return@coroutineScope result(source, found)
                }
                if (!prioritizeSyllableSync && lines.any { it.timeMs > 0 }) {
                    LyricsLog.s("Repository", "Line-synced match from ${source.label}")
                    return@coroutineScope result(source, found)
                }
                if (lineSynced == null) lineSynced = result(source, found)
            }
            lineSynced
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): LyricsPayload? {
        LyricsLog.i(source.label, "Querying $source...")
        val found = when (source) {
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album)?.let(::LyricsPayload)
            LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)?.let(::LyricsPayload)
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)?.let(::LyricsPayload)
            LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)?.let(::LyricsPayload)
            LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
            LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)?.let(::LyricsPayload)
            LyricsSource.GENIUS -> Genius.lyrics(title, artist)?.let(::LyricsPayload)
        }
        if (found == null || found.lines.isEmpty()) {
            LyricsLog.w(source.label, "No lyrics returned")
        } else {
            val syncType = when {
                found.lines.any { it.isWordSynced } -> "word-synced"
                found.lines.any { it.timeMs > 0 } -> "line-synced"
                else -> "plain text"
            }
            LyricsLog.s(source.label, "Returned ${found.lines.size} lines ($syncType)")
            if (found.hasAlternates) {
                val extras = listOfNotNull(
                    "a translation".takeIf { found.translation != null },
                    "a romanization".takeIf { found.romanization != null },
                )
                LyricsLog.i(source.label, "  carrying ${extras.joinToString(" and ")}")
            }
        }
        return found
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     *
     * The alternates get the same treatment for the opposite reason: they have
     * to end up the *same length* as the original, or swapping one for the
     * other would change how many rows the list has and drop the reader's
     * place. Instrumental breaks are part of that shape, so both sides get
     * them. Their own text is never split for a background vocal — a
     * translated "(ooh)" is a parenthesis in whatever language it is now.
     */
    private fun result(source: LyricsSource, found: LyricsPayload) = Result(
        source = source,
        lines = found.lines.withBackgroundVocals(),
        translation = found.translation?.withInstrumentalGaps(),
        romanization = found.romanization?.withInstrumentalGaps(),
    )
}
