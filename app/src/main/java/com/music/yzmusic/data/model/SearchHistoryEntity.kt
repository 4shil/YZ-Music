package com.music.yzmusic.data.model

import kotlinx.serialization.Serializable

/**
 * What a recent search entry was found as, which is what lets a recent be
 * opened as the thing it was rather than as the text that found it.
 *
 * [QUERY] is the plain-text case: nothing was picked, a term was typed and
 * submitted, so there is no entity behind it and the term is all there is.
 */
@Serializable
enum class SearchEntityType {
    SONG,
    VIDEO,
    ALBUM,
    ARTIST,
    PLAYLIST,
    OTHER,
    QUERY,
}

/**
 * One entry of the recent searches list.
 *
 * This used to be a bare string — the term that was searched. That is enough
 * to show a list and re-run the search, and nothing more: the same page
 * shows a song, an album and an artist, and the term alone cannot say which
 * was which or show the cover that was on screen when it was picked. So the
 * id, kind and metadata of what was actually acted on are kept, and the
 * search term rides along as well.
 *
 * [artist] and [artworkUrl] are nullable because a query entry has neither,
 * and a track's artist is only ever missing because the catalogue said so.
 */
@Serializable
data class SearchHistoryEntity(
    val id: String,
    val entityType: SearchEntityType,
    val title: String,
    val artist: String? = null,
    val artworkUrl: String? = null,
    val query: String? = null,
    val durationText: String? = null,
    val timestamp: Long = 0L,
) {
    /**
     * What two entries are "the same thing" for.
     *
     * The kind is part of it because ids are not shared across kinds: a
     * playlist whose browse id happens to read like a video id is a different
     * entry, and merging the two would hide one of them from the list.
     */
    val identity: String get() = "${entityType.name}:$id"

    /**
     * The text to search for, for an entry that has nothing to open directly.
     *
     * Only a query entry has one. The others are reopened as the entity they
     * are, from what is stored here, which is what keeps a recent usable with
     * no connection — see `com.music.yzmusic.ui.search.RecentSearchAction`.
     */
    val searchTerm: String?
        get() = query?.takeIf { it.isNotBlank() }

    /** An entry with neither an id nor a name is not an entry. */
    val isUsable: Boolean get() = id.isNotBlank() && title.isNotBlank()

    companion object {
        /**
         * A term that was typed and submitted. Null for blank text, which is
         * the whole of what is worth refusing here: there is nothing to search
         * for and nothing to show.
         *
         * The id is the term folded to lower case, so "M83" and "m83" are one
         * entry rather than two rows of the same search.
         */
        fun forQuery(term: String, timestamp: Long = System.currentTimeMillis()): SearchHistoryEntity? {
            val trimmed = term.trim()
            if (trimmed.isEmpty()) return null
            return SearchHistoryEntity(
                id = "q:${trimmed.lowercase()}",
                entityType = SearchEntityType.QUERY,
                title = trimmed,
                query = trimmed,
                timestamp = timestamp,
            )
        }

        /** A track or a video, with everything a song row carries. */
        fun forSong(song: Song, timestamp: Long = System.currentTimeMillis()) = SearchHistoryEntity(
            id = song.videoId,
            entityType = if (song.isVideo) SearchEntityType.VIDEO else SearchEntityType.SONG,
            title = song.title,
            artist = song.artist.trim().takeIf { it.isNotEmpty() },
            artworkUrl = song.thumbnailUrl,
            durationText = song.durationText,
            timestamp = timestamp,
        )

        /** An album, artist or playlist, kept with the type it was found as. */
        fun forBrowse(item: BrowseItem, timestamp: Long = System.currentTimeMillis()) = SearchHistoryEntity(
            id = item.browseId,
            entityType = when (item.type) {
                BrowseType.ALBUM -> SearchEntityType.ALBUM
                BrowseType.ARTIST -> SearchEntityType.ARTIST
                BrowseType.PLAYLIST -> SearchEntityType.PLAYLIST
                else -> SearchEntityType.OTHER
            },
            title = item.title,
            artist = item.subtitle.trim().takeIf { it.isNotEmpty() },
            artworkUrl = item.thumbnailUrl,
            timestamp = timestamp,
        )
    }
}
