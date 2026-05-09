package com.music.yzmusic.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.music.yzmusic.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.download.DownloadStore
import com.music.yzmusic.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object LocalMediaRepository {

    private const val TAG = "YZ Music"

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all songs in the `Music/YZ Music` directory, combining app downloads
     * with any local audio files present in that folder.
     *
     * The download record is the better source for a title and a credit — it
     * holds what the catalogue row said, not what a scanner guessed off a
     * filename — but it only started carrying the album at all recently, and
     * an album page's rows never name their own release. So whatever the media
     * scanner read off each file is collected alongside and used to fill the
     * gaps, which is what keeps the Albums tab from being empty for everything
     * downloaded before that field existed.
     */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Song>()

        /** uri to what the media scanner read off that file. */
        val scanned = mutableMapOf<String, ScannedTags>()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            artworkUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                null
                            },
                        )
                        scanned[contentUri] = tags
                        if (contentUri !in knownUris && isAudioFileName(name)) {
                            extraSongs.add(buildSongFromUri(context, contentUri, name, tags))
                        }
                    }
                }
            } else {
                val folder = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC,
                    ),
                    DownloadStore.FOLDER,
                )
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && isAudioFileName(file.name)) {
                            val uriStr = Uri.fromFile(file).toString()
                            if (uriStr !in knownUris) {
                                extraSongs.add(buildSongFromUri(context, uriStr, file.name))
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/YZ Music directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            if (song.albumName != null) return@map song
            val uri = song.localUri ?: return@map song
            val album = scanned[uri]?.albumName ?: return@map song
            song.copy(albumName = album)
        }

        (filled + extraSongs).distinctBy { it.localUri ?: it.videoId }
    }

    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(val albumName: String?, val artworkUrl: String?)

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * Queries MediaStore for all audio files available on the device.
     */
    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) return@withContext emptyList()

        val songs = mutableListOf<Song>()
        // This scan runs over every audio file on the device, which includes
        // whatever this app has downloaded into Music/YZ Music alongside
        // everything else — but by content URI, the only thing MediaStore
        // offers here, that download is indistinguishable from a file the
        // user copied on by hand. Reversing [Downloads.saved] hands a
        // downloaded track its real YouTube id back, which is what lets
        // PlaybackTracker recognise it as a video worth registering a play
        // for — a content URI fails its id check on purpose, since most rows
        // here really are just local files with nothing to sync.
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 5000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
