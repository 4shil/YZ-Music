package com.music.yzmusic.data.lyrics

/** Shorter instrumental breaks aren't worth interrupting the line for. */
internal const val MIN_GAP_MS = 4_000L

/**
 * Marks the instrumental stretches with blank lines, the way an LRC file
 * marks them with a bare timestamp.
 *
 * A break is only drawn where the line before it says when its singing
 * stopped — see [LyricLine.hasKnownEnd]. Given that, the note appears the
 * moment the vocal ends rather than several seconds later once the next line
 * was due, which is the whole advantage over [LrcLib.parseLrc]'s stamp-to-stamp
 * guess. Without it there is nothing to measure silence against: the distance
 * to the next stamp is the line's own slot, and treating that as a break puts a
