package com.music.yzmusic.ui.replay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.music.yzmusic.data.stats.ArtistFacts
import com.music.yzmusic.data.stats.ListeningStats
import com.music.yzmusic.data.stats.ReplayPeriod
import com.music.yzmusic.data.stats.ReplaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Replay's state: which stretch of listening is being shown, and the
 * numbers for it.
 *
 * Held here rather than in [com.music.yzmusic.ui.MainViewModel] because it is
 * only alive while the page is: the summary is a merge of a few files and is
 * cheap to make, and keeping a copy of every chart in a view model that outlives
 * the screen would hold artwork URLs and a few hundred rows for the rest of the
 * session in exchange for saving a hundred milliseconds nobody would notice.
 *
 * The *period* does survive, because it is a choice rather than a result.
 */
class ReplayState(
    val period: ReplayPeriod,
    val summary: ReplaySummary?,
    val loading: Boolean,
    /**
     * `MM/YY` of the first month this device recorded anything, for the card.
     *
     * Deliberately all-time rather than the open period's own first month: a
     * card that says "member since" has to mean since you started, and reading
     * it off the period would have it announce a new membership every time the
     * chips were switched to This month.
     */
    val memberSince: String?,
)

/**
 * @param active whether the Replay is open in any of its three forms — the
 *   page, the stories, the share sheet. The state is hoisted to the app so all
 *   three read one set of numbers, and this is what stops that hoisting from
 *   costing a file merge on every cold start for a page most launches never
 *   open. It also means reopening Replay re-reads: whatever has been played
 *   since is on it.
 */
@Composable
fun rememberReplayState(active: Boolean): Pair<ReplayState, (ReplayPeriod) -> Unit> {
    var period by rememberSaveable { mutableStateOf(ReplayPeriod.THIS_YEAR) }
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var memberSince by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // A directory listing, so off the composition's thread.
        memberSince = withContext(Dispatchers.IO) {
            ListeningStats.months().firstOrNull()?.let {
                "%02d/%02d".format(Locale.ROOT, it.monthValue, it.year % 100)
            }
        }
    }
    LaunchedEffect(period, active) {
        if (!active) return@LaunchedEffect
        // Only the first read shows a spinner. Switching period must not blank
        // the charts for the beat it takes to merge the files — that reads as
        // the page breaking rather than as it answering a different question.
        loading = summary == null
        summary = ListeningStats.summary(period)
        loading = false

        // Artist pictures and pages arrive after the page has been built — see
        // [ArtistFacts.revision] — so the charts are rebuilt when they do.
        //
        // Collected inside the effect rather than as composed state on purpose:
        // this function is called from the app's root, so a revision held as
        // state would recompose the whole tree every time a lookup landed, even
        // with the Replay closed. `collectLatest` gives the debounce for free —
        // a burst of lookups cancels each pending delay and only the last one
        // gets as far as a rebuild.
        ArtistFacts.revision.drop(1).collectLatest {
            delay(SETTLE_MILLIS)
            summary = ListeningStats.summary(period)
        }
    }
    return ReplayState(period, summary, loading, memberSince) to
        { next: ReplayPeriod -> period = next }
}

/** How long a burst of artist lookups is allowed to settle before a rebuild. */
private const val SETTLE_MILLIS = 1_200L

/**
 * One run of a card's headline, and whether it is the emphasised part.
 *
 * The sentence lives here rather than in the story that draws it because it is
 * drawn twice — once on screen and once into the picture the share button
 * produces — and a card that says something different in the version people
 * send is worse than no picture at all.
 */
data class HeadlineRun(val text: String, val bold: Boolean)

private fun runs(vararg parts: Pair<String, Boolean>): List<HeadlineRun> =
    parts.map { HeadlineRun(it.first, it.second) }

/** The sentence at the top of [page]. */
