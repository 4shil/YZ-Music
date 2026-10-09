package com.music.yzmusic.ui.player.queue

import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.stats.ArtistFacts

/**
 * What a queue tag filters by.
 *
 * Only axes that can be computed from fields a [Song] actually carries are
 * here. Mood/flow is not a YouTube field — it is inferred locally from the
 * queue's own titles and artists the way YT Music derives "Relax/Chill" vs
 * "Energize" per queue, not from a constant list.
 */
enum class QueueTagAxis {
    /** The station/mood the track was queued from, where the row has one. */
    MOOD,

    /** The performing artist. */
    ARTIST,

    /** The album the track belongs to, where the row has one. */
    ALBUM,

    /** Whether the row is marked explicit/clean. */
    EXPLICIT,

    /** What the chosen source reports it can serve the track at. */
    QUALITY,

    /** Genre from Last.fm ArtistFacts — cached per artist, queue-derived. */
    GENRE,

    /** Queue-local mood/flow buckets inferred from titles/artists (e.g. Chill, Energize). */
    MOOD_FLOW,
}

/** One selectable tag, and how many of the queue's songs it covers. */
data class QueueTag(
    val axis: QueueTagAxis,
    val label: String,
    val count: Int,
)

/** Every tag of one axis, in the order they are offered. */
data class QueueTagGroup(
    val axis: QueueTagAxis,
    val tags: List<QueueTag>,
)

/**
 * An artist/album axis with a chip per value is only a filter while the queue is
 * made of a few of them; past this it is a wall of one-song chips that
 * describes nothing. Above it the axis is simply not offered.
 */
private const val MAX_ARTIST_AXIS = 8
private const val MAX_ALBUM_AXIS = 8
private const val MAX_GENRE_AXIS = 8
private const val MAX_KEYWORD_TAGS = 5

/** The tag every queue starts on, and the one that filters nothing away. */
const val ALL_TAGS_LABEL = "All"

/**
 * Tags describing the songs *in the queue*, YouTube-Music style.
 *
 * Fixed axes (MOOD/ARTIST/ALBUM/EXPLICIT/QUALITY) come from the row fields
 * directly; GENRE is ArtistFacts when available with a title/artist heuristic
 * fallback so offline queues still have genre chips; MOOD_FLOW is synthesized
 * from the queue's own content plus a keyword fallback so that every
 * non-trivial queue still has something musical to filter on even when the
 * fixed fields are empty — the way YT Music always has a chip row.
 *
 * Deterministic by construction: axes come out in [QueueTagAxis] order and
 * tags within an axis sort by count descending then label, so the same queue
 * always offers the same chips in the same order however many times it is
 * recomposed. An axis that has nothing to say is left out rather than shown
 * empty.
 */
fun computeQueueTags(queue: List<Song>): List<QueueTagGroup> {
    if (queue.isEmpty()) return emptyList()

    val groups = ArrayList<QueueTagGroup>(QueueTagAxis.values().size)

    for (axis in QueueTagAxis.values()) {
        if (axis == QueueTagAxis.GENRE || axis == QueueTagAxis.MOOD_FLOW) continue
        val counts = LinkedHashMap<String, Int>()
        for (song in queue) {
            val label = labelFor(song, axis) ?: continue
            counts[label] = (counts[label] ?: 0) + 1
        }
        when (axis) {
            QueueTagAxis.ARTIST ->
                if (counts.isEmpty() || counts.size > MAX_ARTIST_AXIS) continue
            QueueTagAxis.ALBUM ->
                if (counts.isEmpty() || counts.size > MAX_ALBUM_AXIS) continue
            else -> if (counts.isEmpty()) continue
        }
        groups += QueueTagGroup(axis, orderTags(axis, counts))
    }

    // ——— Genre — ArtistFacts when available, heuristic fallback otherwise ———
    // Queue-derived genres so different queues surface different chips, like YT Music.
    // Only the in-memory cache is read here — no network on the UI thread.
    // Background enrichment happens via ArtistFacts.noticed() in InlineQueue.
    val genreCounts = LinkedHashMap<String, Int>()
    for (song in queue) {
        val cached = if (ArtistFacts.genresAvailable) ArtistFacts.genresFor(song.artist) else emptyList()
        val genres = if (cached.isNotEmpty()) cached else heuristicGenresFor(song)
        for (g in genres) {
            genreCounts[g] = (genreCounts[g] ?: 0) + 1
        }
    }
    if (genreCounts.isNotEmpty()) {
        val orderedGenres = genreCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .take(MAX_GENRE_AXIS)
            .map { (label, count) -> QueueTag(axis = QueueTagAxis.GENRE, label = label, count = count) }
        if (orderedGenres.any { it.count in 1 until queue.size }) {
            groups += QueueTagGroup(QueueTagAxis.GENRE, orderedGenres)
        } else if (orderedGenres.size >= 2) {
            // Homogenous genre queue (e.g. 4 Punjabi tracks) still benefits from the chips
            // as a descriptor; emit top 2 so the row is never empty for genre-homogenous queues.
            groups += QueueTagGroup(QueueTagAxis.GENRE, orderedGenres.take(2))
        }
    }

    // ——— Music-aware mood/flow, derived per-queue like YT Music ———
    // Each bucket is a predicate on the row itself (title/artist/duration).
    // Only buckets that split the queue are emitted, ordered most-covering first.
    val moodCounts = LinkedHashMap<String, Int>()
    for ((label, pred) in MOOD_FLOW_PREDICATES) {
        val c = queue.count(pred)
        if (c > 0 && c < queue.size) moodCounts[label] = c
    }
    if (moodCounts.isNotEmpty()) {
        val ordered = moodCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            .map { (label, count) -> QueueTag(axis = QueueTagAxis.MOOD_FLOW, label = label, count = count) }
        groups += QueueTagGroup(QueueTagAxis.MOOD_FLOW, ordered)
    }

    // ——— Keyword fallback — guarantees 10+ when heuristics are sparse ———
    // For titles in any language that share a romanised word (Dil, Love, Remix),
    // surface the top words so a Punjabi/Hindi queue still gets per-queue chips.
    val flatChipCount = groups.flatMap { it.tags }.size
    if (flatChipCount < 10) {
        val keywordCounts = keywordCounts(queue)
        if (keywordCounts.isNotEmpty()) {
            val existingLabels = groups.flatMap { it.tags }.map { it.label.lowercase() }.toSet() + moodCounts.keys.map { it.lowercase() }.toSet()
            val keywordTags = keywordCounts.entries
                .filter { it.key.lowercase() !in existingLabels }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .take(MAX_KEYWORD_TAGS)
                .map { (label, count) -> QueueTag(axis = QueueTagAxis.MOOD_FLOW, label = label, count = count) }
            if (keywordTags.isNotEmpty()) {
                // Merge with existing MOOD_FLOW or add new group
                val existingMoodIdx = groups.indexOfFirst { it.axis == QueueTagAxis.MOOD_FLOW }
                if (existingMoodIdx >= 0) {
                    val merged = (groups[existingMoodIdx].tags + keywordTags)
                        .distinctBy { it.label.lowercase() }
                    groups[existingMoodIdx] = QueueTagGroup(QueueTagAxis.MOOD_FLOW, merged)
                } else {
                    groups += QueueTagGroup(QueueTagAxis.MOOD_FLOW, keywordTags)
                }
            }
        }
    }

    return groups
}

private fun orderTags(axis: QueueTagAxis, counts: Map<String, Int>): List<QueueTag> =
    counts.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
        )
        .map { (label, count) -> QueueTag(axis = axis, label = label, count = count) }

/** The display label for a song on one axis, or null when it has none. */
private fun labelFor(song: Song, axis: QueueTagAxis): String? = when (axis) {
    QueueTagAxis.MOOD -> song.radioName?.trim()?.takeIf { it.isNotEmpty() }
    QueueTagAxis.ARTIST -> song.artist.trim().takeIf { it.isNotEmpty() }
    QueueTagAxis.ALBUM -> song.albumName?.trim()?.takeIf { it.isNotEmpty() }
    QueueTagAxis.QUALITY -> qualityLabel(song.sourceQuality)
    QueueTagAxis.EXPLICIT -> when (song.isExplicit) {
        true -> "Explicit"
        false -> "Clean"
        null -> null
    }
    QueueTagAxis.GENRE -> null // matched via ArtistFacts/heuristic, not a single field
    QueueTagAxis.MOOD_FLOW -> null // matched via predicate table, not a single field
}

private fun qualityLabel(raw: String?): String? = when (raw?.trim()?.uppercase()) {
    "LOSSLESS" -> "Lossless"
    "HIGH" -> "High"
    "LOW" -> "Low"
    else -> null
}

// ——— Heuristic genre fallback — so offline queue still gets genre chips ———
private val HEURISTIC_GENRE_PATTERNS: List<Pair<String, String>> = listOf(
    "lofi" to "Lo-Fi", "lo fi" to "Lo-Fi", "chillhop" to "Lo-Fi",
    "phonk" to "Phonk", "hyperpop" to "Hyperpop", "drift" to "Phonk",
    "trap" to "Trap", "drill" to "Drill", "grime" to "Grime",
    "jungle" to "Jungle", "garage" to "Garage", "dnb" to "Drum & Bass", "drum and bass" to "Drum & Bass",
    "chillout" to "Chillout", "downtempo" to "Downtempo", "ambient" to "Ambient",
    "bollywood" to "Bollywood", "punjabi" to "Punjabi", "desi" to "Desi", "bhangra" to "Bhangra",
    "hip hop" to "Hip-Hop", "hiphop" to "Hip-Hop", "rap" to "Rap",
    "edm" to "EDM", "house" to "House", "techno" to "Techno", "trance" to "Trance",
    "r&b" to "R&B", "randb" to "R&B", "soul" to "Soul", "jazz" to "Jazz",
    "rock" to "Rock", "pop" to "Pop", "indie" to "Indie", "metal" to "Metal",
    "acoustic" to "Acoustic", "instrumental" to "Instrumental", "classical" to "Classical",
)

private fun heuristicGenresFor(song: Song): List<String> {
    val hay = (song.title + " " + song.artist).lowercase()
    val out = LinkedHashSet<String>()
    for ((needle, label) in HEURISTIC_GENRE_PATTERNS) {
        if (needle in hay) out += label
    }
    return out.toList().take(2)
}

private fun keywordCounts(queue: List<Song>): Map<String, Int> {
    val stop = setOf("the","and","you","for","with","from","this","that","your","are","was","have","has","will","remix","official","video","feat","ft","lyrics","audio","song","music")
    val counts = LinkedHashMap<String, Int>()
    for (song in queue) {
        // words per title, distinct per song so count = songs containing word
        val words = song.title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in stop }
            .toSet()
        for (w in words) {
            // pretty label: capitalised
            val label = w.replaceFirstChar { it.uppercaseChar() }
            counts[label] = (counts[label] ?: 0) + 1
        }
    }
    // Include also album/artist words that split queue, but title is primary
    return counts.filter { it.value in 1 until queue.size }
}

// ——— Mood/flow lexicon — YT-Music-like, queue-derived ———
// Each entry is (chip label, predicate on Song). Keep labels short, YT-style.
// Added duration/origin buckets and late-night/drive variants so a 4-song queue
// still splits across 5-8 chips with only title/artist.

private val MOOD_FLOW_PREDICATES: List<Pair<String, (Song) -> Boolean>> = listOf(
    "Chill" to { s -> s.title.containsAny("chill", "lofi", "lo fi", "acoustic", "calm", "sleep", "soft") || s.artist.containsAny("chill", "lofi") },
    "Energize" to { s -> s.title.containsAny("phonk", "drift", "edm", "trap", "drill", "rage", "hype", "gym") || s.artist.containsAny("phonk", "trap") },
    "Romance" to { s -> s.title.containsAny("love", "romance", "romantic", "dil", "sanam", "ishq", "jaan", "pyaar", "mohabbat") },
    "Sad" to { s -> s.title.containsAny("sad", "lonely", "alone", "tears", "broken", "hurt", "udaas", "dukhi", "rula") },
    "Party" to { s -> s.title.containsAny("party", "dance", "club", "celebrat") },
    "Focus" to { s -> s.title.containsAny("focus", "study", "instrumental", "piano") && !s.title.containsAny("party", "dance") },
    "Happy" to { s -> s.title.containsAny("happy", "joy", "smile", "masti", "khushi", "sunny", "feel good") },
    "Late Night" to { s -> s.title.containsAny("night", "midnight", "raat", "neend", "dream", "sleep") },
    "Drive" to { s -> s.title.containsAny("drive", "road", "highway", "drift") },
    "Workout" to { s -> s.title.containsAny("workout", "gym", "run", "pump", "energy") },
    "Acoustic" to { s -> s.title.containsAny("acoustic", "unplugged", "stripped") },
    "Instrumental" to { s -> s.title.containsAny("instrumental", "beats", "piano solo") },
    "Live" to { s -> s.title.contains("live", ignoreCase = true) },
    "Remix" to { s -> s.title.contains("remix", ignoreCase = true) },
    "Short" to { s -> (parseDurationText(s.durationText) ?: Int.MAX_VALUE) < 150 },
    "Long" to { s -> (parseDurationText(s.durationText) ?: 0) > 300 },
    "Singles" to { s -> s.albumName.isNullOrBlank() },
    "Video" to { s -> s.isVideo },
)

private fun String.containsAny(vararg needles: String): Boolean {
    val lower = lowercase()
    return needles.any { it.lowercase() in lower }
}

/** Whether [song] is one of the songs [tag] covers. */
fun songMatchesTag(song: Song, tag: QueueTag): Boolean {
    if (tag.axis == QueueTagAxis.GENRE) return songMatchesGenreTag(song, tag.label)
    if (tag.axis == QueueTagAxis.MOOD_FLOW) return songMatchesMoodFlowTag(song, tag.label)
    return labelFor(song, tag.axis) == tag.label
}

private fun songMatchesGenreTag(song: Song, label: String): Boolean {
    val cached = if (ArtistFacts.genresAvailable) ArtistFacts.genresFor(song.artist) else emptyList()
    if (label in cached) return true
    return label in heuristicGenresFor(song)
}

private fun songMatchesMoodFlowTag(song: Song, label: String): Boolean {
    val pred = MOOD_FLOW_PREDICATES.firstOrNull { it.first.equals(label, ignoreCase = true) }?.second
    if (pred != null) return pred(song)
    // Keyword fallback: label is a word from titles
    return song.title.contains(label, ignoreCase = true) || song.artist.contains(label, ignoreCase = true) || song.albumName?.contains(label, ignoreCase = true) == true
}

fun songMatchesAnyTag(song: Song, tags: Collection<QueueTag>): Boolean =
    tags.isEmpty() || tags.any { songMatchesTag(song, it) }

/**
 * Whether [song] carries every word of [query] in its title, artist or album.
 *
 * The whole query is split on whitespace and all of it has to be found, so
 * two words narrow the queue rather than widening it. Words are compared
 * without regard to case, and a blank query matches everything — which is
 * what makes clearing the box restore the unfiltered queue exactly.
 */
fun songMatchesQuery(song: Song, query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val haystack = buildString {
        append(song.title)
        append(' ')
        append(song.artist)
        append(' ')
        append(song.albumName.orEmpty())
    }.lowercase()
    return trimmed.lowercase().split(Regex("\\s+")).all { it in haystack }
}

/**
 * How much the queue adds up to.
 *
 * [totalDurationSeconds] is null when no row carried a duration this could
 * read, rather than zero: "no idea" and "no time at all" are different
 * answers and only one of them is true of a queue of live streams.
 */
data class QueueStats(
    val songCount: Int,
    val totalDurationSeconds: Int?,
    val artistCount: Int,
    val albumCount: Int,
)

fun computeQueueStats(queue: List<Song>): QueueStats {
    var total = 0
    var timed = 0
    val artists = HashSet<String>()
    val albums = HashSet<String>()
    for (song in queue) {
        val seconds = parseDurationText(song.durationText)
        if (seconds != null) {
            total += seconds
            timed++
        }
        song.artist.trim().takeIf { it.isNotEmpty() }?.let(artists::add)
        song.albumName?.trim()?.takeIf { it.isNotEmpty() }?.let(albums::add)
    }
    return QueueStats(
        songCount = queue.size,
        totalDurationSeconds = if (timed == 0) null else total,
        artistCount = artists.size,
        albumCount = albums.size,
    )
}

/**
 * Reads the "M:SS" a row was built with, and the "H:MM:SS" a long one.
 *
 * Returns null for anything else, so a row carrying a localised or otherwise
 * unreadable duration contributes nothing to the total rather than a wrong
 * amount of time.
 */
fun parseDurationText(text: String?): Int? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(':')
    if (parts.size !in 2..3) return null
    var total = 0
    for (part in parts) {
        val value = part.trim().toIntOrNull() ?: return null
        if (value < 0) return null
        total = total * 60 + value
    }
    return total
}

/** "1h 42m", or null when there is no duration to show. */
fun formatQueueDuration(totalSeconds: Int?): String? {
    if (totalSeconds == null || totalSeconds <= 0) return null
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

/** The whole statistics line, e.g. "24 songs · 1h 42m · 8 artists · 12 albums". */
fun formatQueueStats(stats: QueueStats): String {
    val parts = ArrayList<String>(4)
    parts += if (stats.songCount == 1) "1 song" else "${stats.songCount} songs"
    formatQueueDuration(stats.totalDurationSeconds)?.let { parts += it }
    if (stats.artistCount > 0) {
        parts += if (stats.artistCount == 1) "1 artist" else "${stats.artistCount} artists"
    }
    if (stats.albumCount > 0) {
        parts += if (stats.albumCount == 1) "1 album" else "${stats.albumCount} albums"
    }
    return parts.joinToString(" · ")
}

// ───────── Tag → search query → fetched queue ─────────
// Display-only: never mutates PlaybackService's queue.

private val MOOD_QUERY: Map<String, String> = mapOf(
    "Chill" to "chill lofi acoustic",
    "Energize" to "phonk trap edm hype",
    "Romance" to "romantic love punjabi",
    "Sad" to "sad lonely broken",
    "Party" to "party dance club mix",
    "Focus" to "focus study instrumental piano",
    "Happy" to "happy feel good",
    "Late Night" to "late night chill",
    "Drive" to "drive road drift",
    "Workout" to "workout gym pump",
    "Acoustic" to "acoustic unplugged",
    "Instrumental" to "instrumental beats",
    "Live" to "live performance",
    "Remix" to "remix",
    "Short" to "short song",
    "Long" to "long song",
    "Singles" to "single",
    "Video" to "music video",
)

/**
 * Query to run when a tag is tapped.
 *
 * MOOD_FLOW uses a music-aware expansion so "Romance" surfaces romance mixes
 * rather than literally every title containing the word love. ARTIST/GENRE
 * use the chip label itself. ALBUM/EXPLICIT/QUALITY return null — they have
 * no meaningful catalog to fetch.
 */
fun tagSearchQuery(tag: QueueTag): String? = when (tag.axis) {
    QueueTagAxis.MOOD_FLOW -> MOOD_QUERY[tag.label] ?: tag.label
    QueueTagAxis.GENRE, QueueTagAxis.ARTIST, QueueTagAxis.MOOD -> tag.label
    else -> null
}

/**
 * Fetches ~25 songs matching [tag] without mutating the playing queue.
 *
 * Returns empty on error so the caller can fall back to local filtering.
 * Kept in this module so [QueueInsights] owns everything about tags.
 */
suspend fun loadForTag(tag: QueueTag, queue: List<Song>): List<Song> {
    val query = tagSearchQuery(tag) ?: return emptyList()
    val seen = queue.map { it.videoId }.toSet()
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrDefault(emptyList())
        .filterIsInstance<SearchResult.Track>()
        .map { it.song }
        .distinctBy { it.videoId }
        .filter { it.videoId !in seen }
        .take(25)
}
