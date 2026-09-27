package com.music.yzmusic.ui.screens

import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.model.ShelfItem

/**
 * The Recents shelf's two layouts, as plain data.
 *
 * Recents is the only shelf on Home with a layout the reader picks, so what
 * decides whether a shelf gets that treatment is worth keeping away from the
 * composables: [isRecents] is the gate that leaves every other shelf on the
 * path it was already on, and a gate that only the screen can test is a gate
 * that quietly grows.
 */
object RecentShelfLayout {

    /** Tracks to a column — the same column the artist page's top tracks use. */
    const val TRACKS_PER_COLUMN = 4

    /**
     * Whether [title] is the Recents shelf, and so the one shelf whose layout
     * the header toggle is allowed to speak for.
     *
     * Matched against the single title the repository gives the shelf
     * ([YtMusicRepository.RECENT_TITLE]) rather than against its position, since
     * a signed-out account has no history and the lead position then belongs to
     * a shelf this feature was never built for.
     */
    fun isRecents(title: String): Boolean =
        title.equals(YtMusicRepository.RECENT_TITLE, ignoreCase = true)

    /**
     * The shelf's tracks in reading order, gathered into [TRACKS_PER_COLUMN]
     * long columns.
     *
     * Flattening this back out gives [items] back untouched: the layout decides
     * which column a track sits in, never which tracks there are or in what
     * order, so switching layouts cannot drop a play or move one.
     */
    fun columns(items: List<ShelfItem>): List<List<ShelfItem>> =
        items.chunked(TRACKS_PER_COLUMN)
}
