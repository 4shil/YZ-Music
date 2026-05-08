package com.music.yzmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.yzmusic.auth.DiscordLoginScreen
import com.music.yzmusic.auth.YtMusicLoginScreen
import com.music.yzmusic.data.AppUpdateChecker
import com.music.yzmusic.data.LocalMediaRepository
import com.music.yzmusic.data.NerdStats
import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.innertube.InnertubeParser
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.HomeShelf
import com.music.yzmusic.data.model.LikeStatus
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.UiState
import com.music.yzmusic.data.model.durationMillis
import com.music.yzmusic.data.scrobbling.LastFM
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.ThemeMode
import com.music.yzmusic.ui.screens.AccountAndScrobblingScreen
import com.music.yzmusic.ui.screens.DiscordDialog
import com.music.yzmusic.ui.screens.DiscordDialogHost
import com.music.yzmusic.ui.screens.DiscordScreen
import com.music.yzmusic.ui.screens.HistoryScreen
import com.music.yzmusic.ui.screens.SettingsScreen
import com.music.yzmusic.ui.screens.SourcesScreen
import com.music.yzmusic.ui.screens.SpotifyCanvasAuthScreen
import com.music.yzmusic.playback.LinkRequest
import com.music.yzmusic.playback.MusicLink
import com.music.yzmusic.playback.PlayerDeepLink
import com.music.yzmusic.playback.QueueBuilder
import com.music.yzmusic.playback.QueueShuffle
import com.music.yzmusic.playback.autoplaySectionStart
import com.music.yzmusic.playback.playSongs
import com.music.yzmusic.playback.toMediaItem
import com.music.yzmusic.playback.toggleAutoplay
import com.music.yzmusic.download.DownloadSession
import com.music.yzmusic.download.DownloadStore
import com.music.yzmusic.download.DownloadTarget
import com.music.yzmusic.download.Downloads
import com.music.yzmusic.ui.components.BrowseActionsSheet
import com.music.yzmusic.ui.components.BrowseTarget
import com.music.yzmusic.ui.components.DownloadManagerSheet
import com.music.yzmusic.ui.components.PlaylistPickerSheet
import com.music.yzmusic.ui.components.SongActionsSheet
import com.music.yzmusic.playback.rememberMediaController
import com.music.yzmusic.playback.rememberPlayerState
import com.music.yzmusic.ui.MainViewModel
import com.music.yzmusic.ui.components.BottomFadeScrim
import com.music.yzmusic.ui.components.BottomTab
import com.music.yzmusic.ui.components.FLOATING_BAR_MAX_WIDTH
import com.music.yzmusic.ui.components.FloatingBottomBar
import com.music.yzmusic.ui.components.FrostedTopBar
import com.music.yzmusic.ui.components.LastfmLoginAlert
import com.music.yzmusic.data.sources.SourceRegistry
import com.music.yzmusic.ui.components.ListenBrainzTokenAlert
import com.music.yzmusic.ui.components.TextValueAlert
import com.music.yzmusic.ui.components.MiniPlayer
import com.music.yzmusic.ui.components.TopBarAccountButton
import com.music.yzmusic.ui.components.TopBarDownloadButton
import com.music.yzmusic.ui.components.TopFadeBlur
import com.music.yzmusic.ui.components.topBarContentPadding
import com.music.yzmusic.ui.components.AppLanguageDialog
import com.music.yzmusic.ui.components.LyricsSourcesDialog
import com.music.yzmusic.ui.components.UpdateAvailableDialog
import com.music.yzmusic.ui.icons.YZMusicIcons
import androidx.media3.common.Player
import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.ui.player.NowPlayingScreen
import com.music.yzmusic.ui.player.dockedPlayerAvailable
import com.music.yzmusic.ui.player.dockedPlayerWidth
import com.music.yzmusic.ui.screens.DetailScreen
import com.music.yzmusic.ui.screens.LocalMusicScreen
import com.music.yzmusic.ui.screens.HomeScreen
import com.music.yzmusic.ui.screens.LibraryGridPage
import com.music.yzmusic.ui.screens.LibraryScreen
import com.music.yzmusic.ui.screens.SearchScreen
import com.music.yzmusic.ui.replay.ReplayScreen
import com.music.yzmusic.ui.replay.cards
import com.music.yzmusic.ui.replay.ReplayShareSheet
import com.music.yzmusic.ui.replay.ReplayStories
import com.music.yzmusic.ui.replay.ReplayStoryPage
import com.music.yzmusic.ui.replay.rememberReplayState
import com.music.yzmusic.ui.theme.YZMusicTheme
import com.music.yzmusic.ui.theme.rememberArtworkPalette
import com.music.yzmusic.ui.theme.SystemBarIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before the composition, so a cold launch from a widget's artwork has
        // the request already standing by the time YZMusicApp first reads it.
        PlayerDeepLink.consume(intent)
        // Likewise for a link tapped or shared from another app — see [MusicLink].
        MusicLink.consume(intent)
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            YZMusicTheme(darkTheme = darkTheme) {
                // The window's width, measured rather than asked for.
                //
                // `Configuration.screenWidthDp` is the wrong question here: in a
                // freeform or desktop window it can report the display rather
                // than the window it is actually in, and it lands a beat late
                // when that window is dragged. The layout downstream splits in
                // two on the strength of this number and sizes both halves from
                // it, so a stale one is a player pane sized for a window that no
                // longer exists and a page squeezed to a sliver to pay for it.
                // A measured constraint cannot be stale — it is the very width
                // the split is about to be laid out in.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    YZMusicApp(darkTheme = darkTheme, windowWidth = maxWidth)
                }
            }
        }
    }

    /**
     * The other half of the relay. This activity is `singleTask`, so once it is
     * running a second tap on the widget does not rebuild anything — it arrives
     * here, and [onCreate] never runs again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replaces what getIntent() returns, so the extra this consumes is the
        // one that just arrived and not the one the task was started with.
        setIntent(intent)
        PlayerDeepLink.consume(intent)
        MusicLink.consume(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YZMusicApp(
    darkTheme: Boolean,
    /** The width of the window this is laid out in — see the call site. */
    windowWidth: Dp,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Whether there is room to keep the player open beside the page rather than
    // raising it over one. Read all over what follows, because most of what the
    // page does about the player is really about which of the two it is: no mini
    // player standing in for one that is already there, no sheet to raise, and
    // the bottom inset the mini player was holding handed back to the page.
    val playerDocked = dockedPlayerAvailable(windowWidth)
    /**
     * Whether the player's *sheet* is up.
     *
     * Only ever set where there is a sheet to set it for. Docked, the player is
     * open whatever this says, and the things that read it — the light status
     * bar glyphs the artwork needs, the sheet itself — are all asking the one
     * question this used to answer on its own: is the player covering the page?
     */
    var showNowPlaying by remember { mutableStateOf(false) }
    // The far end of the relay from a widget's artwork. Cleared here rather than
    // where it was set, so the request is spent by being served — see
    // [PlayerDeepLink.handled]. The sheet itself is gated on there being a track,
    // so on a cold launch this simply arms it and it opens as the controller
    // connects. Docked there is nothing to raise: the player is already up, and
    // the tap has been honoured by the time it arrives.
    val openPlayerRequested by PlayerDeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            if (!playerDocked) showNowPlaying = true
            PlayerDeepLink.handled()
        }
    }
    var showLogin by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // Replay: the page, the stories over it, and the share sheet over those.
    // Three states rather than one enum because they stack — the stories are
    // opened from the page and the share sheet from either, and closing one
    // has to reveal what it was opened from.
    var showReplay by remember { mutableStateOf(false) }
    var replayStory by remember { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by remember { mutableStateOf(false) }
    /** Which story card the share sheet is for, or null for the whole Replay. */
    var replaySharePage by remember { mutableStateOf<ReplayStoryPage?>(null) }
    var showAccountScrobbling by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showSpotifyCanvasAuth by remember { mutableStateOf(false) }
    
    // Hosted here rather than inside SourcesScreen so its scrim covers the tab
    // bar and mini player, like every other alert in the app.
    var customModuleAlert by remember { mutableStateOf(false) }
    var customModuleInput by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    // A Library shelf's "Show all" — the shelf it was opened from, so its own
    // cards can be laid out again as a full-screen grid. See [LibraryGridPage].
    var libraryShowAll by remember { mutableStateOf<HomeShelf?>(null) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showAppLanguage by remember { mutableStateOf(false) }
    var showListenBrainzLogin by remember { mutableStateOf(false) }
    var showLastfmLogin by remember { mutableStateOf(false) }
    /**
     * Whether the download manager is open.
     *
     * Not [rememberSaveable]: the list behind it does not survive the process
     * either (see [com.music.yzmusic.download.DownloadSession]), and a sheet
     * restored over an empty one would be a manager with nothing to manage.
     */
    var showDownloadManager by remember { mutableStateOf(false) }
    // Discord Rich Presence: its own page under Account & integrations, its own
    // full-screen sign-in, and one slot for whichever of its alerts is open.
    // The alerts live out here rather than on the page because their scrim has
    // to cover the tab bar and mini player, which are drawn after it.
    var showDiscord by remember { mutableStateOf(false) }
    var showDiscordLogin by remember { mutableStateOf(false) }
    var discordDialog by remember { mutableStateOf<DiscordDialog?>(null) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    /**
     * Whether the track menu that is up was opened from the player.
     *
     * Its copy of the menu carries rows nothing else offers — a sleep timer,
     * the track log, share — and until now "opened from the player" and "the
     * player is on screen" were the same sentence, because the player was a
     * sheet and nothing else could be up behind it. On a tablet the player is
     * never *the* thing on screen: it is always beside whatever is, so the
     * question has to be answered by whoever opened the menu.
     */
    var menuFromPlayer by remember { mutableStateOf(false) }
    /** Holding a row anywhere but the player — the menu without the player's rows. */
    val openSongMenu: (Song) -> Unit = { song ->
        menuFromPlayer = false
        songActions = song
    }
    // Whether the player's album/artist lookup (below, for the current track)
    // is still in flight — read by the long-press sheet so it can show a
    // loading row instead of the two just being absent while it waits.
    var linksLoading by remember { mutableStateOf(false) }
    // Which track the playlist picker is adding, or null when it's closed.
    // Separate from [songActions] so the menu can close behind it — the picker
    // is the next step, not a second sheet stacked on the first.
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    // The picker opened from the Library tab, where there is no track and
    // creating the playlist is the whole errand.
    var creatingPlaylist by remember { mutableStateOf(false) }
    // Which album or playlist the collection menu is open on, or null when it
    // is shut. One slot for every surface that can open it — the shelves on
    // three tabs, the search rows, the artist page's carousels, the release
    // page's own overflow — because only one of them can be held at a time.
    var browseActions by remember { mutableStateOf<BrowseTarget?>(null) }
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    // Incremented each time the search tab is re-tapped while already selected,
    // which SearchScreen uses as a signal to focus the input field.
    var searchFocusTrigger by remember { mutableIntStateOf(0) }

    // The player fills the screen with dark artwork whichever theme is on, so
    // it keeps light glyphs; every other surface follows the theme. Replay's
    // page and stories are the same case — dark artwork either way.
    SystemBarIcons(dark = !darkTheme && !showNowPlaying && !showReplay && replayStory == null)

    val homeState by viewModel.home.collectAsStateWithLifecycle()
    val homeLoadingMore by viewModel.homeLoadingMore.collectAsStateWithLifecycle()

    // The top bar's icon is the quiet, always-there nudge; this is the
    // once-per-launch popup version of the same news. `updateDialogShown`
    // rides out configuration changes on rememberSaveable so a rotation
    // doesn't bring it back — only a fresh launch does.
    var updateDialogShown by rememberSaveable { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()

    /**
     * The single gate both surfaces read, so the icon can't announce the update
     * a beat before the popup does — they're one piece of news, and staggering
     * them made the top bar look like it had caught something the app hadn't.
     */
    val updateNotice = updateAvailable

    LaunchedEffect(updateNotice) {
        if (updateNotice != null && !updateDialogShown) {
            updateDialogShown = true
            showUpdateDialog = true
        }
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val exploreState by viewModel.explore.collectAsStateWithLifecycle()
