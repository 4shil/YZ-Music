package com.music.yzmusic.data.canvas

import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Finds the looping video that belongs behind a track's or a release's cover
 * art — Spotify's Canvas, Apple's motion artwork.
 *
 * Four sources, asked in turn until one answers. They cover genuinely
 * different ground rather than being four routes to the same catalogue:
 * Apple has the most, Tidal has square covers on a lot of what Apple misses,
 * the community index is the only one that reaches back catalogue, and
 * Spotify has the original Canvas but needs the listener's own session
 * cookie to reach (see [SpotifyCanvas]) and is a free no-op without one.
 * Spotify goes last rather than first even once it's set up: it's the
 * heaviest of the four to reach (an offscreen WebView, not just a request)
 * and the other three between them already cover most of what it would
 * have answered.
 *
 * Every one of them is a public endpoint belonging to someone else, reached
 * without an account, and all of them will confidently answer a search with
 * the wrong record. So the shape of this is: ask, then re-check the answer
 * against what was asked for ([CanvasArtwork.matches]), and treat any failure
 * — network, parse, mismatch — as simply no canvas. The still art is always
 * underneath, so nothing here can break the player or the album page.
 *
 * Results are cached, misses included: nothing having a canvas is the common
 * case, and without negative caching every revisit would pay for three
 * lookups again to learn the same thing.
 */
object CanvasRepository {

    private const val TAG = "CanvasRepository"
    private const val CACHE_SIZE = 64

    /**
     * A settled answer for one track or release.
     *
     * [withAlbum] records whether the album name was known when this was
     * worked out. It is the one thing that can turn a miss into a hit later:
     * the album is what makes the catalogue searches land, and on the player
     * it resolves a beat after the track starts. A miss reached without it is
     * therefore provisional; everything else is final.
     */
    private class Entry(val artwork: CanvasArtwork?, val withAlbum: Boolean)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
