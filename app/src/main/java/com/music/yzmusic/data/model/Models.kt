package com.music.yzmusic.data.model

/** A playable YouTube Music track. */
data class Song(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationText: String? = null,
    /** Browse ids lifted from the row, used by the long-press actions. */
    val artistId: String? = null,
    val albumId: String? = null,
    /** Names the album page header, which [albumId] alone can't. */
    val albumName: String? = null,
    /** A music-video upload rather than the catalogue track. */
    val isVideo: Boolean = false,
    /**
     * This track's identity *within one playlist*, which is not its [videoId]:
     * the same song added twice is two entries with two set-video-ids, and
     * removing one of them is only expressible in those terms. Present only on
     * rows parsed from a playlist page, which is the only place a removal can
     * be asked for from.
     */
    val setVideoId: String? = null,
    /**
     * Queued by AutoPlay or by a station's own mix rather than asked for — the
     * player groups these under the AutoPlay heading and keeps them at the
     * bottom of the queue, below anything the user picked.
     */
    val fromAutoplay: Boolean = false,
    /**
     * Explicit content or file URI for local device tracks or downloaded audio.
     */
    val localUri: String? = null,
    /**
     * Real filesystem path backing [localUri], when MediaStore exposes one.
     * Lets playback swap a content:// row for a raw file:// path on formats
     * that need it — see [com.music.yzmusic.playback.toMediaItem].
     */
    val localPath: String? = null,
    /**
     * What a non-YouTube source says it can serve this recording at, as one of
     * `LOSSLESS`, `HIGH` or `LOW` — null for every row that didn't come from
     * one.
     *
     * Carried on the row rather than discovered at stream time because it is
     * the only thing that distinguishes two catalogues holding the same track,
     * and the choice between them has to be made *before* either is asked for
     * a URL. Without it the picker was blind: a Deezer row and a 16-bit FLAC
     * row looked identical, the FLAC lost a tie-break on artist spelling, and
     * the track played as a 128kbps MP3.
     */
    val sourceQuality: String? = null,
)

/**
 * Artwork at a given pixel size.
 *
 * YouTube serves every size from one URL via a `w<n>-h<n>` hint, so the size
 * an image is fetched at is the caller's to choose, and worth choosing in both
 * directions. Up: the size YouTube advertises is far short of what a
 * full-screen player draws, and the source images run to about 1400px, so
 * asking for more is free and sharper. Down: a row thumbnail left at the
 * advertised size costs an order of magnitude more bytes than the square it
 * fills — 84kB against 7.8kB, measured on the same cover.
 *
 * Video thumbnails carry no hint and are returned unchanged.
 */
fun Song.artworkAt(px: Int): String? = thumbnailUrl.artworkAt(px)

/**
 * [Song.durationText] in milliseconds, or 0 when the row didn't state one.
 *
 * A row's duration is a display string — YouTube sends `"3:45"`, not a
 * number — and anything that has to *reason* about the length rather than draw
 * it needs it back as a quantity. Lyrics matching is the case that forced this
 * out into the open: LRCLIB keys its exact lookup on the track's length, and
 * falls back to whichever fuzzy hit is closest to it, so a duration of zero
 * doesn't miss — it silently matches the shortest edit of the song in the
 * database and hands back timings for a different recording.
 *
 * Zero is the answer for anything that isn't a duration, including null, so a
 * caller has one thing to check rather than a nullable *and* a range.
 */
fun Song.durationMillis(): Long = durationText.durationMillis()

/** As [Song.durationMillis], for a `M:SS` or `H:MM:SS` string on its own. */
fun String?.durationMillis(): Long {
    val parts = this?.trim()?.takeIf { it.isNotEmpty() }?.split(":") ?: return 0L
    val numbers = parts.map { it.trim().toLongOrNull() ?: return 0L }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        3 -> numbers[0] * 3_600 + numbers[1] * 60 + numbers[2]
        else -> return 0L
    }
    return (seconds * 1_000).coerceAtLeast(0L)
}

/** As [Song.artworkAt], for artwork that isn't a track's. */
fun String?.artworkAt(px: Int): String? = this?.replace(SIZE_HINT, "w$px-h$px")

private val SIZE_HINT = Regex("""w\d+-h\d+""")

/**
 * Artwork for a list row — 52dp at most, so about 140px on a 3x screen.
 * Rounded up, and one value for every row in the app rather than one per
 * row height, so they share a cache entry instead of each fetching its own.
 */
const val ROW_ART_PX = 160

/** Artwork for a shelf card: 166dp wide, so a little under 450px at 3x. */
const val CARD_ART_PX = 480

/** Artwork for a page header, drawn near enough full width. */
const val HEADER_ART_PX = 720

/**
 * Artwork handed to the media session — the lock screen, the notification,
 * Android Auto. Generous because those surfaces draw it large and take one
 * copy: unlike a list row, nothing goes back for a better one later.
 */
const val NOTIFICATION_ART_PX = 544

enum class BrowseType { ALBUM, ARTIST, PLAYLIST, OTHER }

/** A non-track search result: album, artist or playlist. */
data class BrowseItem(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
)

/** Search rows are heterogeneous once filters other than "Songs" are used. */
sealed interface SearchResult {
    data class Track(val song: Song) : SearchResult
    data class Browse(val item: BrowseItem) : SearchResult
}

enum class SearchFilter(val label: String, val params: String?) {
    SONGS("Songs", "EgWKAQIIAWoKEAkQChAFEAMQBA=="),
    ALBUMS("Albums", "EgWKAQIYAWoKEAkQChAFEAMQBA=="),
    ARTISTS("Artists", "EgWKAQIgAWoKEAkQChAFEAMQBA=="),
    PLAYLISTS("Playlists", "EgWKAQIoAWoKEAkQChAFEAMQBA=="),
}

/** A card in a home-feed carousel: either a track (videoId) or an album/playlist (browseId). */
data class ShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val videoId: String?,
    val browseId: String?,
)

/** The signed-in Google account, as YouTube Music reports it. */
data class Account(
    val name: String,
    val email: String,
    val thumbnailUrl: String?,
)

data class HomeShelf(
    val title: String,
    val items: List<ShelfItem>,
    /** YouTube's "strapline" — the grey line Apple Music runs under a heading. */
    val subtitle: String = "",
)

/** A page of the Home feed, plus the token for the next one — null once exhausted. */
data class HomeFeed(
    val shelves: List<HomeShelf>,
    val continuation: String?,
)

/**
 * The signed-in library, as YouTube Music splits it: the auto-generated Liked
 * Music playlist, the tracks explicitly added to the library, and a shelf per
 * saved collection (playlists, albums, artists, subscriptions, podcasts).
 */
data class LibraryPage(
    val likedSongs: List<Song>,
    val librarySongs: List<Song>,
    val shelves: List<HomeShelf>,
) {
    val isEmpty: Boolean
        get() = likedSongs.isEmpty() && librarySongs.isEmpty() && shelves.isEmpty()
}

/** A browsed album / artist / playlist page. */
data class DetailPage(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val songs: UiState<List<Song>>,
    val type: BrowseType = BrowseType.OTHER,
    /** Albums / singles carousels, populated for artist pages. */
