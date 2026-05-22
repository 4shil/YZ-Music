package com.music.yzmusic.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.yzmusic.data.DebugLog as Log
import androidx.annotation.RequiresApi
import com.music.yzmusic.data.model.Song
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Where a downloaded track goes, and how it gets there.
 *
 * The destination is the device's own Music folder, in a `YZ Music`
 * subfolder — somewhere the file manager lists, other players can open, and a
 * user can back up or delete without going through this app. That choice is
 * what makes this class necessary at all: an app-private directory would be
 * four lines of [File], but a shared one crosses the scoped-storage line and
 * the two sides of that line have nothing in common.
 *
 * It goes through the audio collection rather than Downloads because that is
 * where audio belongs and where every other player on the device looks. What
 * first ruled Downloads out was narrower and is worth keeping on the record:
 * the files were `.webm` then — a container extension Android's own mime table
 * ties to video regardless of what MIME type this class declares for it — and a
 * Gallery app crawling Downloads for video-looking files does not care what a
 * column says otherwise. Nothing writes `.webm` any more (see [storable] and
 * `StreamResolver.resolveForDownload`), so that particular trap is behind us;
 * the conclusion it led to is still the right one.
 *
 *  - **API 29+** goes through [MediaStore]. There is no filesystem path to
 *    write to; the store mints a row, hands back a content uri, and the file
 *    exists at a location it chooses. `IS_PENDING` keeps the row invisible to
 *    everything else until the bytes are all there, so a cancelled download is
 *    never a half-file somebody can find and play.
 *  - **API 26–28** is a real path and a runtime permission. The file is written
 *    beside its final name with a `.part` suffix and renamed on completion,
 *    which is the same guarantee `IS_PENDING` gives for free above, and the
 *    media scanner is told afterwards or the file stays invisible to everything
 *    that reads the index rather than the disk.
 *
 * Neither side writes tags — this class only ever copies the bytes the server
 * on the other end sent. [MediaTagger] rewrites the finished file afterwards to
 * add them; the filename below is what every downloaded track carries
 * regardless of whether that rewrite finds a layout it recognises.
 */
object DownloadStore {

    private const val TAG = "YZ Music"

    /** The subfolder of Music that everything lands in. */
    const val FOLDER = "YZ Music"

    private val relativePath = "${Environment.DIRECTORY_MUSIC}/$FOLDER"

    /**
     * Whether saving needs `WRITE_EXTERNAL_STORAGE` asked for at runtime.
     *
     * Only below API 29. From there on the app writes through the media store,
     * which grants access to rows it created and needs no permission for them —
     * and the permission it would ask for isn't grantable anyway.
     *
     * Every version check in this file is written out inline rather than
     * routed through this, deliberately: lint reads an inline `SDK_INT`
     * comparison as a guard around the API-29 calls beside it and does not
     * read a boolean property the same way, so hiding the check behind a name
     * costs a `NewApi` error on the release build.
     */
    fun needsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    // ---- Naming -------------------------------------------------------------

    /**
     * What the file is called: `Artist - Title.ext`.
     *
     * Artist first because a Music folder is sorted by name and nothing
     * else — no tags to group by — so leading with the artist is the only thing
     * that puts an album back together in the listing.
     */
    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    /**
     * Everything a FAT32 volume, the media store or a shell would each object
     * to for its own reasons, plus the whitespace that survives them.
     */
    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Long enough for anything real, short of the 255-byte filename ceiling. */
    private const val MAX_STEM_CHARS = 120

    /** What a file of some codec is called and what the store is told it is. */
    class Storable(val extension: String, val mimeType: String)

    /**
     * How to file a track of [codec], or null if this device won't have it.
     *
     * A source that can serve lossless does not thereby serve something Android
     * will keep: the media store's audio collection accepts a closed list of
     * MIME types, and one it doesn't recognise is refused outright at [begin] —
     * which is a download that cannot start rather than one that sounds worse
     * than hoped. Anything not answered for here falls the caller back to
     * YouTube's AAC, so an unfamiliar codec costs quality and not the download.
     *
     * Kept as a table rather than derived from the codec string because two of
     * these are not the identity mapping they look like. WAV's registered type
     * is `audio/x-wav` on Android, and ALAC ships inside an MP4 container, so an
     * ALAC file is an `.m4a` as far as both the store and [Mp4Tagger] are
     * concerned — the tagger works on the box tree and never asks what the
     * samples inside are.
     */
