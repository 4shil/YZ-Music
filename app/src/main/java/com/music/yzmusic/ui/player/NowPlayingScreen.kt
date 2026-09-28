package com.music.yzmusic.ui.player

import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.drawBehind
import com.music.yzmusic.data.lyrics.LyricAlignment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import com.music.yzmusic.download.Downloads
import com.music.yzmusic.download.DownloadState
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.music.yzmusic.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.draw.drawWithCache
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.artworkAt
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.music.yzmusic.ui.rememberIsForeground
import com.music.yzmusic.ui.MainViewModel
import com.music.yzmusic.ui.components.ExplicitBadge
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.haptics.Haptic
import com.music.yzmusic.ui.haptics.rememberHaptics
import com.music.yzmusic.ui.icons.YZMusicIcons
import com.music.yzmusic.ui.offersTranslationDisc
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.data.NerdStats
import com.music.yzmusic.data.settings.TrackAnalysisState
import com.music.yzmusic.data.canvas.CanvasArtwork
import com.music.yzmusic.data.canvas.CanvasRepository
import com.music.yzmusic.data.canvas.CanvasSource
import com.music.yzmusic.data.lyrics.Genius
import com.music.yzmusic.data.lyrics.LyricDisplayRow
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricsTranslationState
import com.music.yzmusic.data.lyrics.LyricsSource
import com.music.yzmusic.ui.components.LyricsLogConsole
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.AudioQuality
import com.music.yzmusic.data.model.LikeStatus
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.playback.BACK_RESTARTS_AFTER_MS
import com.music.yzmusic.playback.autoplaySectionStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Collapsed-header geometry, shared by the layout and its animation. */
/** Comfortably over the sleeve's drawn size on a phone, without wasting bytes. */
private const val ART_PX = 1200

/**
 * How long a canvas lookup waits for the track's album name before giving up
 * on it. Long enough to cover the album lookup on a normal connection, short
 * enough not to be noticed on a track that has no album to find.
 */
private const val ALBUM_SETTLE_MS = 700L

/**
 * How close the player's reported position has to get to a released scrub
 * handle before the handle stops being drawn where it was dropped. Wide enough
 * to swallow a coarse progress tick, tight enough that the handle doesn't hand
 * over while it is still visibly wrong.
 */
private const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/**
 * How long that handle is held at the drop point regardless. A backstop, not a
 * schedule: a seek normally settles in a tick or two, and this only decides how
 * long a seek that never settles can freeze the bar for. Generous enough that a
 * slow buffer still hands over smoothly rather than snapping back.
 */
private const val SEEK_SETTLE_TIMEOUT_MS = 4_000L
private const val SEEK_END_GUARD_MS = 1_000L

private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
/**
 * How long the sleeve takes to travel the whole way between the full player and
 * the queue's header.
 *
 * Spent in proportion rather than in full: a drag released four fifths of the
 * way up has a fifth of the journey left and gets a fifth of the time for it.
 * Only the toggle, which travels end to end, ever spends all of it.
 */
private const val QUEUE_TRAVEL_MS = 420

/**
 * The handle strip above the artwork, which always hands drags to the sheet.
 *
 * It isn't the only place that does — the artwork and the credits under it pass
 * theirs on as well, which is what makes the whole top of the player closable
 * rather than just its topmost 44dp. See the dismiss band in `NowPlayingScreen`.
 */
private val DISMISS_STRIP_HEIGHT = 44.dp
/** The breathing room above the sleeve, needed twice: once to apply, once to measure past. */
private val ART_BOX_TOP_PAD = 14.dp
/**
 * Share of the motion-artwork banner's height given over to its dissolve.
 *
 * Generous on purpose: the banner has no card edge to stop at, so anything
 * short enough to still be reading as artwork where it ends reads as a picture
 * that was cut off rather than one that ran out.
 */
private const val HERO_FADE_FRACTION = 0.42f

/** The player's side margin. Scrollable panels reach back across it. */
private val PLAYER_GUTTER = 30.dp
/**
 * How wide the player's content is ever allowed to get. A sleeve and a volume
 * slider stretched right across a tablet aren't a bigger player, just a coarser
 * one; past this the column stops growing and centres itself instead. Phones
 * are narrower than this, so for them it does nothing.
 */
private val PLAYER_MAX_WIDTH = 560.dp
/**
 * The pane's share of a window wide enough to dock in, and the bounds it takes
 * that share within.
 *
 * A fraction alone hands a 13in screen half a metre of player. The ceiling is a
 * phone's width because that is the shape the player was drawn for and the shape
 * it looks right in — a square sleeve, one line of credits, a row of oversized
 * glyphs. Widened past that the sleeve stops being able to grow with it (it is
 * bounded by the pane's height long before that) and all the extra pane buys is
 * a scrubber and a volume slider stretched thin either side of it, which is a
 * coarser player rather than a bigger one, and a column of feed given up to pay
 * for it. The floor is there because the fraction of the narrowest window that
 * qualifies is thinner than the controls want to be.
 */
private const val DOCKED_PLAYER_FRACTION = 0.42f
private val DOCKED_PLAYER_MIN_WIDTH = 340.dp
private val DOCKED_PLAYER_MAX_WIDTH = 420.dp

/**
 * The narrowest the page is worth leaving while the player stands beside it.
 *
 * About a small phone: below this the shelves stop showing a second card, the
 * track rows lose their artist line to the ellipsis and a two-pane layout is
 * two things done badly instead of one done well.
 */
private val DOCKED_PAGE_MIN_WIDTH = 360.dp
/**
 * The room above a docked player's artwork, in place of the drag handle.
 *
 * The handle is a promise that the player can be pulled away, and a pane it is
 * pinned in cannot be — so what is left is the gap it used to sit in, minus the
 * strip the gesture needed.
 */
private val DOCKED_TOP_PAD = 12.dp
/**
 * How far a tall screen is allowed to push the transport from the blocks either
 * side of it.
 *
 * The spare height has to land somewhere, and above and below the play button is
 * where it reads as room rather than as a hole. Past this it stops reading as one
 * group of controls, so the rest goes back to the artwork block.
 */
private val CONTROL_GAP_SPREAD_MAX = 48.dp
/**
 * The spread [NowPlayingScreen] settled on the last time it was laid out.
 *
 * It follows from the window, so it is very nearly the same answer on every open
 * — and the player is torn down with its sheet, so without this the first frame
 * of each open would show the unspread gaps and then step to the real ones. Only
 * a head start: the frame after re-derives it either way. A plain var because
 * that is all it is, a cache of a measurement, not state anything observes.
 */
private var lastControlSpread: Dp = 0.dp

/**
 * Whether the player is ever narrow enough in this window to run artwork edge to
 * edge — the gate on both the motion-artwork banner and
 * [AppSettings.fullBleedArtwork]. Public so the settings sheet can leave the
 * switch out entirely where it would do nothing.
 *
 * Two ways to qualify. A window narrow enough that the player fills it is one:
 * edge to edge there means the artwork *is* the screen, which is the whole idea.
 * A window wide enough to dock the player is the other, and for the same reason
 * rather than in spite of it — the pane is a phone's width by construction (see
 * [dockedPlayerWidth]), so edge to edge inside it reads exactly as it does on a
 * phone. Only the band between the two has nothing to offer: too wide for the
 * player to fill, too narrow to stand something beside it.
 */
fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || dockedPlayerAvailable(windowWidth)

/**
 * Whether a player given the whole of a window this wide is still narrow enough
 * to run its artwork edge to edge.
 *
 * The player's own width is the question, always — this is just the form it takes
 * when the player *is* the window, which is the only time the window's width is
 * an answer to it. A docked pane has its own, much smaller width and does not go
 * through here.
 */
private fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

/**
 * Whether [windowWidth] is enough to keep the player open beside the page rather
 * than raising it over one: the least the page can live in and the least the
 * player can live in, side by side. Stated as the sum of the two minimums rather
 * than as a number of its own, so it cannot drift out of step with either.
 *
 * [windowWidth] is the width of the *window*, and it has to be measured rather
 * than read off `Configuration.screenWidthDp` — in a freeform or desktop window
 * that can report the display instead of the window, and it lands a beat late
 * when the window is dragged. Deciding a two-pane split from a width the app
 * does not have splits it at the wrong moment and in the wrong place.
 *
 * Public because it is not only the player's business: the page it stands next
 * to loses the mini player, gets its bottom inset back, and stops being able to
 * raise the sheet at all.
 */
fun dockedPlayerAvailable(windowWidth: Dp): Boolean =
    windowWidth >= DOCKED_PAGE_MIN_WIDTH + DOCKED_PLAYER_MIN_WIDTH

/** How wide that pane is. Only meaningful where [dockedPlayerAvailable] is true. */
fun dockedPlayerWidth(windowWidth: Dp): Dp =
    (windowWidth * DOCKED_PLAYER_FRACTION)
        .coerceIn(DOCKED_PLAYER_MIN_WIDTH, DOCKED_PLAYER_MAX_WIDTH)
        // Never at the page's expense. The floor above is what the player wants;
        // this is what it may actually have, and where the two disagree the page
        // wins — [dockedPlayerAvailable] is the promise that they only disagree
        // in windows narrow enough that there is no pane at all.
        .coerceAtMost(windowWidth - DOCKED_PAGE_MIN_WIDTH)

/** Share of a lyric line's own length spent fading out, and its bounds. */
/** The row a lyric is painted into, so the bloom and the roll-off stop at it. */
private val LyricLineShape = RoundedCornerShape(10.dp)

private const val LYRIC_FADE_FRACTION = 0.28f
private const val LYRIC_FADE_MIN_MS = 160f
private const val LYRIC_FADE_MAX_MS = 700f


/**
 * How far a finger has to travel before the lyrics controls get out of the
 * way. Deltas arrive a couple of pixels at a time, so what counts is the run
 * of them in one direction, not any single one.
 */
private val CONTROLS_SCROLL_SLOP = 20.dp

/**
 * How much room the discs take at the foot of the panel: the disc itself plus
 * the padding that keeps it off the edge. Drawn rather than measured, because
 * the list has already been given its height by the time the discs lay out.
 */
private val LYRICS_DISCS_ROOM = 34.dp + 16.dp

/** Stands in for an instrumental stretch on the strip. */
private const val INSTRUMENTAL_MARK = "Instrumental"

/**
 * Shown on the strip during the intro, before the first sung line — one picked
 * at random per track, so the wait for the vocals has some character to it.
 */
private val INTRO_LINES = listOf(
    "Beat's landing",
    "Song's starting",
    "Intro's cooking",
    "Warming up",
    "Here we go",
    "Setting the mood",
    "Drums are in",
    "Bass first, words later",
    "Turn it up",
    "Vibe check",
    "Wait for it",
    "Feel that build",
    "Let it ride",
    "Just the groove for now",
    "Speakers breathing",
    "Rolling in",
    "Hold tight",
    "Riff o'clock",
    "Strings first",
    "Hook's on the way",
    "Eyes closed",
    "Loading the vibe",
    "Almost words",
    "Pure heat, no words",
    "Tuning in",
    "Buckle up",
    "Let it breathe",
    "That opening though",
    "Bass is talking",
    "Lyrics loading",
    "Give it a sec",
    "Building something",
    "Cue the vocals",
    "Slow burn",
    "First notes in",
    "Nod along",
    "Groove's on deck",
    "Melody first",
    "Ease into it",
    "Big things coming",
    "Stage is set",
    "The calm before",
    "Sit with it",
    "Any second now",
    "Volume up, phone down",
    "Drums doing the talking",
    "Locked in",
    "Something's brewing",
    "Finding its feet",
    "Deep breath",
)

/**
 * Shown on the strip while a lyrics lookup is still in flight — one picked
 * at random per track, in the same spirit as [INTRO_LINES].
 */
private val LYRICS_LOADING_LINES = listOf(
    "Getting lyrics",
    "Chasing the words",
    "Digging up the lyrics",
    "Words incoming",
    "On the hunt for lyrics",
    "Fetching the verses",
    "Tracking down the words",
    "Lyrics loading",
    "Reading between the lines",
    "Scanning for lyrics",
    "Words on the way",
    "Looking this one up",
    "Checking the lyric sheet",
    "Pulling up the words",
    "Searching the songbook",
    "Lining up the lyrics",
    "One sec, finding the words",
    "Combing through for lyrics",
    "Lyrics inbound",
    "Sourcing the verses",
    "Cross-checking the words",
    "Rounding up the lyrics",
    "Text hunt in progress",
    "Syncing up the words",
    "Peeking at the lyric sheet",
    "Almost got the words",
    "Fishing for lyrics",
    "Grabbing the transcript",
    "Lyrics, one moment",
    "Tuning in the words",
    "Locating the verses",
    "Words are en route",
    "Checking the archives",
    "Piecing the lyrics together",
    "Loading up the words",
    "Lyric search underway",
    "Finding the right words",
    "Tracking the lyric sheet",
    "Verses incoming",
    "Getting the words lined up",
    "Hang tight, fetching lyrics",
    "Looking for the hook",
    "Words are loading",
    "Lyrics on their way",
    "Checking what's sung here",
    "Reading the room for lyrics",
    "Lyric lookup in progress",
    "Bringing up the words",
    "Just a sec, finding words",
    "Lyrics coming together",
)

private const val LYRICS_UNAVAILABLE_HOLD_MS = 5_000L
private const val LYRICS_UNAVAILABLE_FADE_MS = 900

/**
 * Apple Music's Now Playing, closely: artwork that shrinks when paused, a
 * hairline scrubber with elapsed / remaining either side, oversized transport
 * glyphs, a volume capsule flanked by speaker icons, and lyrics / AirPlay /
 * queue along the bottom.
 */
@Composable
fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMs: Long,
    durationMs: Long,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    /**
     * Seek to a fraction of the track, for the scrubber.
     *
     * Separate from [onSeek] because the scrubber is the one caller that knows
     * *where along the bar* it wants to go rather than a time. Converting that
     * here would use this screen's cached duration, which lags a track change by
     * however long the session takes to report the new one — long enough to drop
     * the handle on a bar still scaled to the previous song and seek to the
     * wrong fraction of the current one. The conversion belongs wherever the
     * freshest duration is.
     */
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onDownload: () -> Unit = {},
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    /**
     * The original words. The collapsed strip above the controls reads this
     * rather than [displayRows] on purpose: one line of the singer's own
     * text is enough to recognise a song there, and a translation would be
     * one more thing to read at a glance.
     */
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
    /**
     * The rows to draw: every line of the song, each already carrying the
     * second voice the reader has switched on. A translation or a
     * romanization is drawn underneath its line rather than in place of it,
     * so the original is always there and the panel never changes shape.
     */
    displayRows: List<LyricDisplayRow>?,
    lyricsDisplayMode: MainViewModel.LyricsDisplayMode,
    availableLyricsModes: Set<MainViewModel.LyricsDisplayMode>,
    onLyricsLayerToggle: (MainViewModel.LyricsDisplayMode) -> Unit,
    /** How far the lyrics are drawn from the audio. Drawing only — see [AppSettings.lyricsOffsetMs]. */
    lyricsOffsetMs: Long,
    lyricsTranslation: LyricsTranslationState,
    /** Progress of the generated romanization, and why there might not be one. */
    lyricsRomanization: MainViewModel.RomanizationState,
    /**
     * Whether on-device translation is worth offering at all. It is not for
     * a track whose lyrics the engine would refuse, and offering it anyway
     * would spend a tap to arrive at a failure screen.
     */
    canTranslateLyrics: Boolean,
    translateLyrics: (String) -> Unit,
    /** The width of the window the player is in — see [fullBleedArtworkAvailable]. */
    windowWidth: Dp,
    /**
     * Whether the player is a pane the page sits beside rather than a sheet
     * raised over it — see [dockedPlayerAvailable].
     *
     * There is no sheet under a docked player to pull away, so the handle goes
     * and with it the strip of dead space that existed to pass drags down to one.
     * The artwork is unaffected: the pane is a phone's width, so it runs the
     * cover edge to edge exactly as a phone does — see [fullBleedArtworkAvailable].
     */
    docked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()

    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val seekDurationSeconds by AppSettings.seekDurationSeconds.collectAsStateWithLifecycle()

    // A language chosen in Settings wins. Empty means follow the device, which
    // is right for most people and needs no configuration; the panel keeps one
    // button either way, because the target is a setting rather than a
    // per-track choice and a menu has nowhere to live in here.
    val configuredLyricsLanguage by AppSettings.lyricsTargetLanguage.collectAsStateWithLifecycle()
    val lyricsTargetLanguage = remember(context, configuredLyricsLanguage) {
        configuredLyricsLanguage.ifBlank { context.resources.configuration.locales[0].language }
    }
    var doubleTapSeekDirection by remember { mutableStateOf<Int?>(null) } // -1 = backward, 1 = forward
    var doubleTapSeekSeconds by remember { mutableIntStateOf(10) }
    var doubleTapSeekTrigger by remember { mutableLongStateOf(0L) }
    val swipeDragOffset = remember { Animatable(0f) }

    LaunchedEffect(doubleTapSeekTrigger) {
        if (doubleTapSeekTrigger > 0L) {
            delay(550)
            doubleTapSeekDirection = null
        }
    }

    val currentSeekSeconds by rememberUpdatedState(seekDurationSeconds)
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val currentOnSeek by rememberUpdatedState(onSeek)

    val activeDownloads by Downloads.active.collectAsStateWithLifecycle()
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()

    // Animated cover art: the looping video some labels publish alongside a
    // release, laid over the sleeve. A miss is the normal answer — see
    // CanvasRepository, which is also where the "is this actually the right
    // track" check lives.
    val canvasEnabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val meteredConnection by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    // The switch turns the feature off outright; this is the narrower "not
    // over cellular" case — see [AppSettings.canvasOverCellular] for why a
    // clip's own loop makes that worth guarding separately from a still image.
    val canvasAllowedNow = canvasEnabled && (meteredConnection != true || canvasOverCellular)
    var canvas by remember(song.videoId) { mutableStateOf<CanvasArtwork?>(null) }
    // Whether the clip actually has a frame on screen right now, and one of
    // them — used to blow the sleeve out to the full-bleed hero treatment and
    // to re-tint the backdrop off the clip's own colours rather than the
    // still sleeve's.
    var canvasRendered by remember(song.videoId) { mutableStateOf(false) }
    var canvasFrame by remember(song.videoId) { mutableStateOf<Bitmap?>(null) }
    // How much of the still artwork the clip is covering, reported by the clip
    // itself. Read from a draw scope rather than in composition: it moves every
    // frame of the fade, and the still art it governs is an AsyncImage whose
    // request is rebuilt on each pass and so would not be skipped.
    val canvasCover = remember(song.videoId) { mutableFloatStateOf(0f) }
    // The one thing about it worth recomposing for: whether the clip is opaque
    // enough that the still frame under it can go entirely. Derived, so this
    // flips twice across a fade instead of once per frame of it.
    val stillCovered by remember(song.videoId) {
        derivedStateOf { canvasCover.floatValue > 0.999f }
    }
    val meshColors = rememberArtworkColors(song.thumbnailUrl, canvasFrame)
    // Spotify's own Canvas, specifically — see CanvasArtworkPlayer's
    // refreshFrameEveryMs for why this is scoped to that one source rather
    // than asked of every clip.
    val meshRefreshMs = if (canvas?.source == CanvasSource.SPOTIFY) 3_000L else null
    LaunchedEffect(song.videoId, song.albumName, canvasAllowedNow) {
        if (!canvasAllowedNow) {
            canvas = null
            return@LaunchedEffect
        }
        // Anything already settled for this track paints immediately: a
        // reopened player, or a track coming round again in the queue.
        canvas = CanvasRepository.cached(song) ?: canvas

        // The album name is looked up separately and lands a moment after the
        // player opens, and it is the field that makes the catalogue searches
        // match. Give it that moment: if it arrives, this effect restarts and
        // all that was spent waiting is the wait. If it never does — a track
        // with no album, or a lookup that failed — the search still goes out,
        // just a beat later, which is imperceptible for decoration.
        if (canvas == null && song.albumName == null) delay(ALBUM_SETTLE_MS)
        // Keep what an earlier pass found if this one comes back empty, rather
        // than pulling a playing clip out from under itself.
        canvas = CanvasRepository.canvasFor(song) ?: canvas
    }

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    // The Now Playing navigation model:
    // FULL_PLAYER (transitionProgress = 0.0)
    // QUEUE (transitionProgress = 1.0, peek = 0.55)
    // LYRICS (transitionProgress = 1.0, peek = 0.55)
    var navMode by remember { mutableStateOf(NowPlayingMode.FULL_PLAYER) }
    var sheetState by remember { mutableStateOf(PanelSheetState.COLLAPSED) }
    val transitionProgress = remember { Animatable(0f) }
    val p = transitionProgress.value
    val queueListState = rememberLazyListState()
    val lyricsListState = remember(song.videoId) { LazyListState() }

    val queueOpen = navMode == NowPlayingMode.QUEUE && sheetState != PanelSheetState.COLLAPSED
    val lyricsOpen = navMode == NowPlayingMode.LYRICS && sheetState != PanelSheetState.COLLAPSED
    var lyricsLogsOpen by remember { mutableStateOf(false) }
    val showLyricsLogsEnabled by AppSettings.showLyricsLogs.collectAsStateWithLifecycle()

    val swallowDownToSheet = remember(queueOpen) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                return if (queueOpen && available.y > 0f) Offset(0f, available.y) else Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return if (queueOpen && available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }

    val scope = rememberCoroutineScope()

    fun applySheetState(targetState: PanelSheetState) {
        scope.launch {
            sheetState = targetState
            val targetVal = if (targetState == PanelSheetState.EXPANDED) 1f else 0f
            transitionProgress.animateTo(
                targetValue = targetVal,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            if (targetState == PanelSheetState.COLLAPSED) {
                navMode = NowPlayingMode.FULL_PLAYER
            }
        }
    }

    fun openQueue() {
        haptics.play(Haptic.Expand)
        navMode = NowPlayingMode.QUEUE
        applySheetState(PanelSheetState.EXPANDED)
    }

    fun openLyrics() {
        haptics.play(Haptic.Expand)
        navMode = NowPlayingMode.LYRICS
        applySheetState(PanelSheetState.EXPANDED)
    }

    fun closeToFullPlayer() {
        lyricsLogsOpen = false
        haptics.play(Haptic.Tap)
        applySheetState(PanelSheetState.COLLAPSED)
    }

    val closeQueueToPlayer = remember(scope) {
        {
            closeToFullPlayer()
        }
    }

    LaunchedEffect(song.videoId) {
        lyricsLogsOpen = false
        if (navMode == NowPlayingMode.LYRICS) {
            closeToFullPlayer()
        }
    }

    BackHandler(enabled = sheetState != PanelSheetState.COLLAPSED) {
        if (lyricsLogsOpen) {
            lyricsLogsOpen = false
        } else {
            closeToFullPlayer()
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val view = LocalView.current
        DisposableEffect(view, sheetState, lyricsLogsOpen) {
            val callback = if (sheetState != PanelSheetState.COLLAPSED) {
                OverlayBack.register(view) {
                    if (lyricsLogsOpen) {
                        lyricsLogsOpen = false
                    } else {
                        closeToFullPlayer()
                    }
                }
            } else {
                null
            }
            onDispose { OverlayBack.unregister(view, callback) }
        }
    }

    // After releasing the scrubber the player needs to buffer before it
    // reports the new position. Keep showing where the user dropped it so the
    // handle doesn't snap back and then jump forward once loading finishes.
    var pendingSeek by remember { mutableStateOf<Float?>(null) }

    val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val shown = when {
        scrubbing -> scrubValue
        pendingSeek != null -> pendingSeek!!
        else -> fraction.coerceIn(0f, 1f)
    }

    // Released as soon as the player's own position agrees with where the handle
    // was dropped — and unconditionally a few seconds later whether it agrees or
    // not.
    //
    // The agreement test alone is not enough, because it is the only thing that
    // ever cleared the override: if the position never passes close to the
    // target — a clamped or rejected seek, a rendition swapped underneath, a
    // progress sample that steps straight over the window — nothing releases it
    // and the handle sits frozen at the drop point for the rest of the track.
    // Audio and lyrics follow the real position perfectly throughout, so the
    // failure looks like a stuck seek bar on a track that is playing fine.
    //
    // Tolerance is absolute rather than a share of the duration: two percent is
    // a quarter-second on a jingle and twelve seconds on a long mix, and it is
    // the wall-clock gap that decides whether the handle appears to jump.
    LaunchedEffect(positionMs, durationMs, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (durationMs > 0 && abs(positionMs - (target * durationMs).toLong()) < SEEK_SETTLE_TOLERANCE_MS) {
            pendingSeek = null
        }
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        pendingSeek = null
    }
    LaunchedEffect(song.videoId) { pendingSeek = null }

    // Signature Apple Music touch: the sleeve shrinks back while paused.
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artScale",
    )

    val audioManager = remember(context) {
        context.getSystemService(AudioManager::class.java)
    }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    }
    // Animatable rather than plain state: a hardware volume step is a jump of
    // 1/15th of the bar, which reads as a stutter unless it's tweened.
    val volume = remember {
        Animatable(
            (audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVolume,
        )
    }
    var volumeDragging by remember { mutableStateOf(false) }
    var systemVolume by remember { mutableFloatStateOf(volume.value) }

    // Glide to the level the system reports, but never fight the finger — a
    // drag writes the stream, which calls straight back through here.
    LaunchedEffect(systemVolume) {
        if (!volumeDragging) {
            volume.animateTo(systemVolume, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
    }

    // Hardware volume keys and the system panel change the stream behind our
    // back — watch Settings for changes so the bar tracks them live.
    DisposableEffect(audioManager) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
                systemVolume = current.toFloat() / maxVolume
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // 0 = the ordinary square sleeve, 1 = the artwork as a full-bleed banner.
    // Both states collapse the header, but the banner only ever shows over a
    // settled player: opening the queue or the lyrics hands the sleeve back its
    // card first.
    // How collapsed the sleeve is, whichever surface asked for it.
    // Unified under [transitionProgress.value] (p) so all layers move in lockstep.
    val fullBleedArt by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    // Full-bleed is a phone idiom, and a docked pane is a phone's width — so it
    // is asked of the player's own width rather than of the window's. Asking the
    // window is what left the pane with a square sleeve floating in a field of
    // backdrop: the window is wide, but the player in it never is.
    //
    // What the width has to rule out is a player running a foot wider than the
    // column of controls under it — edge to edge meaning "a picture, and
    // separately some controls" rather than "the artwork *is* the player". A pane
    // cannot do that; only the band between phone-width and dockable can, and
    // that is the band [fullBleedArtworkAvailable] excludes.
    //
    // One question for the still cover and the clip both, rather than two that
    // could disagree — and they did, twice over. Dissolving a TextureView's
    // bottom edge needs a RenderEffect, so below API 31 the clip was held in its
    // sleeve while the cover behind it went edge to edge, and the artwork
    // changed shape the moment a clip arrived. In the other direction the clip
    // ignored [fullBleedArt] entirely, so turning the setting off still left a
    // clip running the full screen. CanvasArtworkPlayer masks itself on every
    // API level now, and both layers answer to this.
    val heroMode = fullBleedArt && (docked || playerFillsWindow(windowWidth))
    // Whether there's a still image to blow out — a placeholder tile is a card
    // or it is nothing, and going full-bleed with one would just tint the top
    // third of the screen.
    //
    // Keyed on the artwork rather than on the track, because that is what it
    // actually describes and because only Coil can set it back to true. Two
    // tracks off one album share a cover, so skipping between them leaves the
    // request below byte-identical: the painter keeps the Success it already
    // had and never re-emits, so the `onState` that is the sole writer here
    // never fires again. Keyed on the track this reset to false and stayed
    // there, which pinned the sleeve fully opaque (see the alpha it feeds) on
    // top of an equally opaque banner — the same cover drawn twice, card and
    // full-bleed at once. Keyed on the cover there is nothing to reset: the
    // bitmap really is still loaded, so the state stays true and the two
    // layers go on trading places as they should.
    var artLoaded by remember(song.artworkAt(ART_PX)) { mutableStateOf(false) }
    // Sticky, unlike [artLoaded]: the banner is the shape of the player rather
    // than a property of the track in it. Waiting on each new cover would
    // collapse the banner into a card and blow it back out on every skip —
    // twice the length of the whole screen's worth of movement for a change the
    // artwork itself already announces. The frame stays; the cover arrives in
    // it, fading in as Coil fades in everywhere else.
    //
    // Latched off the clip as well as the still art, for a cover that never
    // arrives at all and leaves the banner standing on the clip alone: the clip
    // gives its frame up and takes it back every time the app leaves the screen,
    // and a banner that answered only to that would collapse behind the user's
    // back and blow itself out again in front of them on the way in.
    var heroSettled by remember { mutableStateOf(false) }
    LaunchedEffect(artLoaded, canvasRendered) {
        if (artLoaded || canvasRendered) heroSettled = true
    }
    // The clip that gets the banner, if any. Hoisted because the still frame
    // underneath keys its handover on exactly what is mounted here: both are
    // decided in the same composition pass, so opening the queue or the lyrics —
    // which takes the clip away — brings the still frame back in the very frame
    // the clip goes, instead of a frame later with the sleeve behind it still
    // transparent and no artwork anywhere.
    val heroClip = canvas?.takeIf { heroMode && p < 0.5f }
    // Whether the banner is the presentation at all: full-bleed is on, and there
    // is something to blow out. The collapse is deliberately *not* part of this
    // — see [heroVisible].
    val heroT by animateFloatAsState(
        targetValue = if (
            heroMode && (canvasRendered || artLoaded || heroSettled)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "heroCanvas",
    )

    /**
     * How much of the banner is actually on screen: its own fade, dissolved by
     * the collapse rather than after it.
     *
     * The collapse used to be a threshold on this animation's *target* — the
     * banner was told to go once [p] passed a half. That chained two 420ms
     * animations end to end when they should have been the same one: for the
     * first half of the collapse the banner sat at full size and full opacity
     * with nothing appearing to move, since the card shrinking behind it is
     * transparent while the banner is up; then the card finished collapsing and
     * a full-screen banner cross-dissolved into a finished thumbnail. Two sizes
     * of the same artwork on screen at once, which is what made every trip in
     * and out of the lyrics look wrong.
     *
     * Multiplied by the collapse instead, the banner goes as the card shrinks:
     * one movement, and the card is fading in the whole way down.
     */
    val heroVisible = heroT * (1f - p)
    // How tall that banner is, worked out down in the layout where the sleeve's
    // own geometry is known. Zero until the first measure, which is fine: there
    // is nothing to show that early either.
    var heroHeight by remember { mutableStateOf(0.dp) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // What sits between the status bar and the artwork: the drag strip in a
    // sheet, plain padding in a pane. Read in three places — the strip itself,
    // the scrim drawn over it and the banner's own height — which all have to
    // agree or the artwork and the credits under it move.
    val topStrip = if (docked) DOCKED_TOP_PAD else DISMISS_STRIP_HEIGHT

    Box(modifier = modifier.fillMaxSize()) {
        // Keyed on the track: the backdrop drifts when the player opens and on
        // every skip, then rests. Position ticks recompose this screen twice a
        // second and must not drag a full-screen blur along with them, which is
        // why the palette is passed as one immutable value.
        MeshGradientBackground(palette = meshColors, trackKey = song.videoId)

        // The artwork, edge to edge and running up behind the status bar,
        // dissolving into the backdrop where the sleeve's bottom edge would
        // have been. It lives out here rather than in the sleeve because that
        // is the only way to escape the player's side gutter and its status-bar
        // inset — a banner that stops short of either reads as a misplaced card
        // rather than as the artwork the screen is made of.
        if (heroHeight > 0.dp) {
            // The still sleeve first, so a clip fading in on top of it never
            // shows the backdrop through the gap between them — and only until
            // that fade has run. Both layers carry the same bottom gradient, so
            // a still frame left lit under a settled clip is not hidden by it:
            // down in the fade the clip is only part-opaque, and what shows
            // through it there is the cover art rather than the backdrop. That
            // is the artwork and the clip on screen at once.
            //
            // So it is dropped outright once the clip is opaque, rather than
            // held at alpha 0: nothing under a full-bleed clip is ever visible,
            // and a full-screen AsyncImage kept mounted for no one is a bitmap
            // and a layer the compositor still has to carry.
            //
            // Kept mounted through the handover in either direction rather than
            // dropped the moment [p] crosses the collapse threshold: the sleeve
            // behind it is still transparent at that point, so pulling the
            // banner straight out leaves a frame or two with no artwork anywhere
            // on screen before the card catches up.
            if (heroMode && !(stillCovered && heroClip != null) &&
                (p < 0.5f || heroVisible > 0.001f)
            ) {
                AsyncImage(
                    // Decoded at the same size the sleeve asks for, so the two
                    // share one entry in Coil's cache and one bitmap: the pair
                    // cross-fade into each other, and asking twice at two sizes
                    // would decode the same art twice and let the banner fade in
                    // before its own copy had arrived.
                    model = ImageRequest.Builder(context)
                        .data(song.artworkAt(ART_PX))
                        .size(ART_PX)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(heroHeight)
                        .graphicsLayer {
                            // Hands its opacity to the clip as the clip takes
                            // over, and takes it straight back if there is no
                            // clip mounted to hand it to.
                            alpha = heroVisible *
                                (1f - if (heroClip != null) canvasCover.floatValue else 0f)
                            // The mask below erases part of what this layer
                            // drew, which it can only do in a buffer of its own.
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startY = size.height * (1f - HERO_FADE_FRACTION),
                                    endY = size.height,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }

            // Motion artwork over it, in the same frame.
            //
            // Always composed while there's a clip to play, never gated on
            // [heroVisible]: the clip has to be mounted and decoding *before*
            // it can report the first frame that raises heroT in the first place.
            if (heroMode) {
                heroClip?.let { clip ->
                    CanvasArtworkPlayer(
                        canvas = clip,
                        isPlaying = isPlaying,
                        onRenderedChanged = { canvasRendered = it },
                        onFrameCaptured = { canvasFrame = it },
                        refreshFrameEveryMs = meshRefreshMs,
                        onCoverChanged = { canvasCover.floatValue = it },
                        bottomFade = HERO_FADE_FRACTION,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(heroHeight),
                    )
                }
            }

            // The clock, the signal bars and the drag handle are all white, and
            // the banner puts whatever the artwork happens to have up there
            // directly behind them — a bright frame or a pale sleeve leaves the
            // top of the screen unreadable. Faded in with the banner and gone
            // with it.
            if (heroVisible > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(statusBarTop + topStrip)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.38f * heroVisible),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .nestedScroll(swallowDownToSheet)
                .fullPlayerGestures(
                    enabled = sheetState == PanelSheetState.COLLAPSED && navMode == NowPlayingMode.FULL_PLAYER,
                    onSwipeUp = ::openQueue,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onDoubleTapSeek = { isForward ->
                        val currentSeek = currentSeekSeconds
                        val currentPos = currentPositionMs
                        val currentDur = currentDurationMs
                        doubleTapSeekDirection = if (isForward) 1 else -1
                        doubleTapSeekSeconds = currentSeek
                        doubleTapSeekTrigger = System.currentTimeMillis()
                        haptics.play(if (isForward) Haptic.SkipNext else Haptic.SkipPrevious)
                        val target = FullPlayerSeekCalculator.calculateTarget(
                            isForward = isForward,
                            currentPosMs = currentPos,
                            durationMs = currentDur,
                            seekAmountSeconds = currentSeek,
                            guardMs = SEEK_END_GUARD_MS,
                        )
                        currentOnSeek(target)
                    },
                    onHorizontalDragOffset = { dx ->
                        scope.launch {
                            if (dx != 0f) {
                                swipeDragOffset.snapTo((dx * 0.18f).coerceIn(-32f, 32f))
                            } else {
                                swipeDragOffset.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        }
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The only strip that passes drags through to the sheet, so the
            // player closes from the handle and the space around it — not from
            // a stray downward swipe on the artwork or the controls. Docked
            // there is no sheet to pass anything to, so all that is left of it
            // is the room it kept above the artwork.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topStrip)
                    .then(
                        if (sheetState != PanelSheetState.COLLAPSED && navMode == NowPlayingMode.QUEUE) {
                            Modifier.queueSwipeDown(
                                enabled = true,
                                listState = queueListState,
                                scope = scope,
                                allowScrollToTop = true,
                                consumeDownDeltas = true,
                                onClose = closeQueueToPlayer,
                            )
                        } else if (sheetState != PanelSheetState.COLLAPSED && navMode == NowPlayingMode.LYRICS) {
                            Modifier.lyricsSwipeDown(
                                enabled = true,
                                listState = lyricsListState,
                                scope = scope,
                                allowScrollToTop = true,
                                onClose = ::closeToFullPlayer,
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!docked) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = 8.dp)
                            .width(38.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.32f)),
                    )
                    song.radioName?.let { radioName ->
                        Text(
                            text = stringResource(R.string.playing_radio, radioName),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = PLAYER_GUTTER, end = PLAYER_GUTTER, bottom = 1.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = PLAYER_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // ---- Top and centre: artwork, then the credits ----
            // Everything that changes between the artwork and the queue lives
            // in this one weighted box, so the controls below it never move.
            // Read up here rather than down by the scrubber: the stats-for-nerds
            // line now lives inside the sleeve itself, so the art Box below
            // needs these before the seek bar does.
            val showNerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
            val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
            // Hoisted alongside the other two rather than read where it is drawn:
            // the stats block is inside a condition that flips as the sleeve
            // collapses, and re-subscribing to a flow on every frame of that
            // collapse is a waste of a subscription.
            val smartFadeOn by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
            val smartAnalysis by AppSettings.smartAnalysis.collectAsStateWithLifecycle()
            // Height the artwork block below turns out not to need, spent by the
            // controls at the foot of the screen. Filled in from inside the box,
            // where the sleeve's real size is known; see [lastControlSpread].
            var controlSpread by remember { mutableStateOf(lastControlSpread) }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = PLAYER_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(top = ART_BOX_TOP_PAD, bottom = 18.dp),
            ) {
                // The height this box would have if the controls at the foot of
                // the screen were at their natural size. They aren't: they are
                // holding [controlSpread] of extra gap, which came out of here,
                // so adding it back cancels the only thing down there that
                // depends on what is decided up here.
                //
                // The sleeve and the slack below are both worked out from this
                // rather than from the box as it actually stands, and that is
                // what keeps the hand-off from creeping. Measured off the real
                // height, granting the gaps 20dp came back as a box 20dp
                // shorter and read as a *further* 20dp going spare — so any
                // moment the controls were briefly shorter than usual (a track
                // change, where the lyric strip drops back to its loading line,
                // or coming back from the lyrics panel, where the strip is
                // rebuilt from scratch) was pocketed for good. The gaps
                // ratcheted open a little at a time and the sleeve paid for it.
                val roomy = maxHeight + if (lyricsOpen) 0.dp else controlSpread
                // The sleeve is square, so it is bounded by whichever of the
                // two axes runs out first: the player's width on a phone, or —
                // on a tablet, where there is width to spare — the height left
                // over once the credits row and the gap above it have had
                // theirs. Sizing it off the width alone is what pushed the
                // credits down across the scrubber on anything but a phone.
                val wantArt = minOf(maxWidth, roomy - ART_TITLE_GAP - HEADER_HEIGHT)
                // Held to what the box has actually got, for the single frame it
                // takes the gaps below to catch up with a change in their own
                // height: a sleeve a few dp under for one frame is a better
                // failure than a credits row overhanging the lyric strip.
                val fullArt = minOf(wantArt, maxHeight - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(THUMB_SIZE)
                // What's left over once the sleeve, the gap and the credits have
                // had theirs. A few dp on a phone; the better part of a
                // centimetre on anything taller, and since the group is centred,
                // half of it used to land between the credits and the lyric strip
                // as one wide hole in the middle of the controls.
                val slack = (roomy - wantArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp)
                // Handed to the two gaps around the transport row instead, which
                // is where a tall screen should be doing its breathing.
                //
                // Assigned, not added to: [slack] is stated in terms the spread
                // cannot move, so this is the whole answer in one step, and it
                // gives the room back just as readily when the controls grow
                // into it again.
                //
                // Left alone while the lyrics panel is up: the spacers it feeds
                // aren't in the tree then, so there would be nothing to apply it.
                //
                // Granted in whole even pixels, and only when it actually moves.
                // This is a measurement feeding the layout it was measured from,
                // and [roomy] cancels that by adding the grant back — but only if
                // this pass's [maxHeight] already reflects the grant about to be
                // written, which needs the Column above to have re-measured the
                // controls at that grant already. It doesn't always have: on some
                // aspect ratios (a phone-shaped sheet as readily as a docked pane)
                // the cancellation lands a pass late, the grant overshoots, the
                // next pass corrects past it the other way, and the two chase
                // each other through the same handful of values forever instead
                // of settling — a full-amplitude standing oscillation, not the
                // single-pixel shiver this rounding alone was built to absorb.
                // See [granted] below for the fix.
                if (!lyricsOpen) {
                    val target = with(density) {
                        val half = slack
                            .coerceAtMost(CONTROL_GAP_SPREAD_MAX * 2)
                            .toPx()
                            .div(2f)
                            .roundToInt()
                        (half * 2).toDp()
                    }
                    // Stepped towards [target] rather than jumped there in one
                    // grant, so a late cancellation (see above) decays instead of
                    // standing: still one pass to settle when the cancellation
                    // does land on time, and a fast-converging approach rather
                    // than a full-amplitude swing on the passes where it doesn't.
                    val granted = with(density) {
                        val steppedPx = (controlSpread.toPx() +
                            (target.toPx() - controlSpread.toPx()) * 0.4f)
                            .roundToInt()
                        steppedPx.toDp()
                    }
                    if (granted != controlSpread) {
                        SideEffect {
                            controlSpread = granted
                            lastControlSpread = granted
                        }
                    }
                }
                // Artwork and the title row travel together as one block, so
                // the pair sits centred while the queue is closed — in whatever
                // the controls couldn't take, which on all but the tallest
                // screens is nothing.
                val groupTop = (maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp) / 2
                val artSize = lerp(fullArt, THUMB_SIZE, p)
                val artTop = lerp(groupTop, 0.dp, p)
                // Expanded and height-bound, the sleeve is narrower than the
                // player and has to be centred in it; collapsed, it belongs
                // hard against the left edge with the credits beside it.
                val artStart = lerp((maxWidth - fullArt) / 2, 0.dp, p)
                val titleTop = lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p)
                val titleStart = lerp(0.dp, THUMB_SIZE + 12.dp, p)

                // How far down the *screen* the sleeve's bottom edge sits, which
                // is where the full-bleed banner has to stop for the credits
                // below it not to move when it appears. Everything between the
                // screen's top and this box's own top is fixed padding, so it
                // can simply be added back up rather than measured.
                val bannerBottom = statusBarTop + topStrip + ART_BOX_TOP_PAD +
                    groupTop + fullArt + ART_TITLE_GAP / 2
                // Guarded, like the spread above: this runs on every pass, and a
                // state write from inside a layout is a recomposition asked for
                // from inside a layout. Writing the same answer back costs a
                // comparison here and a whole frame if it is left to the snapshot
                // to notice.
                if (bannerBottom != heroHeight) {
                    SideEffect { heroHeight = bannerBottom }
                }

                if (queueOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .queueSwipeDown(
                                enabled = true,
                                listState = queueListState,
                                scope = scope,
                                allowScrollToTop = true,
                                consumeDownDeltas = true,
                                onClose = closeQueueToPlayer,
                            ),
                    )
                }

                // Empty state lives on this Box, not the AsyncImage: a
                // background *and* a painter both trying to fill the same
                // clipped shape is what read as two overlapping squares
                // whenever there was nothing to paint. One layer, one square.
                // [artLoaded] is hoisted to the screen, where the banner needs
                // it too.
                Box(
                    modifier = Modifier
                        // The lambda overload deliberately: the Dp one reads
                        // its arguments at composition, so an animated offset
                        // recomposes and re-measures this Box — cover, clip and
                        // all — once per frame. Read at placement instead, the
                        // same movement costs a placement pass.
                        .offset { IntOffset(artStart.roundToPx(), artTop.roundToPx()) }
                        .size(artSize)
                        // Where the dismiss band starts. Read here, above the
                        // paused shrink below, so the band covers the sleeve's
                        // slot rather than the 86% of it that is drawn while
                        .graphicsLayer {
                            // The paused shrink only makes sense on the full sleeve.
                            val idle = artScale + (1f - artScale) * p
                            scaleX = idle
                            scaleY = idle
                            translationX = swipeDragOffset.value
                        }
                        // Collapsed, the sleeve is the way back: tapping the
                        // thumbnail puts the queue or the lyrics away again.
                        .then(
                            if (sheetState != PanelSheetState.COLLAPSED) {
                                if (navMode == NowPlayingMode.QUEUE) {
                                    Modifier.queueSwipeDown(
                                        enabled = true,
                                        listState = queueListState,
                                        scope = scope,
                                        allowScrollToTop = true,
                                        consumeDownDeltas = true,
                                        onClose = closeQueueToPlayer,
                                    ).clickable {
                                        closeToFullPlayer()
                                    }
                                } else {
                                    Modifier.clickable {
                                        closeToFullPlayer()
                                    }
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // The sleeve proper. Separated from the box around it so
                    // the banner can dissolve the card — shadow, corners, tile
                    // and all — without taking the stats line with it.
                    //
                    // Held fully opaque until this track's own art is in,
                    // regardless of [heroT]: the banner is sticky across skips
                    // by design (see [heroSettled]), but its still image is not
                    // — a new track's cover has to come from somewhere while
                    // the banner waits on Coil, and the sleeve underneath,
                    // with its loading icon, is that somewhere. Once
                    // [artLoaded] catches up the two are showing the same
                    // bitmap, so hiding one behind the other is invisible.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (artLoaded) 1f - heroVisible else 1f }
                            // A drop shadow grounds a photo; on the flat
                            // placeholder tile it has nothing to sit behind, so
                            // it just reads as a second, darker square ringing
                            // the first. Only cast it once there's actually art.
                            .shadow(
                                if (artLoaded) lerp(14.dp, 6.dp, p) else 0.dp,
                                RoundedCornerShape(lerp(10.dp, 7.dp, p)),
                            )
                            .clip(RoundedCornerShape(lerp(10.dp, 7.dp, p)))
                            .background(Color.Black.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!artLoaded) {
                            Icon(
                                imageVector = YZMusicIcons.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(lerp(40.dp, 20.dp, p)),
                            )
                        }
                        AsyncImage(
                            // Decode at the sleeve's *expanded* size, always.
                            // Coil otherwise sizes the decode to however large
                            // this is when the request goes out — and changing
                            // track from the queue does that while the sleeve is
                            // collapsed to a thumbnail, leaving a thumbnail-sized
                            // bitmap to be blown back up when the queue closes.
                            // Skipping tracks with the transport keeps it sharp
                            // only because the sleeve happens to be full size at
                            // that moment.
                            //
                            // Asked for at the source's own size rather than the
                            // sleeve's: it is the same request the full-bleed
                            // banner makes, and the banner is taller than the
                            // sleeve is wide. One ask, one decode, one bitmap for
                            // both — and nothing to upscale when the two swap.
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(song.artworkAt(ART_PX))
                                .size(ART_PX)
                                .build(),
                            contentDescription = null,
                            // Video thumbnails are 16:9; letterboxing them inside
                            // the square sleeve looks like a broken frame.
                            contentScale = ContentScale.Crop,
                            onState = { artLoaded = it is AsyncImagePainter.State.Success },
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Where the clip plays when it can't have the banner:
                        // inside the same clip as the still art, taking the
                        // sleeve's corners, shadow and paused shrink for free.
                        if (!heroMode) {
                            canvas?.takeIf { p < 0.5f }?.let { clip ->
                                CanvasArtworkPlayer(
                                    canvas = clip,
                                    isPlaying = isPlaying,
                                    onRenderedChanged = { canvasRendered = it },
                                    onFrameCaptured = { canvasFrame = it },
                                    refreshFrameEveryMs = meshRefreshMs,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    // Measured stats, pinned to the sleeve's own bottom-centre
                    // rather than squeezed under the seek bar with the
                    // "Lossless" badge — the badge is a claim, this is the
                    // evidence, and the two no longer swap for each other on a
                    // tap. Fades out with the sleeve as it collapses to a
                    // thumbnail, where there's no room to read it anyway.
                    if (showNerdStats && p < 0.5f) {
                        // A plain white line reads fine over the usual dark
                        // tile, but a light stretch of an animated cover — sky,
                        // snow, a pale sleeve — washes it out entirely. The
                        // shadow costs nothing on a dark background and is what
                        // keeps it legible on a bright one.
                        val nerdStyle = MaterialTheme.typography.labelSmall.copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.55f),
                                offset = Offset(0f, 1f),
                                blurRadius = 4f,
                            ),
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .graphicsLayer { alpha = 1f - p * 2f },
                        ) {
                            nerdStats?.describe()?.let { stats ->
                                Text(
                                    text = stats,
                                    style = nerdStyle,
                                    color = Color.White.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            // Only when Automix is actually switched on:
                            // otherwise this would report on analysis nothing is
                            // going to use, which is noise rather than a stat.
                            if (smartFadeOn) {
                                Text(
                                    // Both sides always named, even when they
                                    // agree, so the line reads the same way every
                                    // time and the eye can find the half it wants
                                    // without re-parsing the sentence.
                                    text = "Automix · this song " +
                                        smartAnalysis.current.label() +
                                        " · next " + smartAnalysis.next.label(),
                                    style = nerdStyle,
                                    // Dimmer than the measured line above it: that
                                    // one describes the audio, this one describes
                                    // the app, and the ranking should show.
                                    color = Color.White.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    // Double-tap seek visual feedback (subtle pill on the tapped side)
                    val seekDir = doubleTapSeekDirection
                    if (seekDir != null && p < 0.3f) {
                        val isFwd = seekDir > 0
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.5f)
                                .align(if (isFwd) Alignment.CenterEnd else Alignment.CenterStart),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black.copy(alpha = 0.45f))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                if (!isFwd) {
                                    Icon(
                                        imageVector = Icons.Rounded.FastRewind,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    text = "${doubleTapSeekSeconds}s",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = Color.White.copy(alpha = 0.9f),
                                )
                                if (isFwd) {
                                    Icon(
                                        imageVector = Icons.Rounded.FastForward,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.85f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- Title + menu ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = titleTop)
                        .padding(start = titleStart)
                        .height(HEADER_HEIGHT)
                        .then(
                            if (queueOpen) {
                                Modifier.queueSwipeDown(
                                    enabled = true,
                                    listState = queueListState,
                                    scope = scope,
                                    allowScrollToTop = true,
                                    consumeDownDeltas = true,
                                    onClose = closeQueueToPlayer,
                                )
                            } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // Shrinks as the header collapses, so the queue's
                        // heading doesn't have to compete with it.
                        val titleSize = lerp(20.sp, 16.sp, p)
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = titleSize,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Only the tracks YouTube hands us a browse id for
                            // lead anywhere; the rest stay plain text.
                            modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.opensPage(song.artistId, onOpenArtist),
                        ) {
                            if (song.isExplicit == true) {
                                ExplicitBadge(
                                    color = Color.White.copy(alpha = 0.8f),
                                    backgroundColor = Color.White.copy(alpha = 0.18f),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.W500,
                                    fontSize = titleSize,
                                ),
                                color = Color.White.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // Beside the credits rather than down in the toggle row:
                    // liking is about *this song*, and the row below is about
                    // how the queue plays. Guests get nothing to tap, since
                    // there's no account to record it against — and neither
                    // does a local file or a finished download, which carries
                    // no YouTube identity to rate.
                    if (signedIn && song.localUri == null) {
                        val liked = likeStatus == LikeStatus.LIKE
                        CircleGlyph(
                            icon = if (liked) YZMusicIcons.HeartFilled else YZMusicIcons.Heart,
                            contentDescription = if (liked) "Remove from Liked Music" else "Like",
                            onClick = onToggleLike,
                            active = liked,
                            haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (navMode == NowPlayingMode.LYRICS && showLyricsLogsEnabled) {
                        CircleGlyph(
                            icon = Icons.Rounded.History,
                            contentDescription = "Lyrics Logs",
                            onClick = { lyricsLogsOpen = !lyricsLogsOpen },
                            active = lyricsLogsOpen,
                            haptic = Haptic.Tap,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    CircleGlyph(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = "More",
                        onClick = onOpenMenu,
                    )
                }

                if (navMode == NowPlayingMode.LYRICS && p > 0.01f) {
                    if (lyricsLogsOpen) {
                        LyricsLogConsole(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = HEADER_HEIGHT + 10.dp)
                                .graphicsLayer {
                                    alpha = ((p - 0.20f) / 0.80f).coerceIn(0f, 1f)
                                    translationY = (1f - p) * 32.dp.toPx()
                                },
                        )
                    } else {
                        LyricsPanel(
                            // The singer's lines, each already carrying whichever
                            // second voice is switched on. The layer rides along
                            // underneath rather than replacing anything, so
                            // turning one on never moves the song or the scroll.
                            rows = displayRows.orEmpty(),
                            // Null and empty reach the panel as the same empty
                            // list, so it cannot tell a lookup still in flight
                            // from a lookup that came back with nothing. That
                            // answer already exists upstream and is passed with
                            // the rest: false here means the words have not been
                            // looked for yet, not that there are none.
                            unavailable = lyricsUnavailable,
                            trackKey = song.videoId,
                            // The offset shifts what is *drawn*, and a tap on a
                            // line seeks back out through the same correction:
                            // the reader moved the words because they were in
                            // the wrong place, so "play me that line" has to
                            // mean the line where they actually see it.
                            // A larger offset draws a line later, and both
                            // helpers floor at zero so an offset past the start
                            // of the song cannot seek outside it.
                            positionMs = adjustedLyricsPosition(positionMs, lyricsOffsetMs.toInt()),
                            isPlaying = isPlaying,
                            onSeekToLine = { lineMs ->
                                onSeek(adjustedLyricsSeekTarget(lineMs, lyricsOffsetMs.toInt()))
                            },
                            activeLayer = lyricsDisplayMode,
                            availableModes = availableLyricsModes,
                            onToggleLayer = onLyricsLayerToggle,
                            translationState = lyricsTranslation,
                            romanizationState = lyricsRomanization,
                            canTranslate = canTranslateLyrics,
                            targetLanguageTag = lyricsTargetLanguage,
                            onTranslate = translateLyrics,
                            onClose = ::closeToFullPlayer,
                            listState = lyricsListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = HEADER_HEIGHT + 10.dp)
                                .graphicsLayer {
                                    alpha = ((p - 0.20f) / 0.80f).coerceIn(0f, 1f)
                                    translationY = (1f - p) * 32.dp.toPx()
                                },
                        )
                    }
                }

                if (navMode == NowPlayingMode.QUEUE && p > 0.01f) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = HEADER_HEIGHT + 10.dp)
                            .graphicsLayer {
                                alpha = ((p - 0.20f) / 0.80f).coerceIn(0f, 1f)
                                translationY = (1f - p) * 32.dp.toPx()
                            },
                    ) {
                        InlineQueue(
                            queue = queue,
                            currentIndex = queueIndex,
                            autoplayEnabled = autoplayEnabled,
                            onJumpTo = onJumpTo,
                            onRemove = onRemoveFromQueue,
                            onMove = onMoveInQueue,
                            onClear = onClearQueue,
                            onClose = closeQueueToPlayer,
                            listState = queueListState,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- Bottom: lyric strip, scrubber, transport, volume, toggles ----
            // One block, measured at its natural height and pinned to the foot
            // of the player. Whatever is left over above it is the artwork's,
            // which is what keeps this row of controls in the same place on
            // every screen instead of being shoved off the bottom of a tall one.
            Column(
                modifier = Modifier
                    .widthIn(max = PLAYER_MAX_WIDTH)
                    .fillMaxWidth()
                    .then(
                        if (queueOpen) {
                            Modifier.queueSwipeDown(
                                enabled = true,
                                listState = queueListState,
                                scope = scope,
                                allowScrollToTop = true,
                                consumeDownDeltas = true,
                                onClose = closeQueueToPlayer,
                            )
                        } else Modifier
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // Current lyric, one line, directly above the scrubber. It stays in
            // the layout — and stays fully visible — whether or not the queue
            // is open: dropping it would shorten this block and the controls
            // under it would jump the moment the queue started sliding in, and
            // fading it away behind the queue left this the one place in the
            // player where the current line simply vanished.
            //
            // Switched off in Settings it goes entirely, rather than sitting
            // there saying no lyrics were found: none were looked for. It is
            // also the only way into the full lyrics panel, so with it gone
            // the feature is properly gone.
            if (!lyricsOpen && syncedLyricsEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The slider's touch target reaches ~13dp above the
                        // drawn bar, so the strip reads as further off it than
                        // it is. Nudged down into that dead space, the same way
                        // the timestamps below are pulled back up into it.
                        .offset(y = 6.dp),
                ) {
                    if (!lyrics.isNullOrEmpty()) {
                        CurrentLyricLine(
                            lines = lyrics,
                            trackKey = song.videoId,
                            positionMs = positionMs,
                            isPlaying = isPlaying,
                            // No duration here: the strip reads the clock
                            // itself and runs off the end of the song into its
                            // last line, which is what a finished track has
                            // left to show.
                            // Still visible over the queue, so still a valid way
                            // in: opens the same full lyrics panel it always has,
                            // closing the queue behind it the same way the "Up
                            // next" glyph closes lyrics behind the queue.
                            onClick = ::openLyrics,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (lyricsUnavailable) {
                        LyricsUnavailableLine(
                            trackKey = song.videoId,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LyricsLoadingLine(
                            // One of the pool, picked per track and held. A
                            // bare "Lyrics" here reads as a label for whatever
                            // lands underneath it, which is the opposite of
                            // what this line is for.
                            text = remember(song.videoId) { LYRICS_LOADING_LINES.random() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            val mixing by AppSettings.smartMixInProgress.collectAsStateWithLifecycle()
            val transitionWindow by AppSettings.smartTransitionWindow.collectAsStateWithLifecycle()
            ThinSlider(
                value = shown,
                onValueChange = {
                    scrubbing = true
                    scrubValue = it
                },
                onValueChangeFinished = {
                    // On release only. Ticking the whole way along the bar turns
                    // a scrub into a rattle, and the beat that matters is the one
                    // that says where the playhead landed.
                    haptics.play(Haptic.Select)
                    pendingSeek = scrubValue
                    onSeekFraction(scrubValue)
                    scrubbing = false
                },
                // Suppressed under the finger: the bar is already thickening and
                // tracking a drag, and a sheen sweeping through that reads as a
                // rendering glitch rather than as a signal.
                mixing = mixing && !scrubbing,
                // Hidden while scrubbing for the same reason as the sheen: the
                // planner is still describing where the transition *would* be,
                // and a marker sitting under a finger that is moving the
                // playhead invites reading it as a drag target.
                transitionWindow = transitionWindow
                    ?.takeIf { !scrubbing && it.end > it.start }
                    ?.let { it.start..it.end },
            )
            val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
            val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
            val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
            // Whether this playback session is even asking for a lossless
            // stream — the same computation SourceResolver.requestForNow()
            // makes, mirrored here so "Loading lossless" only appears when a
            // lossless fetch is actually in flight, not on every buffering
            // YouTube track.
            val losslessRequested =
                (if (metered == true) cellularQuality else wifiQuality) == AudioQuality.HIGH
            // Whether a module is still racing YouTube for this exact track —
            // see [NerdStats.racingLossless]. YouTube can win that race and
            // already be playing while the module lookup is still running
            // detached in the background, and the badge should keep saying
            // "loading" through that stretch rather than going blank only to
            // possibly say "loading" again a moment later.
            val racingLossless by NerdStats.racingLossless.collectAsStateWithLifecycle()
            val stillRacing = song.videoId in racingLossless
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // The slider's touch target extends well past the drawn
                    // bar, so pull the labels back up under it.
                    .offset(y = (-9).dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatTime((shown * durationMs).toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                    Text(
                        text = "-" + formatTime(durationMs - (shown * durationMs).toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
                // Pinned to the box's own center rather than squeezed into the
                // gap between the two timestamps: that gap's width changes by
                // a digit's worth every time a minute rolls over, which was
                // dragging this along with it every tick. The screen's center
                // doesn't move.
                LosslessOrStats(
                    isLoading = isLoading,
                    stillRacing = stillRacing,
                    losslessRequested = losslessRequested,
                    nerdStats = nerdStats,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                )
            }

            if (lyricsOpen) {
                Spacer(Modifier.height(16.dp))
                // The credit, and beside it the way out. Tapping the sleeve
                // above also closes the panel, but that is an invisible target
                // you have to be told about; the button says so. With four
                // databases behind the panel, whose timings you are looking at
                // is worth the room the credit takes next to it.
                Row(
                    // Measured at the pill's own height so the button can be
                    // sized off it rather than off a number that happens to
                    // match today: the pill is as tall as the label's line
                    // height plus its padding, which moves with the font scale,
                    // and the circle has to keep matching it when it does.
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.10f))
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            // A missing source and missing lyrics are not the
                            // same thing: lyrics read back out of a downloaded
                            // file have no service to credit, and billing those
                            // as "No lyrics found" said the opposite of what
                            // the screen was showing.
                            text = when {
                                lyricsSource != null -> "Lyrics by ${lyricsSource.label}"
                                lyrics.isNullOrEmpty() -> "No lyrics found"
                                else -> "Lyrics saved with this download"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            // Height from the row, width from the height: a
                            // circle, not an oval, whatever the pill measures.
                            .fillMaxHeight()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                closeToFullPlayer()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close lyrics",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            } else {

            // The transport rides midway between the two blocks it separates:
            // the scrubber above it, and the volume bar and toggle row below,
            // which sit close enough together to read as one. Both of its own
            // gaps take half the spread, so on a tall screen it holds the
            // centre rather than drifting up under the seek bar.
            Spacer(Modifier.height(14.dp + controlSpread / 2))

            // ---- Transport ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportGlyph(
                    icon = Icons.Rounded.FastRewind,
                    contentDescription = "Previous",
                    size = 46.dp,
                    onClick = onPrevious,
                    // Lit whenever back has something to do — either a track to
                    // step to, or enough elapsed for it to restart this one.
                    enabled = hasPrevious || positionMs > BACK_RESTARTS_AFTER_MS,
                    haptic = Haptic.SkipPrevious,
                )
                // While the stream URL resolves and buffers, the play glyph
                // would be a lie — show progress instead.
                if (isLoading) {
                    // Same footprint as TransportGlyph(62.dp) — a smaller box
                    // here would shunt everything below it on every load.
                    Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                } else {
                    TransportGlyph(
                        icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        size = 62.dp,
                        onClick = onPlayPause,
                        haptic = if (isPlaying) Haptic.Pause else Haptic.Resume,
                    )
                }
                TransportGlyph(
                    icon = Icons.Rounded.FastForward,
                    contentDescription = "Next",
                    size = 46.dp,
                    onClick = onNext,
                    enabled = hasNext,
                    haptic = Haptic.SkipNext,
                )
            }

            // Hidden entirely rather than just faded out — with the setting
            // on, the slider takes up no space at all, so the transport and
            // the toggle row below it close the gap instead of leaving a
            // blank strip where the volume bar used to be.
            if (hideVolumeBar) {
                Spacer(Modifier.height(24.dp + controlSpread / 2))
            } else {
                Spacer(Modifier.height(18.dp + controlSpread / 2))

                // ---- Volume ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    ThinSlider(
                        value = volume.value,
                        onValueChange = {
                            volumeDragging = true
                            // Follow the finger exactly; only external changes tween.
                            scope.launch { volume.snapTo(it) }
                            audioManager?.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                (it * maxVolume).roundToInt(),
                                0,
                            )
                        },
                        onValueChangeFinished = { volumeDragging = false },
                        idleHeight = 6.dp,
                        activeHeight = 10.dp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))
            }

            // ---- Shuffle · Repeat · Download · Queue ----
            // These live here rather than in the queue panel so their state is
            // readable without opening anything.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomGlyph(
                    icon = YZMusicIcons.Shuffle,
                    contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                    onClick = onToggleShuffle,
                    highlighted = shuffleEnabled,
                    haptic = if (shuffleEnabled) Haptic.ToggleOff else Haptic.ToggleOn,
                )
                BottomGlyph(
                    icon = if (repeatMode == Player.REPEAT_MODE_ONE) null else YZMusicIcons.Repeat,
                    label = if (repeatMode == Player.REPEAT_MODE_ONE) "1" else null,
                    contentDescription = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        else -> "Repeat off"
                    },
                    onClick = onCycleRepeat,
                    highlighted = repeatMode != Player.REPEAT_MODE_OFF,
                    // Three states, so the buzz tracks the edges of the cycle:
                    // leaving off rises, returning to off falls, and the step
                    // between the two repeat modes is just a selection.
                    haptic = when (repeatMode) {
                        Player.REPEAT_MODE_OFF -> Haptic.ToggleOn
                        Player.REPEAT_MODE_ONE -> Haptic.ToggleOff
                        else -> Haptic.Select
                    },
                )
                val isDownloaded = savedDownloads.containsKey(song.videoId)
                val activeDownload = activeDownloads[song.videoId]
                val downloadDesc = when {
                    isDownloaded -> "Saved to Downloads"
                    activeDownload is DownloadState.Running -> {
                        if (activeDownload.fraction > 0f) "Downloading ${(activeDownload.fraction * 100).toInt()}%" else "Downloading"
                    }
                    activeDownload is DownloadState.Queued -> "Download queued"
                    activeDownload is DownloadState.Failed -> "Download failed, retry"
                    else -> "Download"
                }
                BottomGlyph(
                    icon = if (activeDownload is DownloadState.Running || activeDownload is DownloadState.Queued) null else when {
                        isDownloaded -> Icons.Rounded.DownloadDone
                        activeDownload is DownloadState.Failed -> Icons.Rounded.Download
                        else -> Icons.Rounded.Download
                    },
                    contentDescription = downloadDesc,
                    onClick = onDownload,
                    highlighted = false,
                    haptic = Haptic.Tap,
                    customContent = if (activeDownload is DownloadState.Running) {
                        {
                            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                if (activeDownload.fraction > 0f) {
                                    CircularProgressIndicator(
                                        progress = { activeDownload.fraction },
                                        color = Color.White,
                                        strokeWidth = 2.5.dp,
                                        trackColor = Color.White.copy(alpha = 0.25f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    } else if (activeDownload is DownloadState.Queued) {
                        {
                            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    color = Color.White.copy(alpha = 0.75f),
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    } else null,
                )
                BottomGlyph(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "Up next",
                    onClick = {
                        if (navMode == NowPlayingMode.QUEUE && sheetState != PanelSheetState.COLLAPSED) {
                            closeToFullPlayer()
                        } else {
                            openQueue()
                        }
                    },
                    highlighted = navMode == NowPlayingMode.QUEUE && sheetState != PanelSheetState.COLLAPSED,
                    haptic = if (navMode == NowPlayingMode.QUEUE && sheetState != PanelSheetState.COLLAPSED) Haptic.Tap else Haptic.Expand,
                )
            }

            Spacer(Modifier.height(18.dp))
            }
            }
            }
        }
    }
}



@Composable
private fun LyricsPanel(
    rows: List<LyricDisplayRow>,
    trackKey: Any,
    /**
     * True only once a lookup has come back and found nothing. False while one
     * is still out, which is the whole difference between the shimmer and the
     * empty state — [rows] cannot express it, being empty either way.
     */
    unavailable: Boolean = false,
    positionMs: Long,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    onClose: () -> Unit = {},
    listState: LazyListState = remember(trackKey) { LazyListState() },
    activeLayer: MainViewModel.LyricsDisplayMode = MainViewModel.LyricsDisplayMode.ORIGINAL,
    availableModes: Set<MainViewModel.LyricsDisplayMode> = setOf(MainViewModel.LyricsDisplayMode.ORIGINAL),
    onToggleLayer: (MainViewModel.LyricsDisplayMode) -> Unit = {},
    translationState: LyricsTranslationState = LyricsTranslationState.Idle,
    romanizationState: MainViewModel.RomanizationState = MainViewModel.RomanizationState.Idle,
    /** False for a track the engine would refuse outright — nothing to offer. */
    canTranslate: Boolean = false,
    targetLanguageTag: String = "",
    onTranslate: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {

    // The panel is driven off the primary line of every row; the second voice
    // rides along underneath and never changes which line is playing.
    val lines = remember(rows) { rows.map { it.primary } }
    val clock = rememberLyricClock(trackKey, positionMs, isPlaying)

    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    // Only a song that actually names a second voice is laid out as one. A
    // single-voice song has every line on the left already, so splitting the
    // panel into lanes for it would only be a narrower panel.
    val duet = remember(lines) { lines.any { it.alignment == LyricAlignment.End } }

    // Every unfinished vocal, not just the newest. A backing line routinely
    // holds past the next lead line's stamp, and more than two can overlap at
    // once, so chasing a fixed window behind the clock misses the third voice
    // of a chorus written as three. One lookup over the lines instead of a
    // fixed window behind the index, which is also why this is recomputed as
    // the set changes rather than as a function of the newest one.
    val activeRows by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) emptyList() else activeLyricRows(lines, clock.longValue)
        }
    }
    // The uppermost unfinished vocal owns the scroll anchor until its end, even
    // as later rows begin their own independent highlight animations.
    val scrollLine = activeRows.firstOrNull() ?: -1
    // Where the panel is heading, which is a beat ahead of where the singing is.
    // Movement that starts on the downbeat arrives after it — the line is
    // already being sung by the time it settles, and you read it late. Started
    // during the run-up instead, the words are under your eye when they land.
    //
    // Kept apart from [scrollLine] on purpose: this leads, and the sweep must
    // not. Everything lit by the clock still goes through the real one.
    val leadLine by remember(lines, isSynced) {
        derivedStateOf {
            if (!isSynced) {
                -1
            } else {
                val now = clock.longValue
                activeLyricRows(lines, now + scrollLead(lines, now)).firstOrNull() ?: -1
            }
        }
    }
    // What the stack arranges itself around. The outgoing line starts dimming
    // as the panel leaves it rather than when its last word ends, so the dim,
    // the blur and the movement are one gesture instead of three that happen
    // to start together.
    val focusLine = if (leadLine >= 0) leadLine else scrollLine

    var browsing by remember(trackKey) { mutableStateOf(false) }
    var placed by remember(trackKey, lines) { mutableStateOf(false) }
    // The handover in flight. [ScrollRun] carries its own id so a repeat of the
    // same line by the same distance is still a new run: a chorus that brings
    // back the line it was on a moment ago moves the panel for exactly the
    // number of pixels it moved last time, and without the id that would look
    // like no movement at all.
    var run by remember(trackKey, lines) { mutableStateOf<ScrollRun?>(null) }
    // How long ago this run started, on the frame clock. Stamped once when the
    // run starts rather than re-derived from the playhead: a run outlives the
    // line it is moving to, and deriving its start from where the singing has
    // since got to would slide the stagger underneath the words.
    var since by remember(trackKey) { mutableLongStateOf(0L) }

    // What the panel draws under each line, and how far it has opened. The
    // rows are already paired and already filtered by `pairLyricLayers`, so
    // this only animates them in and out of the gap between the two voices.
    val subReveal = rememberSubLyricsReveal(
        if (activeLayer == MainViewModel.LyricsDisplayMode.ORIGINAL) {
            null
        } else {
            rows.map { it.sub }
        },
        trackKey.toString(),
    )

    // Whether the panel is scrolling itself to follow the song, and whether
    // the controls are up. The fade below ignores the panel's own scrolling:
    // a line landing is not somebody reading on, and the controls must never
    // vanish because the music moved.
    var autoScrolling by remember(trackKey) { mutableStateOf(false) }
    var controlsShown by remember(trackKey) { mutableStateOf(true) }

    // One position for the whole list, in pixels, so a change of row and a
    // change of offset inside a row are the same kind of movement. Without
    // this the two arrive as different units and a direction measured across
    // them is meaningless.
    val averageRowPx by remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val span = visible.size
            if (span == 0) 0f else visible.sumOf { it.size }.toFloat() / span
        }
    }
    val listPositionPx by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex.toFloat() * averageRowPx +
                listState.firstVisibleItemScrollOffset
        }
    }

    // Reading on puts the controls away; reading back brings them out.
    //
    // Observed, never intercepted. The list already carries a scroll
    // connection of its own to swallow the downward overscroll the player
    // sheet would otherwise read as a dismissal, plus the swipe-down that
    // closes the panel. Adding a third link to that chain to drive a fade
    // would be a change to how scrolls resolve, and the one thing this
    // screen cannot afford is a scroll that behaves differently because a
    // label is animating. Reading the list's own position costs nothing and
    // cannot move a single pixel of it.
    val controlsSlopPx = with(LocalDensity.current) { CONTROLS_SCROLL_SLOP.toPx() }
    LaunchedEffect(listState, controlsSlopPx) {
        var last = listPositionPx
        var travel = 0f
        snapshotFlow {
            Triple(listState.isScrollInProgress, listPositionPx, autoScrolling)
        }.collect { (dragging, now, selfDriven) ->
            val delta = now - last
            last = now
            if (!dragging || selfDriven) {
                travel = 0f
                return@collect
            }
            // Deltas arrive a few pixels at a time, so what counts is the run
            // of them in one direction — and that run resets the moment the
            // finger changes its mind, so a scroll that wanders cannot bank
            // its way to the wrong answer.
            if (travel != 0f && (travel > 0f) != (delta > 0f)) travel = 0f
            travel += delta
            when {
                // Position rising is the reader going forward through the
                // song, which is how you read. That is when the controls are
                // in the way; reading back is when they are wanted again.
                travel >= controlsSlopPx -> {
                    travel = 0f
                    controlsShown = false
                }

                travel <= -controlsSlopPx -> {
                    travel = 0f
                    controlsShown = true
                }
            }
        }
    }

    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    // The bloom is a blurred copy of the line, so it is off wherever blur is:
    // below API 31 Modifier.blur does nothing and the "glow" would land as a
    // second sharp copy of the text — fake bold, not light. Both of the
    // reduce-* settings turn it off too. Reduce animation because it is the
    // switch for exactly this kind of flourish, and reduce dynamic blur
    // because adding a blur under a setting that says it drops them would be
    // the app disagreeing with itself.
    val glowing = !reduceAnimation && !reduceDynamicBlur && lyricsBlur &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Track user drag interaction to enter browsing mode.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                browsing = true
            }
        }
    }

    // Hand control back when the user stops interacting:
    // 1. If the user scrolled back to the active line (or never left it):
    //    Wait a comfortable settle delay (2s) so we don't fight intentional reading/nudging,
    //    then naturally resume live follow.
    // 2. If the user scrolled away:
    //    Give them 5s of idle reading time before safely resuming live follow.
    // Both timers only run while music is playing and all scrolling has settled.
    val currentLine by rememberUpdatedState(focusLine)
    val activeOnScreen by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.index == currentLine }
        }
    }
    LaunchedEffect(browsing, activeOnScreen, listState.isScrollInProgress, isPlaying) {
        if (browsing && !listState.isScrollInProgress && isPlaying) {
            val timeoutMs = if (activeOnScreen) 2_000L else 5_000L
            delay(timeoutMs)
            browsing = false
        }
    }

    // Follow the song, keeping the line being sung at the top anchor.
    //
    // A newer line replaces an unfinished automatic scroll. Only a user's
    // browsing gesture should suspend following, not our own animation.
    LaunchedEffect(focusLine, browsing, listState.isScrollInProgress) {
        if (isSynced && !browsing && !listState.isScrollInProgress &&
            focusLine >= 0 && focusLine in lines.indices
        ) {
            snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            // The panel is about to move the list by itself. Say so, so the
            // controls fade reads it as music rather than as a reader.
            autoScrolling = true
            try {
                val visible = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == focusLine }
                when {
                    !placed -> {
                        listState.scrollToItem(focusLine, scrollOffset = 0)
                        placed = true
                    }
                    // Already on screen, which is the ordinary case of handing
                    // over to the next line: its distance is known, so the move
                    // can be given the run-up's own duration and curve instead
                    // of the list's own spring.
                    visible != null -> {
                        val span = scrollLead(lines, clock.longValue).toInt()
                        since = withFrameMillis { it }
                        run = ScrollRun(
                            (run?.id ?: 0) + 1,
                            visible.offset.toFloat(),
                            span,
                        )
                        // The same curve the rows catch up on. Two different
                        // curves and a row with no delay at all still trails the
                        // list it is sitting in, which is most of the way to
                        // looking like the panel cannot keep up with itself.
                        listState.animateScrollBy(
                            value = visible.offset.toFloat(),
                            animationSpec = tween(durationMillis = span, easing = LYRIC_EASING),
                        )
                    }
                    // Somewhere off screen — after a seek, or a long instrumental
                    // scrolled past. How far is not known without laying the
                    // rows out, so this hands back to the list's own staged
                    // scroll.
                    else -> listState.animateScrollToItem(focusLine, scrollOffset = 0)
                }
            } finally {
                autoScrolling = false
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val swipeDownModifier = Modifier.lyricsSwipeDown(
        enabled = true,
        listState = listState,
        scope = coroutineScope,
        allowScrollToTop = false,
        onScrolledToTop = {
            browsing = true
        },
        onClose = onClose,
    )

    if (lines.isEmpty()) {
        // Two different facts arrive here as the same empty list: a lookup
        // still out, and a lookup that came back with nothing. Only the second
        // is a statement about this track, so only the second gets to say so —
        // the first gets a shimmer, or the panel tells the reader the song has
        // no words for as long as it takes to find out that it does.
        if (unavailable) {
            Box(
                modifier = modifier.then(swipeDownModifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No lyrics for this track",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        } else {
            LyricsSkeleton(modifier = modifier.then(swipeDownModifier))
        }
        return
    }

    val swallowDownOverscroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Absorb downward overscroll so parent ModalBottomSheet does not dismiss the player
                return if (available.y > 0f) Offset(0f, available.y) else Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                // Absorb downward overfling so parent ModalBottomSheet does not dismiss the player
                return if (available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }

    // and the controls cannot be dragged into the middle of a lyric.
    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                // Weighted, not fillMaxSize: a Column hands its children the
                // full incoming height, so a list asking for all of it would
                // push the discs below it off the bottom of the screen.
                // Taking what is left over is what keeps both on screen.
                .weight(1f)
                .fillMaxWidth()
                .bleedHorizontally(PLAYER_GUTTER)
                .fadingEdges()
                .nestedScroll(swallowDownOverscroll)
                .then(swipeDownModifier),
            // Each row carries GLOW_ROOM of its own inset for the halo, so the
            // list hands that much back — otherwise the lines would sit a glow's
            // width further apart and further in than they used to.
            contentPadding = PaddingValues(
                top = 40.dp - GLOW_ROOM,
                bottom = 40.dp - GLOW_ROOM,
                start = PLAYER_GUTTER - GLOW_ROOM,
                end = PLAYER_GUTTER - GLOW_ROOM,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            itemsIndexed(
                items = rows,
                key = { index, row -> ((row.primary.timeMs shl 16) or (index.toLong() and 0xFFFF)) },
                contentType = { _, row ->
                    val line = row.primary
                    if (!isSynced && Genius.isSectionHeader(line.text)) "section"
                    else if (line.isGap) "gap"
                    else "lyric"
                },
            ) { index, row ->
                val line = row.primary
                if (!isSynced && Genius.isSectionHeader(line.text)) {
                    val sectionTitle = line.text.removePrefix("[").removeSuffix("]").trim()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 6.dp else 24.dp, bottom = 8.dp)
                            .padding(horizontal = GLOW_ROOM),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.14f))
                                .padding(horizontal = 11.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = sectionTitle.uppercase(),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    letterSpacing = 1.3.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                ),
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                    return@itemsIndexed
                }

                if (!isSynced && line.isGap) {
                    Spacer(Modifier.height(14.dp))
                    return@itemsIndexed
                }

                // Off the line being sung, not off the line the panel is
                // heading for. Brightness is what says "these are the words
                // right now", so it cannot run ahead of them — on a source with
                // no word timings there is no sweep behind it to keep the sung
                // line lit, and it read as dim while it was still being sung.
                val offset = if (scrollLine < 0) 0 else index - scrollLine
                val distance = abs(offset)
                val isActive = isSynced && index in activeRows
                // A ladder rather than a ramp, and symmetric either side of the
                // playing line: the two rows around it stay legible so you can
                // follow back over what was just sung as well as ahead, and
                // everything past that recedes to the same floor rather than
                // fading to nothing.
                val step = distance.coerceAtMost(LINE_FALLOFF_ALPHA.lastIndex)
                val blur by animateDpAsState(
                    targetValue = when {
                        !isSynced || reduceDynamicBlur || !lyricsBlur || browsing || isActive -> 0.dp
                        else -> LINE_FALLOFF_BLUR[step]
                    },
                    animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                    label = "lyricBlur",
                )
                val lineAlpha by animateFloatAsState(
                    targetValue = when {
                        !isSynced -> 0.95f
                        isActive -> 1f
                        // Reading by hand is not following along: the stack
                        // flattens to one brightness so no row is being
                        // pointed at, which is the difference between reading
                        // on and being shown something.
                        browsing -> BROWSING_ALPHA
                        else -> LINE_FALLOFF_ALPHA[step]
                    },
                    animationSpec = tween(LYRIC_SETTLE_MS, easing = LYRIC_EASING),
                    label = "lyricAlpha",
                )
                if (line.isGap) {
                    // A break counts itself out rather than being marked: three
                    // dots lighting in turn across the interlude, so a long one
                    // reads as time running down instead of a symbol parked on
                    // screen waiting for the singing to come back.
                    val until = lines.getOrNull(index + 1)?.timeMs ?: line.endMs
                    // The row itself opens and closes with the break, so the
                    // list carries no dead space through the verses either side
                    // of it — which is also what stops the panel scrolling past
                    // a hole to reach the next line that is actually sung.
                    val swell by animateFloatAsState(
                        targetValue = if (isActive) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (isActive) 400 else 350,
                            easing = LYRIC_EASING,
                        ),
                        label = "gapSwell",
                    )
                    val instrumental = stringResource(com.music.yzmusic.R.string.instrumental)
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .height((GAP_ROW_HEIGHT + GAP_ROW_SPACING) * swell)
                            .clipToBounds(),
                    ) {
                        Box(
                            modifier = Modifier
                                .blur(blur, BlurredEdgeTreatment.Unbounded)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = isSynced) {
                                    browsing = false
                                    onSeekToLine(line.timeMs)
                                }
                                // Matches the inset every sung line carries, so
                                // the rhythm of the list doesn't break at a
                                // break.
                                .padding(GLOW_ROOM)
                                .size(
                                    width = GAP_DOT_SIZE * 3 + GAP_DOT_GAP * 2,
                                    height = GAP_DOT_SIZE,
                                )
                                .graphicsLayer {
                                    val grow = GAP_REST_SCALE + (1f - GAP_REST_SCALE) * swell
                                    scaleX = grow
                                    scaleY = grow
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    alpha = lineAlpha * swell
                                }
                                .drawBehind {
                                    // Read here rather than in composition: the
                                    // fill moves every frame, and this way a
                                    // break costs a redraw of three circles
                                    // rather than a recomposition.
                                    val span = (until - line.timeMs).coerceAtLeast(1L)
                                    val through = ((clock.longValue - line.timeMs).toFloat() / span)
                                        .coerceIn(0f, 1f)
                                    val radius = GAP_DOT_SIZE.toPx() / 2f
                                    val stride = (GAP_DOT_SIZE + GAP_DOT_GAP).toPx()
                                    repeat(GAP_DOTS) { dot ->
                                        // Each dot owns its share of the break
                                        // and fills across it, so they light
                                        // left to right.
                                        val lit = (through * GAP_DOTS - dot).coerceIn(0f, 1f)
                                        drawCircle(
                                            color = Color.White.copy(
                                                alpha = GAP_DOT_REST +
                                                    (1f - GAP_DOT_REST) * lit,
                                            ),
                                            radius = radius,
                                            center = Offset(
                                                radius + dot * stride,
                                                size.height / 2f,
                                            ),
                                        )
                                    }
                                }
                                .semantics { contentDescription = instrumental },
                        )
                    }
                } else {
                    val alignEnd = duet && line.alignment == LyricAlignment.End
                    val style = if (isSynced) {
                        MaterialTheme.typography.headlineLarge.copy(
                            fontSize = 34.sp,
                            lineHeight = 41.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                        )
                    } else {
                        MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 30.sp,
                            lineHeight = 38.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                        )
                    }
                    // The stack sits fractionally back and the playing line
                    // comes forward to meet you, rather than the playing line
                    // swelling past the others — a smaller move, and one that
                    // doesn't push the type around the line it hands over to.
                    //
                    // Behind the panel's focus, so the words close up to full
                    // brightness as the panel leaves them rather than when the
                    // last syllable lands.
                    val sung = offset < 0
                    // Rows behind the one being scrolled to are the ones that
                    // fan out; the ones it is moving away from arrive together.
                    val current = run
                    val behind =
                        if ((current?.delta ?: 0f) >= 0f) index - focusLine
                        else focusLine - index
                    val staggerDelay = behind.coerceIn(0, STAGGER_STEPS) *
                        STAGGER_FRACTION * (current?.durationMs ?: 0)
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = when {
                            pressed -> PRESSED_SCALE
                            isActive -> 1f
                            else -> INACTIVE_SCALE
                        },
                        animationSpec = tween(
                            durationMillis = if (pressed) 120 else LYRIC_SETTLE_MS,
                            easing = LYRIC_EASING,
                        ),
                        label = "lyricScale",
                    )
                    // Apple's bloom on the line being sung. Fades in and out
                    // with the line rather than switching, so a handover is one
                    // line's light going down as the next one's comes up.
                    val glow by animateFloatAsState(
                        targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                        animationSpec = tween(durationMillis = 420),
                        label = "lyricGlow",
                    )
                    // No width held back for the swell: nothing draws past its
                    // own bounds now that the playing line tops out at 1, so the
                    // text gets the full column and wraps where the panel does.
                    val shape = Modifier
                        .fillMaxWidth()
                        // The lane the other voice sings in, kept clear.
                        // Applied before the layer below so the row scales
                        // about the edge it is actually written from.
                        .padding(
                            start = if (duet && alignEnd) DUET_LANE else 0.dp,
                            end = if (duet && !alignEnd) DUET_LANE else 0.dp,
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                            alpha = lineAlpha
                            // Held back against the list's own movement: the
                            // list has already taken this row part of the way,
                            // so giving back what it has not earned yet is what
                            // leaves it trailing. One curve for both, so a row
                            // with no delay sits exactly still against the list
                            // and the rows that do have one are the only thing
                            // that moves.
                            //
                            // Rows with nothing to catch up on never read the
                            // clock at all, so a handover only invalidates the
                            // handful of layers actually fanning out.
                            translationY = if (staggerDelay <= 0f || current == null) {
                                0f
                            } else {
                                val elapsed = since.toFloat()
                                val span = current.durationMs.toFloat().coerceAtLeast(1f)
                                current.delta * (
                                    LYRIC_EASING.transform(
                                        (elapsed / span).coerceIn(0f, 1f),
                                    ) - LYRIC_EASING.transform(
                                        ((elapsed - staggerDelay) / span).coerceIn(0f, 1f),
                                    )
                                    )
                            }
                        }
                        .blur(blur, BlurredEdgeTreatment.Unbounded)
                        .clip(LyricLineShape)
                        .clickable(
                            enabled = isSynced,
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                        ) {
                            browsing = false
                            onSeekToLine(line.timeMs)
                        }
                    // Lead and answering vocal are one row: they are one line
                    // of the song, they scale and dim together, and tapping
                    // either seeks to the same place.
                    val sub = subReveal.lines?.getOrNull(index)
                    val subStyle = style.copy(
                        fontSize = SUB_LYRIC_FONT_SIZE,
                        lineHeight = SUB_LYRIC_LINE_HEIGHT,
                        fontWeight = FontWeight.Bold,
                    )
                    Column(modifier = shape) {
                        PanelVoice(
                            line = line,
                            clock = clock,
                            style = style,
                            isActive = isActive,
                            sung = sung,
                            synced = isSynced,
                            browsing = browsing,
                            glowAlpha = glow,
                            room = GLOW_ROOM,
                            alignEnd = alignEnd,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // A line the service handed back unchanged ("falling
                        // down" in a Korean song) gets no second copy of itself.
                        sub?.takeIf { it.text != line.text }?.let { subLine ->
                            PanelVoice(
                                line = subLine,
                                clock = clock,
                                style = subStyle,
                                isActive = isActive,
                                sung = sung,
                                synced = isSynced,
                                browsing = browsing,
                                glowAlpha = 0f,
                                room = 0.dp,
                                alignEnd = alignEnd,
                                // Only the rows actually in front of the
                                // reader get the particle pass. Sixty rows'
                                // worth of glyph boxes is a layout walk per
                                // frame for text nobody is looking at. YZ's
                                // sub layer is switched on by hand rather than
                                // arriving with the lyrics, so it is that
                                // reveal the particles ride.
                                translationProgress = subReveal.progress.takeIf {
                                    if (isSynced) abs(index - focusLine) <= 1 else index < 4
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .revealBelow(subReveal.progress)
                                    // Tucked up into the lead's glow inset so
                                    // the pair reads as one line in two
                                    // scripts.
                                    .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                    .offset(y = -SUB_LYRIC_TUCK)
                                    .graphicsLayer { alpha = SUB_LYRIC_ALPHA },
                            )
                        }
                        line.background?.let { backing ->
                            PanelVoice(
                                line = backing.withoutBracketPunctuation(),
                                clock = clock,
                                style = style.copy(
                                    fontSize = BACKING_FONT_SIZE,
                                    lineHeight = BACKING_LINE_HEIGHT,
                                ),
                                isActive = isActive,
                                sung = sung,
                                synced = isSynced,
                                browsing = browsing,
                                // No bloom on the second voice. The glow marks
                                // what is being sung *at you*; putting it on
                                // both makes the row read as two equal lines,
                                // which is the thing this split exists to stop.
                                glowAlpha = 0f,
                                room = 0.dp,
                                alignEnd = alignEnd,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // No top inset: the lead's own bottom room
                                    // is the gap, which leaves the two voices
                                    // closer to each other than to the rows
                                    // either side.
                                    .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                    .graphicsLayer { alpha = BACKING_ALPHA },
                            )
                            sub?.background?.let { subBacking ->
                                PanelVoice(
                                    line = subBacking.withoutBracketPunctuation(),
                                    clock = clock,
                                    style = subStyle.copy(
                                        fontSize = SUB_BACKING_FONT_SIZE,
                                        lineHeight = SUB_BACKING_LINE_HEIGHT,
                                    ),
                                    isActive = isActive,
                                    sung = sung,
                                    synced = isSynced,
                                    browsing = browsing,
                                    glowAlpha = 0f,
                                    room = 0.dp,
                                    alignEnd = alignEnd,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .revealBelow(subReveal.progress)
                                        .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                        .offset(y = -SUB_LYRIC_TUCK)
                                        .graphicsLayer { alpha = BACKING_ALPHA * SUB_LYRIC_ALPHA },
                                )
                            }
                        }
                    }
                }
            }
        }

        // The controls get out of the way while the reader is scrolling and
        // come back when they scroll the other way. A slide as well as a fade,
        // so they leave rather than merely thinning out — a label that is only
        // slightly transparent is still a label over the words.
        val controlsAlpha by animateFloatAsState(
            targetValue = if (controlsShown) 1f else 0f,
            animationSpec = tween(durationMillis = 220),
            label = "lyricsControlsAlpha",
        )
        LyricsLayerDiscs(
            activeLayer = activeLayer,
            availableModes = availableModes,
            translationState = translationState,
            romanizationState = romanizationState,
            canTranslate = canTranslate,
            targetLanguageTag = targetLanguageTag,
            onToggle = onToggleLayer,
            onTranslate = onTranslate,
            modifier = Modifier.graphicsLayer {
                alpha = controlsAlpha
                translationY = (1f - controlsAlpha) * 14.dp.toPx()
            },
        )
    }
}

/**
 * The two discs that put a second voice under the lyrics.
 *
 * Neither replaces the singer's words. Each is a switch over the second line
 * of every row, lit when its layer is up and dim when it is not, so the song
 * stays on screen underneath whatever the reader switches on. They are
 * switches rather than a three-way selector precisely so the same tap takes
 * the layer back off again.
 *
 * They sit outside the list, below it and out of its scroll path, so the drag,
 * the fling and the swipe-down all still belong to the lyrics. A working
 * request shows a spinner in place of its own icon rather than swapping in a
 * separate control, so the button under the reader's finger is the one that
 * answers.
 */
@Composable
private fun LyricsLayerDiscs(
    activeLayer: MainViewModel.LyricsDisplayMode,
    availableModes: Set<MainViewModel.LyricsDisplayMode>,
    translationState: LyricsTranslationState,
    romanizationState: MainViewModel.RomanizationState,
    canTranslate: Boolean = false,
    targetLanguageTag: String,
    onToggle: (MainViewModel.LyricsDisplayMode) -> Unit,
    onTranslate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when {
        // One stage, and no stage name worth showing: the request is out and
        // the words are coming. What used to be a model being downloaded is
        // gone with the model.
        translationState is LyricsTranslationState.Loading ->
            stringResource(
                R.string.lyrics_translation_translating,
                Locale.forLanguageTag(translationState.targetLanguageTag)
                    .getDisplayLanguage(Locale.getDefault()),
            )

        // Unavailable is the engine's own answer and it is final, so it says
        // so plainly rather than offering the disc again, which would only
        // fail the same way.
        translationState is LyricsTranslationState.Unavailable ->
            stringResource(R.string.lyrics_translation_unavailable)

        // Blocked is the network saying no this time, and the reader can fix
        // that by moving to WiFi and tapping again — so the disc stays lit and
        // tappable rather than being taken away along with the way to retry.
        translationState is LyricsTranslationState.Blocked ->
            stringResource(R.string.lyrics_translation_blocked)

        // The engine could tell the reader the words are already in their
        // language, but showing the original is still the right thing: the
        // singer's own text, not a round trip through the translator.
        translationState is LyricsTranslationState.AlreadyInTargetLanguage ->
            stringResource(
                R.string.lyrics_already_in_language,
                Locale.forLanguageTag(translationState.targetLanguageTag)
                    .getDisplayLanguage(Locale.getDefault()),
            )

        romanizationState is MainViewModel.RomanizationState.Unavailable ->
            stringResource(R.string.lyrics_romanization_unavailable)

        // A lyric that needs no romanization. The original *is* the answer
        // here, and it is already what is on screen.
        romanizationState is MainViewModel.RomanizationState.AlreadyRomanized ->
            stringResource(R.string.lyrics_already_romanized)

        else -> null
    }

    // A track the engine would refuse outright gets no translate disc, and a
    // source that shipped no romanization gets no romanize disc. Drawing a
    // dimmed disc for a layer this song cannot have would be saying "coming
    // soon" about a track that simply has nothing to show.
    val offerTranslation = offersTranslationDisc(
        availableModes = availableModes,
        canTranslate = canTranslate,
        translationState = translationState,
    )
    val offerRomanization = MainViewModel.LyricsDisplayMode.ROMANIZED in availableModes
    if (!offerTranslation && !offerRomanization) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PLAYER_GUTTER)
            .padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Romanization left, translation right, and that order is BitChord's
        // in both of its layouts — its landscape bar reads romanize / source /
        // translate, and its portrait player pins romanize to the foot of the
        // words on the start side and translation to the end side. The two
        // make different promises about the same second voice, one in Latin
        // letters and one in the reader's language, and reading left to right
        // that is the order they get offered in.
        //
        // The slot is reserved whether or not the disc is drawn, so a track
        // that can only have one of them does not slide the status line
        // sideways to fill the gap.
        Box(Modifier.size(34.dp)) {
            if (offerRomanization) {
                LyricsLayerDisc(
                    icon = Icons.Rounded.Language,
                    contentDescription = stringResource(
                        if (activeLayer == MainViewModel.LyricsDisplayMode.ROMANIZED) {
                            R.string.lyrics_show_original
                        } else {
                            R.string.lyrics_mode_romanized
                        },
                    ),
                    loading = romanizationState is MainViewModel.RomanizationState.Generating,
                    enabled = romanizationState !is MainViewModel.RomanizationState.AlreadyRomanized,
                    active = activeLayer == MainViewModel.LyricsDisplayMode.ROMANIZED,
                    onClick = { onToggle(MainViewModel.LyricsDisplayMode.ROMANIZED) },
                )
            }
        }
        // Whatever the engine has to report sits between the discs rather than
        // under them, so a message never pushes the lyrics the reader came for
        // any further up the panel.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(34.dp)) {
            if (offerTranslation) {
                LyricsLayerDisc(
                    icon = Icons.Rounded.Translate,
                    contentDescription = stringResource(
                        if (activeLayer == MainViewModel.LyricsDisplayMode.TRANSLATED) {
                            R.string.lyrics_show_original
                        } else {
                            R.string.lyrics_translate
                        },
                    ),
                    loading = translationState is LyricsTranslationState.Loading,
                    // Already in the reader's language is not a failure, but
                    // there is also nothing to switch on, so the disc goes quiet.
                    enabled = translationState !is LyricsTranslationState.AlreadyInTargetLanguage,
                    active = activeLayer == MainViewModel.LyricsDisplayMode.TRANSLATED,
                    // A layer that already exists is a switch; one that has
                    // never been made is the single tap that starts the work.
                    onClick = {
                        if (MainViewModel.LyricsDisplayMode.TRANSLATED in availableModes) {
                            onToggle(MainViewModel.LyricsDisplayMode.TRANSLATED)
                        } else {
                            // A second tap on a blocked disc asks again.
                            // Nothing is remembered about the refusal, so the
                            // retry runs against whatever the network is now.
                            onTranslate(targetLanguageTag)
                        }
                    },
                )
            }
        }
    }
}

/**
 * One switch over a second voice: a white disc that dims when its layer is off
 * and lifts when it is on, with the work showing as a spinner in the icon's
 * own place.
 *
 * No ripple. The disc already says what is on by being brighter, and a ripple
 * across a 34dp circle reads as a second, competing state.
 */
@Composable
private fun LyricsLayerDisc(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    loading: Boolean,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lit = active || loading
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.42f)
        lit -> Color.White
        else -> Color.White.copy(alpha = 0.78f)
    }
    val discAlpha by animateFloatAsState(
        targetValue = if (lit) 0.34f else 0.18f,
        label = "lyricsLayerDisc",
    )
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = tint,
                strokeWidth = 1.7.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}



@Composable
private fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    haptic: Haptic = Haptic.Tap,
) {
    val haptics = rememberHaptics()
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "glyphDisc",
    )
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = discAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * Transport / bottom glyphs. The circular clip belongs on the touch target,
 * never on the [Icon] — clipping the icon itself shaves the corners off wide
 * glyphs like fast-forward and the queue list.
 */
@Composable
private fun TransportGlyph(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: Haptic = Haptic.Tap,
) {
    val haptics = rememberHaptics()
    // Faded rather than hidden: the row keeps its shape at the ends of a queue.
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.3f,
        label = "transportAlpha",
    )
    Box(
        modifier = Modifier
            .size(size + 12.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun BottomGlyph(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    haptic: Haptic = Haptic.Tap,
    label: String? = null,
    customContent: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (highlighted) Color.White.copy(alpha = 0.20f) else Color.Transparent,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(haptic)
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val tint = Color.White.copy(alpha = if (highlighted) 1f else 0.75f)
        if (customContent != null) {
            customContent()
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        } else if (label != null) {
            Text(
                text = label,
                color = tint,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}



/** A credit that links somewhere, when [browseId] is known. */
private fun Modifier.opensPage(browseId: String?, onOpen: (String) -> Unit): Modifier =
    if (browseId == null) {
        this
    } else {
        clip(RoundedCornerShape(6.dp)).clickable { onOpen(browseId) }
    }

/**
 * Measure a child wider than its slot by [gutter] on each side and place it back
 * over that margin, still reporting the original width to the parent.
 *
 * The lists are the only things in the player you can scroll, and the side
 * padding left a strip of bare sheet down each edge. A finger that drifted into
 * one scrolled nothing and closed the player instead. Matching content padding
 * puts every row back exactly where it was drawn, so this is invisible.
 */
private fun Modifier.bleedHorizontally(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra,
        )
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) {
        placeable.place(-(placeable.width - width) / 2, 0)
    }
}

/** Softens the list where it meets the header and the scrubber. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithCache {
        val fade = 28.dp.toPx()
        val topBrush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black),
            startY = 0f,
            endY = fade,
        )
        val bottomBrush = Brush.verticalGradient(
            colors = listOf(Color.Black, Color.Transparent),
            startY = size.height - fade,
            endY = size.height,
        )
        onDrawWithContent {
            drawContent()
            drawRect(
                brush = topBrush,
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                brush = bottomBrush,
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** The live queue, in the player itself. */
@Composable
private fun InlineQueue(
    queue: List<Song>,
    currentIndex: Int,
    autoplayEnabled: Boolean,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
) {
    // Where AutoPlay's tracks start. The queue is kept with them last, so this
    // is one boundary rather than a category to test row by row.
    val autoplayStart = remember(queue, currentIndex) {
        autoplaySectionStart(queue.map { it.fromAutoplay }, currentIndex)
    }

    // Each section reorders on its own — a drag never crosses the line
    // between what was queued by hand and what AutoPlay picked, same as
    // [addToQueue] and [playNext] already respect it.
    //
    // Both draw straight from the live [queue], never from a snapshot taken
    // when the drag began: the boundary between the sections moves on its own
    // as tracks play, so a frozen copy of either one goes stale the moment it
    // does — AutoPlay's section would keep listing tracks that have long
    // since played, and the row indices behind `onJumpTo`/`onRemove` would
    // start pointing at the wrong songs. Each swap is sent to the player as
    // it happens instead, and the rows animate into place off the live order.
    val manualRows = queue.subList(0, autoplayStart)
    val autoplayRows = queue.subList(autoplayStart, queue.size)
    // A song can be queued twice, so videoId alone isn't always a unique key
    // — LazyColumn throws on a repeat. Suffixing by how many times that id
    // has already been seen keeps every key unique while staying stable
    // across a reorder, which plain videoId+index (the previous key) wasn't:
    // that changed on every swap and silently broke animateItem's ability to
    // tell "this row moved" from "this row was replaced".
    val manualKeys = remember(manualRows) { manualRows.stableQueueKeys() }
    val autoplayKeys = remember(autoplayRows) { autoplayRows.stableQueueKeys("autoplay/") }

    // The heading is a row of the same LazyColumn, so it shifts every
    // AutoPlay index below it along by one — hence the offset back to queue
    // indices, which is what [onMove] and the rest of the callbacks take.
    val headingShown = autoplayEnabled || autoplayStart < queue.size
    val headingCount = if (headingShown) 1 else 0
    // Nothing moves at or above the track playing right now: what's already
    // been played is history, and the current row is the boundary the sections
    // are drawn from. Only what's still to come is the user's to reorder.
    // AutoPlay's section needs no such limit — [autoplaySectionStart] always
    // puts it after the current track.
    val firstMovable = (currentIndex + 1).coerceIn(0, autoplayStart)
    val manualDrag = rememberQueueDragState(
        listState = listState,
        lazyRange = firstMovable until autoplayStart,
        lazyOffset = 0,
        onMove = onMove,
    )
    val autoplayDrag = rememberQueueDragState(
        listState = listState,
        lazyRange = (autoplayStart + headingCount) until (autoplayStart + headingCount + autoplayRows.size),
        lazyOffset = headingCount,
        onMove = onMove,
    )

    // Open on what's playing, not at the top of a long queue. The heading sits
    // between the two sections, so it counts as a row once it's above this one.
    //
    // Never mid-drag, though. A track ending while a row is held would jump the
    // list out from under the finger, and the jump takes the list's scroll off
    // the edge auto-scroll below — which would leave the rest of that drag
    // unable to scroll at all. Reordering is also the one time the user is
    // certainly looking somewhere other than at the current track.
    LaunchedEffect(currentIndex) {
        val holdingNow = manualDrag.draggedKey != null || autoplayDrag.draggedKey != null
        if (!holdingNow && currentIndex in queue.indices) {
            val target = currentIndex + if (currentIndex >= autoplayStart) 1 else 0
            if (target != listState.firstVisibleItemIndex) {
                listState.scrollToItem(target)
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val holding = manualDrag.draggedKey != null || autoplayDrag.draggedKey != null

    val swallowDownOverscroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Absorb downward overscroll so parent ModalBottomSheet does not dismiss the player
                return if (available.y > 0f) Offset(0f, available.y) else Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                // Absorb downward overfling so parent ModalBottomSheet does not dismiss the player
                return if (available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }

    Column(
        modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .queueSwipeDown(
                    enabled = true,
                    listState = listState,
                    scope = coroutineScope,
                    isReordering = holding,
                    allowScrollToTop = true,
                    consumeDownDeltas = true,
                    onClose = onClose,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Clear",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .bleedHorizontally(PLAYER_GUTTER)
                .fadingEdges()
                .nestedScroll(swallowDownOverscroll),
            contentPadding = PaddingValues(horizontal = PLAYER_GUTTER),
        ) {
            // What was asked for: the album, playlist or station the queue was
            // started from, plus anything queued by hand since.
            itemsIndexed(
                items = manualRows,
                key = { index, _ -> manualKeys[index] },
            ) { index, song ->
                val key = manualKeys[index]
                val dragging = manualDrag.draggedKey == key
                InlineQueueRow(
                    song = song,
                    isCurrent = index == currentIndex,
                    onClick = { onJumpTo(index) },
                    onRemove = { onRemove(index) },
                    // Only what's still queued ahead. The playing track and
                    // everything already played sit above the line a drag
                    // can't cross.
                    draggable = index >= firstMovable,
                    dragging = dragging,
                    onDragStart = { manualDrag.onDragStart(key) },
                    onDrag = manualDrag::onDrag,
                    onDragEnd = manualDrag::onDragEnd,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) manualDrag.renderOffset else 0f }
                        // The dragged row follows the finger, so it is the one
                        // row that must not also be animating to a slot. Its
                        // neighbours skip the animation too, for as long as
                        // *anything* in the section is being dragged — see the
                        // note on [manualDrag] below for why.
                        .then(if (manualDrag.draggedKey != null) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)),
                )
            }
            // Heading first, then what AutoPlay has lined up under it. With
            // nothing lined up yet it closes the queue as a promise instead.
            if (headingShown) {
                item(key = "autoplay-heading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            YZMusicIcons.Infinity,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "AutoPlay",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                            Text(
                                text = if (autoplayStart < queue.size) {
                                    "Similar music, picked to follow on"
                                } else {
                                    "Similar music will keep playing"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
            itemsIndexed(
                items = autoplayRows,
                key = { index, _ -> autoplayKeys[index] },
            ) { index, song ->
                val at = autoplayStart + index
                val key = autoplayKeys[index]
                val dragging = autoplayDrag.draggedKey == key
                InlineQueueRow(
                    song = song,
                    isCurrent = at == currentIndex,
                    onClick = { onJumpTo(at) },
                    onRemove = { onRemove(at) },
                    draggable = true,
                    dragging = dragging,
                    onDragStart = { autoplayDrag.onDragStart(key) },
                    onDrag = autoplayDrag::onDrag,
                    onDragEnd = autoplayDrag::onDragEnd,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) autoplayDrag.renderOffset else 0f }
                        .then(if (autoplayDrag.draggedKey != null) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)),
                )
            }
        }
    }
}

/**
 * A key per row, stable across a reorder and unique even when the same song
 * appears twice — the Nth time a given videoId is seen gets suffixed with
 * that count, so two copies of one song each keep their own identity instead
 * of colliding on the same LazyColumn key.
 */
private fun List<Song>.stableQueueKeys(prefix: String = ""): List<String> {
    val seen = HashMap<String, Int>()
    return map { song ->
        val n = seen.getOrDefault(song.videoId, 0)
        seen[song.videoId] = n + 1
        if (n == 0) "$prefix${song.videoId}" else "$prefix${song.videoId}#$n"
    }
}

/**
 * How far in from either end of the queue a held row starts scrolling the list,
 * and how fast it scrolls once it is all the way at the edge.
 *
 * The zone is a shade deeper than the 28.dp the list fades out over, so the
 * list is already moving by the time the row begins to disappear into the fade
 * rather than only once it has. The speed at the edge is about six rows a
 * second: quick enough to cross a long queue without waiting on it, slow
 * enough to still read the titles going past and stop on the right one.
 */
private val QUEUE_EDGE_SCROLL_ZONE = 40.dp
private val QUEUE_EDGE_SCROLL_SPEED = 340.dp

/**
 * The pace, in pixels a second, to scroll a list at while a row occupying
 * [top] to [bottom] is held in a viewport spanning [viewportStart] to
 * [viewportEnd] — negative towards the start of the list, positive towards its
 * end, and zero while the row is clear of both edges.
 *
 * Ramped by how far into the [zone] the row has reached, so how fast the queue
 * goes by stays the user's to choose — but from a fifth of [speed] rather than
 * from nothing, since a row just inside the zone should visibly move the list
 * instead of creeping a pixel a second until it is pushed further. A viewport
 * too short to hold the row clear of both edges at once scrolls neither way,
 * rather than picking one arbitrarily and running away with it.
 */
internal fun edgeScrollSpeed(
    top: Float,
    bottom: Float,
    viewportStart: Int,
    viewportEnd: Int,
    zone: Float,
    speed: Float,
): Float {
    if (zone <= 0f) return 0f
    val intoStart = (viewportStart + zone) - top
    val intoEnd = bottom - (viewportEnd - zone)
    val reach = when {
        intoStart > 0f && intoEnd <= 0f -> -intoStart
        intoEnd > 0f && intoStart <= 0f -> intoEnd
        else -> return 0f
    }
    val ramp = speed * (0.2f + 0.8f * (abs(reach) / zone).coerceAtMost(1f))
    return if (reach < 0f) -ramp else ramp
}

/**
 * Drag-to-reorder for one contiguous section of [InlineQueue]'s LazyColumn —
 * the user's own queue and AutoPlay's each get their own instance, since a
 * drag never crosses the boundary between them.
 *
 * Each swap goes to the player the moment the dragged row crosses a
 * neighbour, so the live queue is always what's on screen and the rows the
 * drag displaces animate to their new slots off it. The dragged row is
 * tracked by its LazyColumn key rather than by index, because the index under
 * it changes with every swap.
 *
 * [lazyRange] is the section's span of LazyColumn indices, and [lazyOffset]
 * the distance from those to queue indices — the AutoPlay heading is a row
 * of the list too, so below it the two no longer line up.
 */
@Composable
private fun rememberQueueDragState(
    listState: LazyListState,
    lazyRange: IntRange,
    lazyOffset: Int,
    onMove: (Int, Int) -> Unit,
): QueueDragState {
    val state = remember(listState) { QueueDragState(listState) }
    state.lazyRange = lazyRange
    state.lazyOffset = lazyOffset
    state.onMove = onMove
    with(LocalDensity.current) {
        state.edgeZone = QUEUE_EDGE_SCROLL_ZONE.toPx()
        state.edgeSpeed = QUEUE_EDGE_SCROLL_SPEED.toPx()
    }

    // Held near either end of the list, the row scrolls it. A track can be
    // moved across a queue many screens long without letting go, where before
    // the only way down was to drop the row at the edge, scroll by hand and
    // pick it up again, once per screenful.
    //
    // What the scroll moves is the list, not the finger, and
    // [QueueDragState.onScrolled] says exactly that: the held position stays
    // where it is and the new layout is read back against it. The row sits
    // still on screen while the rows above or below slide past it, and swaps
    // through them on the same terms it would if the finger had covered the
    // distance itself.
    val direction = state.autoScrollDir
    LaunchedEffect(state, direction) {
        if (direction == 0) return@LaunchedEffect
        listState.scroll {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // A frame the system dropped, paid back in full, lands as a
                // lurch — so it isn't.
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
                previous = now
                val scrolled = scrollBy(state.autoScrollSpeed * seconds)
                // Nowhere left to scroll, or the row has left the edge and the
                // speed has gone to nothing. Let the list's scroll go rather
                // than spin on it holding the lock: the row can still be
                // dragged the rest of the way by hand, and coming back to an
                // edge starts this over.
                if (scrolled == 0f) break
                state.onScrolled()
            }
        }
    }
    return state
}

/**
 * Where a held row is being held, what it may do from there, and the moves it
 * has sent to the player on the way.
 *
 * The whole thing turns on one number: [heldCenter], where the row's centre is
 * being held, in the LazyColumn's own viewport pixels. The finger moves it and
 * nothing else does — not a scroll, not a swap, not a relayout. Everything
 * drawn or decided is then read back off the live layout against it: the row
 * is drawn at whatever its slot currently is plus the distance to
 * [heldCenter], and it trades places with whichever neighbour's slot
 * [heldCenter] has reached into.
 *
 * Tracking where the row is rather than how far it has come is what lets the
 * drag survive the list moving underneath it. The offset this replaces was
 * kept by hand — corrected on every scrolled pixel and again on every swap —
 * and held together only for as long as it was told about every last thing
 * that moved the list. It wasn't: LazyColumn re-anchors its own scroll
 * position when the row it measures from is reordered elsewhere (see
 * [swapTarget]), and one such jump left the offset a full row wrong, the row
 * drawn a row off the finger and its slot pushed clean out of the viewport.
 * Read fresh off the layout there is nothing left to be wrong — wherever the
 * list has ended up, the row is still under the finger.
 */
private class QueueDragState(private val listState: LazyListState) {
    var lazyRange: IntRange = IntRange.EMPTY
    var lazyOffset: Int = 0
    var onMove: (Int, Int) -> Unit = { _, _ -> }

    /** [QUEUE_EDGE_SCROLL_ZONE] and [QUEUE_EDGE_SCROLL_SPEED], in pixels. */
    var edgeZone: Float = 0f
    var edgeSpeed: Float = 0f

    /** LazyColumn key of the row being dragged; null at rest. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set

    /**
     * How far from its own slot to draw the held row, in pixels.
     *
     * Not simply the distance to [heldCenter]: a queue longer than the screen
     * has nowhere to show a row above its first slot or below its last, so a
     * finger held past either end was drawing the row off the list into
     * nothing. Kept inside the viewport it sits at whichever edge it reached
     * and stays visible there while the auto-scroll carries the list under it.
     */
    var renderOffset by mutableFloatStateOf(0f)
        private set

    /**
     * Which way the list is scrolling itself under the held row: -1 towards the
     * start of the queue, 1 towards its end, 0 not at all. State, because this
     * is what starts and stops the loop that does the scrolling.
     */
    var autoScrollDir by mutableIntStateOf(0)
        private set

    /**
     * How fast it is doing so, signed, in pixels a second — and deliberately
     * *not* state. It changes with every pixel of drag travel, and only the
     * loop reads it, once a frame; as state it would recompose the whole queue
     * on every touch event to tell the composition something it has no use for.
     */
    var autoScrollSpeed: Float = 0f
        private set

    /**
     * Where the finger is holding the row's centre, in viewport pixels. NaN
     * until the first drag event, which takes it from the row's own slot — a
     * drag begins with the row exactly where it already was.
     */
    private var heldCenter: Float = Float.NaN

    /** Where the last swap put the row, until the list is laid out with it. */
    private var awaiting: Int? = null

    fun onDragStart(key: Any) {
        draggedKey = key
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    /** The finger moved [deltaY] pixels and the list stayed put. */
    fun onDrag(deltaY: Float) = settle(deltaY)

    /** The list moved under the finger and the finger stayed put. */
    fun onScrolled() = settle(0f)

    fun onDragEnd() {
        draggedKey = null
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    /**
     * Takes the drag in [deltaY] pixels further, then reads the list back to
     * see where that leaves the row: where to draw it, whether it has reached
     * an edge, and whether it has reached a neighbour worth trading with.
     */
    private fun settle(deltaY: Float) {
        val key = draggedKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        // The row's own slot is off screen. There is nothing to measure an
        // edge or a swap against and nothing to draw against either, so the
        // way back is to stand still and let the swap already sent land and
        // bring the slot into view. If the row has been disposed outright
        // rather than merely scrolled past, it ends the drag itself on the
        // way out — see the disposal guard in [InlineQueueRow].
        val dragged = items.find { it.key == key } ?: run {
            setAutoScroll(0f)
            return
        }
        val half = dragged.size / 2f
        if (heldCenter.isNaN()) heldCenter = dragged.offset + half
        heldCenter += deltaY
        holdToSection(items, dragged)

        val top = heldCenter - half
        // Aimed before the guard below, not after: a swap in flight is a frame
        // or two of the list not having caught up yet, and the scroll should
        // carry on evenly through those rather than stutter once per row.
        aimAutoScroll(top, dragged)
        renderOffset = insideViewport(top, dragged.size) - dragged.offset

        // A swap already sent but not yet laid out: deciding the next one off
        // a position the list has moved on from would send a second move for
        // a swap that has already happened, and the two would fight.
        awaiting?.let {
            if (dragged.index != it) return
            awaiting = null
        }
        val target = swapTarget(items, dragged) ?: return
        onMove(dragged.index - lazyOffset, target.index - lazyOffset)
        awaiting = target.index
    }

    /**
     * The neighbour [heldCenter] has reached far enough into to trade places
     * with, or null while there is none to trade with yet.
     */
    private fun swapTarget(
        items: List<LazyListItemInfo>,
        dragged: LazyListItemInfo,
    ): LazyListItemInfo? {
        // Only rows of this section are fair targets — the heading and the
        // other section's rows share the LazyColumn but not this range.
        val target = items
            .filter { it.index in lazyRange && it.index != dragged.index }
            .minByOrNull { abs((it.offset + it.size / 2f) - heldCenter) }
            ?: return null
        // Held short of halfway the rows would swap back and forth over a
        // single pixel of travel; a full half-height of overlap is what makes
        // one swap per row crossed.
        if (abs(heldCenter - (target.offset + target.size / 2f)) > target.size / 2f) return null
        // Never with the row the list is keeping its own place by, while there
        // is still list above it to scroll.
        //
        // LazyColumn remembers where it is scrolled to as the *key* of its
        // first visible row plus an offset into it. Reorder that particular
        // row and it follows the key to wherever the row went, which slides
        // the entire list along by a row — and the held row, which has just
        // moved into the slot that row left, goes off the top of the viewport
        // with it. LazyColumn then disposes it, and disposal cancels the drag
        // gesture outright: neither onDragEnd nor onDragCancel runs, so the
        // row was left highlighted and offset with nothing dragging it,
        // stranded a row above where it was picked up. Dragging *down* never
        // met this, because the row traded with is the one below and the list
        // anchors on the one at the top; dragging up, the row traded with is
        // precisely the one the edge scroll is drawing in at the top, which is
        // why one direction worked and the other did not.
        //
        // Declining to swap this frame is the whole fix. The scroll that
        // brought the row here carries on, the next row up becomes the one the
        // list is anchored by, and the trade goes through a few frames later —
        // by which time it moves nothing the list is holding on to. With no
        // list left above to scroll there is no jump to decline in the first
        // place, so a row can still be dropped into the first slot of its
        // section.
        if (target.index == listState.firstVisibleItemIndex && listState.canScrollBackward) {
            return null
        }
        return target
    }

    /**
     * Points the auto-scroll at whichever edge the row now spanning [top] has
     * reached, if either — but only while there is both a row that way for it
     * to swap with and list left to scroll. Held past the last row of its own
     * section it would otherwise keep the list moving with no move left to
     * make, carrying the row's slot away under a finger that has nothing left
     * to answer with.
     */
    private fun aimAutoScroll(top: Float, dragged: LazyListItemInfo) {
        val info = listState.layoutInfo
        val speed = edgeScrollSpeed(
            top = top,
            bottom = top + dragged.size,
            viewportStart = info.viewportStartOffset,
            viewportEnd = info.viewportEndOffset,
            zone = edgeZone,
            speed = edgeSpeed,
        )
        val blocked = when {
            speed < 0f -> dragged.index <= lazyRange.first || !listState.canScrollBackward
            speed > 0f -> dragged.index >= lazyRange.last || !listState.canScrollForward
            else -> true
        }
        setAutoScroll(if (blocked) 0f else speed)
    }

    /**
     * Holds the drag inside the section it started in.
     *
     * A row can only be dropped between the first and last slots of its own
     * section — the playing track and the history above it are not the user's
     * to reorder, and neither is the far side of the AutoPlay heading. The
     * swap loop already respects that, by having no target to offer past
     * either end; what it does not do is stop [heldCenter] running on past the
     * boundary, and a finger a screen beyond it then has that whole distance
     * to travel back before the row answers again. Held at the boundary it
     * stops there under the finger, which is what "this is as far as it goes"
     * ought to look like.
     *
     * Only the ends actually on screen bound anything. A section that runs off
     * the viewport has more of itself that way for the auto-scroll to bring
     * in, and holding to whichever of its rows happens to be measured would
     * stop the drag at the edge of the screen instead of at the edge of the
     * section.
     */
    private fun holdToSection(items: List<LazyListItemInfo>, dragged: LazyListItemInfo) {
        val half = dragged.size / 2f
        items.firstOrNull { it.index == lazyRange.first }?.let {
            heldCenter = heldCenter.coerceAtLeast(it.offset + half)
        }
        items.firstOrNull { it.index == lazyRange.last }?.let {
            heldCenter = heldCenter.coerceAtMost(it.offset + it.size - half)
        }
    }

    /** [top], kept where a row of [size] can still be seen — see [renderOffset]. */
    private fun insideViewport(top: Float, size: Int): Float {
        val info = listState.layoutInfo
        val minTop = info.viewportStartOffset.toFloat()
        val maxTop = (info.viewportEndOffset - size).toFloat().coerceAtLeast(minTop)
        return top.coerceIn(minTop, maxTop)
    }

    private fun setAutoScroll(speed: Float) {
        autoScrollSpeed = speed
        val direction = when {
            speed > 0f -> 1
            speed < 0f -> -1
            else -> 0
        }
        if (autoScrollDir != direction) autoScrollDir = direction
    }
}

private val QueueRowShape = RoundedCornerShape(8.dp)
private val QueueThumbShape = RoundedCornerShape(6.dp)
private val QueueThumbBg = Color.White.copy(alpha = 0.08f)
private val QueueDragHighlight = Color.White.copy(alpha = 0.06f)

@Composable
private fun InlineQueueRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    draggable: Boolean = false,
    dragging: Boolean = false,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    // LazyColumn disposes a row the instant its slot leaves the viewport, and
    // that takes the drag gesture below down with it: the coroutine running
    // [detectDragGestures] is cancelled where it stands, so neither onDragEnd
    // nor onDragCancel is ever reached and the drag is left held by nothing —
    // the row comes back into view highlighted and offset from its slot, and
    // stays that way until the queue is closed. The swap guard in
    // [QueueDragState.swapTarget] is what stops the slot being thrown out of
    // the viewport in the first place; this is here because "the gesture ended
    // and nothing was told" should not be a state the queue can be left in at
    // all, whatever put it there.
    val heldOnDispose by rememberUpdatedState(dragging)
    val endDrag by rememberUpdatedState(onDragEnd)
    DisposableEffect(Unit) {
        onDispose {
            if (heldOnDispose) {
                endDrag()
            }
        }
    }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(QueueRowShape)
            .background(if (dragging) QueueDragHighlight else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (draggable) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(20.dp)
                    // DragHandle's glyph sits well inset from the edges of
                    // its own bounding box — this pulls it back to the row's
                    // actual left edge instead of leaving a gap in front of it.
                    .offset(x = (-4).dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentOnDrag(dragAmount.y)
                            },
                        )
                    },
            )
            Spacer(Modifier.width(4.dp))
        }
        val context = LocalContext.current
        val artModel = remember(song.thumbnailUrl) {
            ImageRequest.Builder(context)
                .data(song.artworkAt(ROW_ART_PX) ?: song.thumbnailUrl)
                .crossfade(false)
                .build()
        }
        AsyncImage(
            model = artModel,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(QueueThumbShape)
                .thumbnailBorder(QueueThumbShape)
                .background(QueueThumbBg),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isExplicit == true) {
                    ExplicitBadge(
                        color = Color.White.copy(alpha = 0.8f),
                        backgroundColor = Color.White.copy(alpha = 0.18f),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent) {
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = "Now playing",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Remove from queue",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/**
 * The gap between the two timestamps under the seek bar: just the "Lossless"
 * badge when one applies, and nothing otherwise. The measured stats line
 * that used to fall back to lives inside the sleeve now (see the bottom-centre
 * overlay on the artwork Box above), so there is no tap here to swap it in —
 * the badge is a claim, the sleeve is where the evidence is.
 */
@Composable
private fun LosslessOrStats(
    isLoading: Boolean,
    stillRacing: Boolean,
    losslessRequested: Boolean,
    nerdStats: NerdStats.Snapshot?,
    modifier: Modifier = Modifier,
) {
    when {
        // Still resolving — either the player itself is buffering, or a
        // module is still racing YouTube for this track in the background
        // (see [NerdStats.racingLossless]) even though YouTube already won
        // and is audible. Either way nothing measured yet to confirm with,
        // so this is a statement of intent, not a result — no shimmer, so
        // it never reads as "confirmed" before it is.
        // [stillRacing] on its own, not gated on the lossless preference: a
        // module outranks YouTube on the strength of the source order alone,
        // so the lookup runs — and can come back lossless — with that switch
        // off. Gating this on it left the badge blank through the wait and
        // then jumped straight to "Hi-Res Lossless".
        // The [isLoading] half is gated on `nerdStats == null` rather than
        // `nerdStats?.isLossless != true`: `isLoading` is just
        // `STATE_BUFFERING`, which a seek trips for a track whose quality
        // question was already settled — swallowing back into cache still
        // rebuffers. Gating on `!= true` read that rebuffer as "resolving"
        // again and flashed "Upgrading Quality" over a track already known
        // to be, say, Hi-Quality with no lossless copy anywhere. Once
        // [nerdStats] exists there is something measured to show instead, so
        // only a genuinely unmeasured track — or a real race via
        // [stillRacing] — earns this label.
        (stillRacing && nerdStats?.isLossless != true) ||
            (isLoading && losslessRequested && nerdStats == null) -> LosslessLabel(
            // What is already true, ahead of what is still being looked for.
            // A race running over JioSaavn's 320kbps AAC and one running over
            // YouTube's 160kbps Opus were both drawn as a bare "Upgrading
            // Quality", which reads as "this is not good yet" — wrong on the
            // first, where the track is already at the top of what lossy gets
            // and the search is only chasing a lossless copy that may not
            // exist. Naming the floor first makes the label describe a track
            // rather than a wait.
            //
            // Decided on [NerdStats.Snapshot.isHiQuality] rather than on which
            // source won, for the reason that property already gives: a
            // 320kbps stream is a 320kbps stream wherever it came from. It
            // reads the claimed rate when nothing is measured yet, so a
            // JioSaavn stream qualifies from its first frame; YouTube's Opus
            // sits under the threshold and keeps the plain label it had.
            text = if (nerdStats?.isHiQuality == true) {
                "Hi-Quality, Upgrading Quality"
            } else {
                "Upgrading Quality"
            },
            animated = false,
            modifier = modifier,
        )
        nerdStats?.isLossless == true -> LosslessLabel(
            // Same line Tidal, Qobuz and Apple Music draw it at — see
            // [NerdStats.Snapshot.isHiRes].
            text = if (nerdStats.isHiRes) "Hi-Res Lossless" else "Lossless",
            // Shimmer is reserved for the thing that was asked for and
            // confirmed. It is what makes the badge read as an achievement
            // rather than a label, which only one of these two is.
            animated = true,
            modifier = modifier,
        )
        // Lossy, but the good end of lossy — a module's 320kbps tier, which
        // for a great many tracks is the best copy that exists anywhere the
        // app can reach. See [NerdStats.Snapshot.isHiQuality].
        nerdStats?.isHiQuality == true -> LosslessLabel(
            text = "Hi-Quality",
            animated = false,
            modifier = modifier,
        )
        else -> {}
    }
}

/** A headphone glyph ahead of the quality tag — "Upgrading Quality", "Hi-Quality", "Lossless". */
@Composable
private fun LosslessLabel(text: String, animated: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (animated) 0.7f else 0.45f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        if (animated) {
            ShimmerText(text = text)
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
                ),
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "Lossless", with a highlight band sweeping left to right across it every
 * three seconds — confirmed, not just claimed, so it's worth the shine.
 *
 * The band's width is measured off the text itself via [onSizeChanged]
 * rather than assumed, so the sweep always clears the word fully at both
 * ends instead of being sized for whatever length happened to be typical.
 */
@Composable
private fun ShimmerText(text: String) {
    var widthPx by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "lossless-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lossless-shimmer-progress",
    )
    val baseColor = Color.White.copy(alpha = 0.55f)
    val brush = if (widthPx <= 0) {
        Brush.linearGradient(listOf(baseColor, baseColor))
    } else {
        val band = widthPx * 0.6f
        val center = -band + progress * (widthPx + 2 * band)
        Brush.linearGradient(
            colorStops = arrayOf(0f to baseColor, 0.5f to Color.White, 1f to baseColor),
            start = Offset(center - band, 0f),
            end = Offset(center + band, 0f),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            brush = brush,
            fontWeight = FontWeight.SemiBold,
            fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + 1).sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.onSizeChanged { widthPx = it.width },
    )
}

/**
 * "FLAC · 24-bit · 96.0 kHz · Stereo" — whichever of those the player has
 * actually reported. A figure it hasn't is dropped rather than filled in, so a
 * short line means little was known, never that something was invented.
 *
 * Bitrate is omitted once the stream is known to be lossless: the number is
 * real but says nothing useful about the quality, and reading "1411 kbps" next
 * to "FLAC" invites the comparison with a lossy figure that the two do not
 * support.
 *
 * A stream that arrived worse than its source promised gets that stated
 * outright rather than left to be spotted — see [NerdStats.Snapshot.downgraded].
 */
private fun NerdStats.Snapshot.describe(): String? {
    val parts = buildList {
        codecLabel(mimeType)?.let(::add)
        bitDepth?.let { add("$it-bit") }
        if (!isLossless) bitrateKbps?.let { add("$it kbps") }
        sampleRateHz?.let { add("%.1f kHz".format(Locale.ROOT, it / 1000f)) }
        channels?.let {
            add(
                when (it) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    else -> "$it ch"
                },
            )
        }
        if (downgraded) add("↓ from ${claimed?.summary}")
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** The codec under its usual name rather than its MIME type. */
private fun codecLabel(mimeType: String?): String? = when {
    mimeType == null -> null
    mimeType.endsWith("opus") -> "Opus"
    mimeType.endsWith("mp4a-latm") -> "AAC"
    mimeType.endsWith("vorbis") -> "Vorbis"
    mimeType.endsWith("mpeg") -> "MP3"
    mimeType.endsWith("flac") -> "FLAC"
    mimeType.endsWith("alac") -> "ALAC"
    else -> mimeType.substringAfter('/').uppercase(Locale.ROOT)
}

/** Wording for the stats line; see [TrackAnalysisState]. */
private fun TrackAnalysisState.label(): String = when (this) {
    TrackAnalysisState.ANALYSED -> "analysed"
    TrackAnalysisState.REFINING -> "analysed, refining…"
    TrackAnalysisState.ANALYSING -> "analysing…"
    TrackAnalysisState.WAITING -> "waiting"
    TrackAnalysisState.FAILED -> "failed"
}

/**
 * A back callback that outranks whatever else the window has registered —
 * here, the sheet the player is drawn in. See the call site in
 * [NowPlayingScreen] for why it takes that.
 *
 * Everything that names an `android.window` type lives in this object so those
 * classes, which don't exist below API 33, are only ever *loaded* on a device
 * that has them: the callback comes back as [Any] rather than as the platform
 * interface for the same reason. Gating the calls on [Build.VERSION.SDK_INT]
 * is very likely enough by itself; this way it can't come down to how eagerly
 * a particular runtime resolves a reference it is never going to use.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object OverlayBack {
    /** The registered callback, to hand back to [unregister]; null if it couldn't be. */
    fun register(view: View, onBack: () -> Unit): Any? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback { onBack() }
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        return callback
    }

    fun unregister(view: View, callback: Any?) {
        if (callback !is OnBackInvokedCallback) return
        view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }
}

/**
 * Detects coordinated gestures on the Full Player:
 * 1. Double-tap seek:
 *    - Left half (< width / 2) -> seek backward
 *    - Right half (>= width / 2) -> seek forward
 * 2. Horizontal swipe:
 *    - Left swipe (dx < 0) -> next track
 *    - Right swipe (dx > 0) -> previous track
 * 3. Vertical swipe up:
 *    - Upward swipe (-dy > 0) -> open Queue
 *
 * Requirements enforced:
 * - Directionally locked: horizontal dominant -> song change, vertical dominant up -> Queue.
 * - Tap vs Swipe: swipes exceed threshold, double tap requires two taps within doubleTapTimeoutMs with movement <= touchSlop.
 * - Single tap does not seek or change track.
 * - Child interactive controls (ThinSlider, buttons) consume their pointer events on PointerEventPass.Main;
 *   if an event is consumed before a gesture is classified, this detector immediately aborts.
 * - Non-interactive areas across the entire Full Player bounds respond cleanly.
 */
private fun Modifier.fullPlayerGestures(
    enabled: Boolean,
    onSwipeUp: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDoubleTapSeek: (isForward: Boolean) -> Unit,
    onHorizontalDragOffset: ((Float) -> Unit)? = null,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled) {
        val thresholdPx = 48.dp.toPx()
        val flickVelocityPx = 400.dp.toPx()
        val minFlickDistancePx = 20.dp.toPx()
        val touchSlopPx = viewConfiguration.touchSlop

        val tracker = FullPlayerGestureTracker(
            thresholdPx = thresholdPx,
            flickVelocityPx = flickVelocityPx,
            minFlickDistancePx = minFlickDistancePx,
            touchSlopPx = touchSlopPx,
            doubleTapTimeoutMs = 350L,
        )

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val pointerId = down.id
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            tracker.onGestureEnd()

            var wasConsumedByChild = false
            var completedAction: FullPlayerGestureAction = FullPlayerGestureAction.NONE

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                // If a child control (e.g. ThinSlider or IconButton) consumed position changes, abort
                if (change.isConsumed && !tracker.gestureHandled) {
                    wasConsumedByChild = true
                    tracker.invalidateTap()
                    onHorizontalDragOffset?.invoke(0f)
                    break
                }

                if (!change.pressed) {
                    // Finger lifted (Up event)
                    val velocity = velocityTracker.calculateVelocity()
                    val flickAction = tracker.onRelease(velocity.x, velocity.y)
                    if (flickAction != FullPlayerGestureAction.NONE) {
                        completedAction = flickAction
                        change.consume()
                    } else if (!tracker.gestureHandled) {
                        // Check tap / double-tap
                        val tapAction = tracker.onTap(
                            x = change.position.x,
                            y = change.position.y,
                            containerWidth = size.width.toFloat(),
                            currentTimeMs = System.currentTimeMillis(),
                        )
                        if (tapAction != FullPlayerGestureAction.NONE) {
                            completedAction = tapAction
                            change.consume()
                        }
                    }
                    onHorizontalDragOffset?.invoke(0f)
                    break
                }

                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val totalDx = change.position.x - startPos.x
                val totalDy = change.position.y - startPos.y

                // Subtle visual horizontal drag feedback if moving horizontally
                if (!tracker.isLockedVertical && kotlin.math.abs(totalDx) > touchSlopPx) {
                    onHorizontalDragOffset?.invoke(totalDx)
                }

                val action = tracker.onPosition(totalDx, totalDy)
                if (action != FullPlayerGestureAction.NONE) {
                    completedAction = action
                    change.consume()
                    onHorizontalDragOffset?.invoke(0f)
                    // Consume remaining drag events of this gesture until finger lifts
                    while (true) {
                        val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                        val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                        nextChange.consume()
                        if (!nextChange.pressed) break
                    }
                    break
                }
            }

            if (!wasConsumedByChild) {
                when (completedAction) {
                    FullPlayerGestureAction.SWIPE_UP_QUEUE -> onSwipeUp()
                    FullPlayerGestureAction.SWIPE_LEFT_NEXT -> onNext()
                    FullPlayerGestureAction.SWIPE_RIGHT_PREVIOUS -> onPrevious()
                    FullPlayerGestureAction.DOUBLE_TAP_SEEK_BACKWARD -> onDoubleTapSeek(false)
                    FullPlayerGestureAction.DOUBLE_TAP_SEEK_FORWARD -> onDoubleTapSeek(true)
                    FullPlayerGestureAction.NONE -> Unit
                }
            }
        }
    }
}

/**
 * Detects deliberate downward swipe on the Queue.
 *
 * Rules:
 * - CASE 1: Queue is NOT at top (firstVisibleItemIndex > 0 || offset > 0)
 *   -> scrolls list directly to top (listState.scrollToItem(0)), remains in Queue.
 *   -> Never closes Queue in this gesture.
 * - CASE 2: Queue IS at top (firstVisibleItemIndex == 0 && offset == 0)
 *   -> closes Queue and returns to Full Player (onClose()).
 * - Small accidental movements do nothing.
 * - Horizontal movement does not trigger actions.
 * - Normal Queue scrolling continues to work unimpeded.
 * - No intermediate Peek state, no sheet animation for case 1.
 */
private fun Modifier.queueSwipeDown(
    enabled: Boolean,
    listState: LazyListState,
    scope: CoroutineScope,
    isReordering: Boolean = false,
    allowScrollToTop: Boolean = true,
    consumeDownDeltas: Boolean = false,
    onClose: () -> Unit,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled, isReordering, allowScrollToTop, consumeDownDeltas) {
        val thresholdPx = 48.dp.toPx()
        val scrollToTopThresholdPx = 180.dp.toPx()
        val flickVelocityPx = 450.dp.toPx()
        val minFlickDistancePx = 20.dp.toPx()
        val minScrollToTopFlickDistancePx = 120.dp.toPx()
        val touchSlopPx = viewConfiguration.touchSlop

        val tracker = QueueSwipeDownTracker(
            thresholdPx = thresholdPx,
            scrollToTopThresholdPx = scrollToTopThresholdPx,
            flickVelocityPx = flickVelocityPx,
            minFlickDistancePx = minFlickDistancePx,
            minScrollToTopFlickDistancePx = minScrollToTopFlickDistancePx,
            touchSlopPx = touchSlopPx,
        )

        awaitEachGesture {
            if (isReordering) return@awaitEachGesture
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val pointerId = down.id
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            val isAtTop = !listState.canScrollBackward ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 4)

            // When scrolling mid-list where scroll-to-top is disabled and deltas are not consumed,
            // queueSwipeDown has no action to perform. Exit immediately so PointerEventPass.Initial
            // is not intercepted, giving LazyColumn 100% native smooth scrolling without overhead.
            if (!isAtTop && !allowScrollToTop && !consumeDownDeltas) {
                return@awaitEachGesture
            }

            tracker.onGestureStart(isAtTop)

            while (true) {
                // Initial pass allows inspecting deltas before child consumes,
                // but we only consume when deliberate swipe threshold is met.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (isReordering) {
                    break
                }

                val totalDx = change.position.x - startPos.x
                val totalDy = change.position.y - startPos.y

                // If gesture is moving upward past touch slop, the user is scrolling down into the queue.
                // A downward swipe-to-close can never happen from this gesture, so stop tracking.
                if (totalDy < -touchSlopPx && !consumeDownDeltas) {
                    break
                }

                velocityTracker.addPosition(change.uptimeMillis, change.position)

                if (!change.pressed) {
                    val velocity = velocityTracker.calculateVelocity()
                    val rawAction = tracker.onRelease(velocity.y)
                    val action = if (!allowScrollToTop && rawAction == QueueSwipeDownAction.SCROLL_TO_TOP) {
                        QueueSwipeDownAction.NONE
                    } else rawAction
                    when (action) {
                        QueueSwipeDownAction.CLOSE_QUEUE -> {
                            change.consume()
                            onClose()
                        }
                        QueueSwipeDownAction.SCROLL_TO_TOP -> {
                            change.consume()
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                        QueueSwipeDownAction.NONE -> {
                            if (consumeDownDeltas && totalDy > touchSlopPx && totalDy > kotlin.math.abs(totalDx) * 1.25f) {
                                change.consume()
                            }
                        }
                    }
                    break
                }

                val rawAction = tracker.onPosition(totalDx, totalDy)
                val action = if (!allowScrollToTop && rawAction == QueueSwipeDownAction.SCROLL_TO_TOP) {
                    QueueSwipeDownAction.NONE
                } else rawAction
                when (action) {
                    QueueSwipeDownAction.CLOSE_QUEUE -> {
                        change.consume()
                        onClose()
                        while (true) {
                            val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                            nextChange.consume()
                            if (!nextChange.pressed) break
                        }
                        break
                    }
                    QueueSwipeDownAction.SCROLL_TO_TOP -> {
                        val currentVelocity = velocityTracker.calculateVelocity()
                        // If on a scrollable list and user is slowly dragging down (browsing), do NOT hijack mid-drag!
                        // Let LazyColumn scroll naturally unless it is a swift deliberate swipe!
                        if (!consumeDownDeltas && currentVelocity.y < 400.dp.toPx() && change.pressed) {
                            // User is slowly dragging down to browse the list — let LazyColumn scroll naturally!
                        } else {
                            change.consume()
                            scope.launch { listState.animateScrollToItem(0) }
                            while (true) {
                                val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                                val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                                nextChange.consume()
                                if (!nextChange.pressed) break
                            }
                            break
                        }
                    }
                    QueueSwipeDownAction.NONE -> {
                        // When on non-scrollable controls/headers, consume downward drag deltas
                        // past touch slop so parent ModalBottomSheet never moves the Full Player!
                        if (consumeDownDeltas && totalDy > touchSlopPx && totalDy > kotlin.math.abs(totalDx) * 1.25f) {
                            change.consume()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Detects deliberate downward swipe on the Lyrics panel or top handle.
 *
 * Rules enforced:
 * - CASE A - LYRICS NOT AT TOP:
 *   Deliberate swipe DOWN on handle -> scroll directly to top (listState.scrollToItem(0)).
 *   Remain in Lyrics. No navigation, no peek state, no sheet animation.
 * - CASE B - LYRICS AT TOP:
 *   Deliberate swipe DOWN -> close Lyrics (return directly to Full Player via onClose).
 * - Small accidental movements do nothing.
 * - Directionally locked: totalDy > |totalDx| * 1.25. Horizontal movement locks out actions.
 * - Normal vertical scrolling continues to work unimpeded.
 * - No automatic chaining (single swipe never scrolls to top and closes).
 */
private fun Modifier.lyricsSwipeDown(
    enabled: Boolean,
    listState: LazyListState,
    scope: CoroutineScope,
    allowScrollToTop: Boolean = true,
    onScrolledToTop: () -> Unit = {},
    onClose: () -> Unit,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled, allowScrollToTop) {
        val thresholdPx = 48.dp.toPx()
        val scrollToTopThresholdPx = 180.dp.toPx()
        val flickVelocityPx = 450.dp.toPx()
        val minFlickDistancePx = 20.dp.toPx()
        val minScrollToTopFlickDistancePx = 120.dp.toPx()
        val touchSlopPx = viewConfiguration.touchSlop

        val tracker = LyricsSwipeDownTracker(
            thresholdPx = thresholdPx,
            scrollToTopThresholdPx = scrollToTopThresholdPx,
            flickVelocityPx = flickVelocityPx,
            minFlickDistancePx = minFlickDistancePx,
            minScrollToTopFlickDistancePx = minScrollToTopFlickDistancePx,
            touchSlopPx = touchSlopPx,
        )

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val pointerId = down.id
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            val isAtTop = !listState.canScrollBackward ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 4)
            tracker.onGestureStart(isAtTop)

            while (true) {
                // Initial pass allows inspecting deltas before child consumes,
                // but we only consume when deliberate swipe threshold is met.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (!change.pressed) {
                    val velocity = velocityTracker.calculateVelocity()
                    val rawAction = tracker.onRelease(velocity.y)
                    val action = if (!allowScrollToTop && rawAction == LyricsSwipeDownAction.SCROLL_TO_TOP) {
                        LyricsSwipeDownAction.NONE
                    } else rawAction
                    when (action) {
                        LyricsSwipeDownAction.CLOSE_LYRICS -> {
                            change.consume()
                            onClose()
                        }
                        LyricsSwipeDownAction.SCROLL_TO_TOP -> {
                            change.consume()
                            onScrolledToTop()
                            scope.launch { listState.scrollToItem(0) }
                        }
                        LyricsSwipeDownAction.NONE -> Unit
                    }
                    break
                }

                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val totalDx = change.position.x - startPos.x
                val totalDy = change.position.y - startPos.y

                val rawAction = tracker.onPosition(totalDx, totalDy)
                val action = if (!allowScrollToTop && rawAction == LyricsSwipeDownAction.SCROLL_TO_TOP) {
                    LyricsSwipeDownAction.NONE
                } else rawAction
                when (action) {
                    LyricsSwipeDownAction.CLOSE_LYRICS -> {
                        change.consume()
                        onClose()
                        while (true) {
                            val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                            nextChange.consume()
                            if (!nextChange.pressed) break
                        }
                        break
                    }
                    LyricsSwipeDownAction.SCROLL_TO_TOP -> {
                        change.consume()
                        onScrolledToTop()
                        scope.launch { listState.scrollToItem(0) }
                        while (true) {
                            val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                            nextChange.consume()
                            if (!nextChange.pressed) break
                        }
                        break
                    }
                    LyricsSwipeDownAction.NONE -> {
                        // Allow LazyColumn to scroll normally in Main pass!
                    }
                }
            }
        }
    }
}


