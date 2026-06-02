package com.music.yzmusic.playback.smart

import android.content.ContentResolver
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.IOException
import java.util.Locale

/**
 * A reader over a track that is already on the device, for [TrackAnalyzer].
 *
 * ## Why this exists
 *
 * Everything else the analyzer reads comes out of
 * [com.music.yzmusic.playback.AudioCache], because that is where *streamed*
 * audio lands. A track playing off the device never goes there:
 * [com.music.yzmusic.playback.AudioCache.playbackFactory] routes `file://` and
 * `content://` straight past the disk cache rather than writing a second copy
 * of a file the user already has.
 *
 * So the whole rendition apparatus the analyzer is built on answered "nothing
 * cached" for a track that was entirely present, and
 * [com.music.yzmusic.playback.AudioCache.requestAnalysisHead] — the fallback
 * for that answer — is a no-op for anything without a YouTube id. Between them,
 * a local file was never queued for analysis at all: it sat at "waiting" for as
 * long as it stayed queued, both tracks of every transition read as no
 * evidence, and the policy ladder answered that the only way it can, with a
 * plain crossfade. Which is exactly what Automix over a local library sounded
 * like.
 *
 * ## Shape
 *
 * A local file needs none of the machinery a cached rendition does, and the
 * differences are not cosmetic:
 *
 *  - **There is one copy.** No lightest-complete-rendition to choose between,
 *    and no sibling to cross-check a borrowed beat grid against.
 *  - **It is complete the moment it exists.** The head-then-whole-track
 *    escalation exists to get ahead of a download; there is no download.
 *  - **It is the user's file.** Nothing here may delete it when a decode
 *    disappoints, which is the one thing the cache path is allowed to do.
 *
 * Reads go through `pread` rather than seek-then-read, so the descriptor
 * carries no shared position: [android.media.MediaExtractor] jumps around a
 * container freely — header, then trailer, then back — and two reads
 * interfering would present as a corrupt file rather than as a bug.
 */
internal object LocalAudioSource {

    private const val TAG = "YZMusicLocalAudio"

    /** Whether [uri] names a file on the device rather than something to fetch. */
    fun isLocal(uri: Uri): Boolean = when (uri.scheme?.lowercase(Locale.ROOT)) {
        ContentResolver.SCHEME_FILE, ContentResolver.SCHEME_CONTENT -> true
        else -> false
