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
