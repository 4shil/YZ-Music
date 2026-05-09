package com.music.yzmusic.data.sources

import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.jiosaavn.JioSaavnService
import com.music.yzmusic.data.model.Song

private const val TAG = "YZ Music"

class JioSaavnSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.JIOSAAVN
    override val displayName: String get() = config.label.ifBlank { SourceKind.JIOSAAVN.label }

    /** Always Ok since the API endpoints don't need authentication to search. */
    override suspend fun health(): SourceHealth = SourceHealth.Ok()

    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> {
        TrackLog.d(TAG, "▶ JioSaavn searchSongs() query=\"$query\" limit=$limit")
        val results = JioSaavnService.searchSongs(query)
        TrackLog.d(TAG, "  ✓ JioSaavn returned ${results.size} tracks" + results.take(3)
            .joinToString(prefix = ": ", separator = "; ") { "'${it.title}' by '${
                it.moreInfo.artistMap.primaryArtists.joinToString(", ") { a -> a.name }
            }' ${it.moreInfo.duration}s" }.takeIf { results.isNotEmpty() }.orEmpty())
        return results.take(limit).map { raw ->
            val primaryArtists = raw.moreInfo.artistMap.primaryArtists.joinToString(", ") { it.name }
            val artistName = primaryArtists.ifBlank { "Unknown Artist" }
            
            // Generate higher quality thumbnail link (e.g. 500x500)
