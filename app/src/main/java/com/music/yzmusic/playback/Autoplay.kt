package com.music.yzmusic.playback

import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.sources.SourceRegistry
import com.music.yzmusic.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/** Threshold below which AutoPlay replenishes future queued recommendations. */
const val AUTOPLAY_LOW_WATER_MARK = 5

/**
 * Finds the YouTube id that should seed AutoPlay for a song. Module tracks and local
 * audio files do not carry native YouTube ids, so they are matched on YouTube before
 * the radio request.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    val isLocal = song.videoId.startsWith("content://") || song.videoId.startsWith("file://")
    val isModule = SourceRegistry.parseTrackKey(song.videoId) != null
    if (!isLocal && !isModule) return song.videoId

    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
