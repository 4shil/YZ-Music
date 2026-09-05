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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.haptics.Haptic
import com.music.yzmusic.ui.haptics.rememberHaptics
import com.music.yzmusic.ui.icons.YZMusicIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.data.NerdStats
import com.music.yzmusic.data.settings.TrackAnalysisState
import com.music.yzmusic.data.canvas.CanvasArtwork
import com.music.yzmusic.data.canvas.CanvasRepository
import com.music.yzmusic.data.canvas.CanvasSource
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricsSource
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
private const val LYRIC_FADE_FRACTION = 0.28f
private const val LYRIC_FADE_MIN_MS = 160f
private const val LYRIC_FADE_MAX_MS = 700f

/**
 * How far back the part of the playing line that hasn't been sung yet is held.
 *
 * The strip above the scrubber gets less of a gap than the full panel: it is
 * one line of small type with nothing around it to compare against, and taking
 * it as far down as the panel does left the words ahead of the highlight hard
 * to read at a glance.
 */
private const val UNSUNG_ALPHA = 0.45f
private const val UNSUNG_ALPHA_STRIP = 0.55f

/**
 * The bloom behind the line being sung, at its very strongest.
 *
 * Kept well under half strength: the halo is drawn from the same white as the
 * text, so at full alpha it stops reading as light and starts reading as a
 * second, badly printed copy of the words. What is actually drawn is this
 * scaled by how long the word is being held, so only a properly carried note
 * ever sees the whole of it.
 */
private const val GLOW_ALPHA = 0.62f
private val GLOW_RADIUS = 9.dp

/**
 * How far behind the sweep's leading edge the bloom reaches, at full strength.
 *
 * The glow belongs to the word being sung, not to everything sung so far —
 * lighting the whole revealed stretch made the line brighten as it went and
 * turned the last line of a verse into a slab of white. Scaled down towards
 * [GLOW_TRAIL_FLOOR] as the singing quickens; see
 * [LyricLine.glowIntensity][com.music.yzmusic.data.lyrics.LyricLine.glowIntensity].
 */
private val GLOW_TRAIL = 62.dp
private const val GLOW_TRAIL_FLOOR = 0.55f

/**
 * Room reserved inside each copy of a line for the halo to spread into.
 *
 * A blur is computed on its layer's own bitmap, so a halo with nowhere to go
 * inside those bounds is a halo with a hard edge — which is what cropped the
 * bloom to the line's box. Every copy carries the same inset so they still lay
 * out identically, and the list gives the width back by taking it off its own
 * padding and row spacing.
 */
private val GLOW_ROOM = 10.dp

/**
 * How the answering vocal is drawn: smaller than the lead and a shade behind
 * it, the way Apple Music hangs a backing line under the one it answers.
 *
 * Small enough to be read as a second voice at a glance and no smaller —
 * these are the words of the song, not a caption.
 */
private val BACKING_FONT_SIZE = 19.sp
private val BACKING_LINE_HEIGHT = 24.sp
private const val BACKING_ALPHA = 0.72f

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
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsUnavailable: Boolean,
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
    var doubleTapSeekDirection by remember { mutableStateOf<Int?>(null) } // -1 = backward, 1 = forward
    var doubleTapSeekSeconds by remember { mutableIntStateOf(10) }
    var doubleTapSeekTrigger by remember { mutableLongStateOf(0L) }

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
        haptics.play(Haptic.Tap)
        applySheetState(PanelSheetState.COLLAPSED)
    }

    val closeQueueToPlayer = remember(scope) {
        {
            closeToFullPlayer()
        }
    }

    LaunchedEffect(song.videoId) {
        if (navMode == NowPlayingMode.LYRICS) {
            closeToFullPlayer()
        }
    }

    BackHandler(enabled = sheetState != PanelSheetState.COLLAPSED) {
        closeToFullPlayer()
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val view = LocalView.current
        DisposableEffect(view, sheetState) {
            val callback = if (sheetState != PanelSheetState.COLLAPSED) {
                OverlayBack.register(view) {
                    closeToFullPlayer()
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
                .fullPlayerSwipeUp(
                    enabled = sheetState == PanelSheetState.COLLAPSED && navMode == NowPlayingMode.FULL_PLAYER,
                    onSwipeUp = ::openQueue,
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
                            .width(38.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.32f)),
                    )
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
                            } else {
                                Modifier.pointerInput(Unit) {
                                    detectDoubleTapSeek { isForward ->
                                        val currentSeek = currentSeekSeconds
                                        val currentPos = currentPositionMs
                                        val currentDur = currentDurationMs
                                        doubleTapSeekDirection = if (isForward) 1 else -1
                                        doubleTapSeekSeconds = currentSeek
                                        doubleTapSeekTrigger = System.currentTimeMillis()
                                        haptics.play(if (isForward) Haptic.SkipNext else Haptic.SkipPrevious)
                                        val seekDelta = currentSeek * 1000L
                                        val target = if (isForward) {
                                            if (currentDur > 0) {
                                                (currentPos + seekDelta).coerceAtMost(
                                                    (currentDur - SEEK_END_GUARD_MS).coerceAtLeast(0L),
                                                )
                                            } else {
                                                currentPos + seekDelta
                                            }
                                        } else {
                                            (currentPos - seekDelta).coerceAtLeast(0L)
                                        }
                                        currentOnSeek(target)
                                    }
                                }
                            },
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
                                    .background(Color.Black.copy(alpha = 0.50f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = if (isFwd) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "${if (isFwd) "+" else "−"}${doubleTapSeekSeconds}s",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = Color.White,
                                )
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
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.W500,
                                fontSize = titleSize,
                            ),
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.opensPage(song.artistId, onOpenArtist),
                        )
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
                    CircleGlyph(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = "More",
                        onClick = onOpenMenu,
                    )
                }

                if (navMode == NowPlayingMode.LYRICS && p > 0.01f) {
                    LyricsPanel(
                        lines = lyrics.orEmpty(),
                        trackKey = song.videoId,
                        positionMs = positionMs,
                        isPlaying = isPlaying,
                        onSeekToLine = onSeek,
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
                            durationMs = durationMs,
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
                            trackKey = song.videoId,
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



/**
 * The song position, ticking every frame.
 *
 * The player reports where it is about twice a second, which is fine for a
 * scrubber and far too coarse for a highlight that has to keep up with a
 * singer. This carries that report forward on the frame clock between
 * reports, and resets to the real value whenever a fresh one lands — so it
 * never drifts, it just fills in.
 *
 * Returned as state rather than a plain value on purpose: read inside a draw
 * lambda, only the draw phase re-runs each frame. Read in composition, the
 * whole line would recompose sixty times a second.
 */
@Composable
private fun rememberLyricClock(positionMs: Long, isPlaying: Boolean): MutableLongState {
    val clock = remember { mutableLongStateOf(positionMs) }
    // Gated on the app being on screen. The loop asks for a frame, writes a
    // value that invalidates a drawing, and is handed the next frame for it —
    // which is a request to render continuously for as long as it runs. That is
    // the right trade for a lyric being read and the wrong one for a phone in a
    // pocket, and the composition alone cannot tell the two apart.
    //
    // Resuming needs no catch-up: [positionMs] is a key, so coming back
    // restarts the effect and the clock is set from the player's own position
    // before the first frame is asked for.
    val foreground = rememberIsForeground()
    LaunchedEffect(positionMs, isPlaying, foreground) {
        clock.longValue = positionMs
        if (!isPlaying || !foreground) return@LaunchedEffect
        var previousFrame = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame ->
                clock.longValue += frame - previousFrame
                previousFrame = frame
            }
        }
    }
    return clock
}

/**
 * A lyric line with the sung part of it lit, the rest dimmed, and the boundary
 * travelling across the words in time with the vocal.
 *
 * Two copies of the same text stacked: a dim one and a bright one clipped to
 * whatever has been sung. Same string, same style, same constraints, so the
 * two lay out identically and the bright copy lands exactly on top of the dim
 * one. The alternative — colouring an AnnotatedString word by word — can only
 * change a whole word at a time, which turns the sweep into a flicker.
 *
 * The clip is recomputed in the draw phase, so a frame costs one clip and one
 * redraw of already-measured text.
 *
 * [glowAlpha] adds Apple's bloom: a third copy, blurred, behind the other two
 * and clipped to the same boundary. Blurring *after* the clip rather than
 * before is what makes the halo bleed a little way past the sweep's leading
 * edge, which is the part that reads as light coming off the word being sung
 * rather than a drop shadow sitting under the line.
 */
@Composable
private fun SweptLyricLine(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    // Carried by every copy: identical insets keep them laying out identically,
    // and the inset is what gives the blurred copy's layer somewhere to put the
    // halo. Sits inside the blur and outside the draw lambdas, so text-layout
    // coordinates and draw coordinates still agree.
    //
    // Off unless asked for. Only the full panel can afford it — it takes the
    // space back off its own row spacing and content padding. Handed to the
    // one-line strip above the scrubber, where there is no glow to make room
    // for and nothing paying the space back, it just left the line sitting in
    // a pocket of air with the chevron pushed off it.
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    val sweep = Modifier.drawWithContent {
        val position = clock.longValue
        when {
            // Sung and done with: all of it is lit. Checked first so the lines
            // above and below the playing one — which are in this same state
            // for minutes at a time — cost a comparison per frame rather than
            // a walk of their words.
            position >= line.endMs -> drawContent()
            // Not started: nothing lit, the dim copy is the whole of it.
            position <= line.timeMs -> Unit
            else -> layout?.let { sweepTo(it, line.revealedChars(position)) }
        }
    }

    Box(modifier) {
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = room,
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = Color.White,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    // Read in the layer block rather than in composition: the
                    // intensity changes every frame, and this way only the
                    // layer's alpha is recomputed, not the line.
                    .graphicsLayer { alpha = glowAlpha * line.glowIntensity(clock.longValue) }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    // The band is masked with a DstIn gradient, which needs a
                    // layer of its own to erase into — against the backdrop it
                    // would take the artwork with it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // Deliberately not the shared sweep: that lights
                        // everything sung so far, and this is only the front of
                        // it. No short-circuit either — the glow layer only
                        // exists for the line being sung, so it is one line's
                        // worth of arithmetic, not the whole panel's.
                        val measured = layout ?: return@drawWithContent
                        val position = clock.longValue
                        glowAt(
                            layout = measured,
                            revealedChars = line.revealedChars(position),
                            intensity = line.glowIntensity(position),
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            maxLines = maxLines,
            overflow = overflow,
            modifier = room.then(sweep),
        )
    }
}

/**
 * Draws this text clipped to a band trailing the sweep's leading edge — the
 * word being sung, roughly, rather than the whole of what has been.
 *
 * The band widens with [intensity] as well as brightening, so a held note
 * spreads its light over the words either side of it while patter keeps its
 * halo tight to the one syllable. Alpha alone made every word glow the same
 * shape, only more or less of it.
 *
 * Only ever one band: the edge is on exactly one visual line, and a wrapped
 * line's previous row has already been left behind by the time the band would
 * have reached back into it.
 */
private fun ContentDrawScope.glowAt(
    layout: TextLayoutResult,
    revealedChars: Float,
    intensity: Float,
) {
    val length = layout.layoutInput.text.length
    if (length == 0 || revealedChars <= 0f || intensity <= 0f) return

    val edge = revealedChars.coerceIn(0f, length.toFloat())
    val visualLine = layout.getLineForOffset(edge.toInt().coerceIn(0, length - 1))
    val lineStart = layout.getLineStart(visualLine)
    val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)

    val right = horizontalAt(layout, edge.coerceIn(lineStart.toFloat(), lineEnd.toFloat()), lineStart, lineEnd)
    val trail = GLOW_TRAIL.toPx() * (GLOW_TRAIL_FLOOR + (1f - GLOW_TRAIL_FLOOR) * intensity)
    val left = (right - trail).coerceAtLeast(layout.getLineLeft(visualLine))
    if (right <= left) return

    // The band, cut out of the line. This is only the vertical and trailing
    // bounds; how it fades across is the mask below.
    clipRect(
        left = left,
        top = layout.getLineTop(visualLine),
        right = right,
        bottom = layout.getLineBottom(visualLine),
    ) {
        this@glowAt.drawContent()
    }

    // Full strength at the leading edge, ebbing away behind it. Without this
    // the band has a hard back edge, and a hard edge travelling along at a
    // constant distance behind the sweep is exactly what reads as a fixed-width
    // block of light being dragged across the words.
    //
    // Painted over the whole node rather than inside the clip on purpose:
    // DstIn keeps what the mask covers and erases the rest, and the brush
    // clamps past its ends — transparent to the left of the band, opaque to
    // the right, where the clip has already left nothing to keep.
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.45f to Color.White.copy(alpha = 0.22f),
            1f to Color.White,
            startX = left,
            endX = right,
        ),
        blendMode = BlendMode.DstIn,
    )
}

/** Where a fractional character index sits across a visual line, in pixels. */
private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    lineStart: Int,
    lineEnd: Int,
): Float {
    val index = chars.toInt().coerceIn(lineStart, lineEnd)
    val here = layout.getHorizontalPosition(index, usePrimaryDirection = true)
    val next = layout.getHorizontalPosition(
        (index + 1).coerceAtMost(lineEnd),
        usePrimaryDirection = true,
    )
    return here + (next - here) * (chars - index)
}

/**
 * Draws this text clipped to its first [revealedChars] characters.
 *
 * Wrapped lines are handled a visual line at a time: the ones already passed
 * are drawn whole, the one holding the boundary is cut at it, and the rest are
 * left to the dim copy. Within a word the cut sits between two character
 * positions, so the edge advances smoothly rather than jumping a letter at a
 * time.
 */
private fun ContentDrawScope.sweepTo(layout: TextLayoutResult, revealedChars: Float) {
    if (revealedChars <= 0f) return
    if (revealedChars >= layout.layoutInput.text.length) {
        drawContent()
        return
    }
    for (visualLine in 0 until layout.lineCount) {
        val start = layout.getLineStart(visualLine)
        // Lines beyond the boundary have nothing lit on them, and neither has
        // anything after them.
        if (revealedChars <= start) return
        val end = layout.getLineEnd(visualLine, visibleEnd = true)
        val right = if (revealedChars >= end) {
            layout.getLineRight(visualLine)
        } else {
            horizontalAt(layout, revealedChars, start, end)
        }
        clipRect(
            left = layout.getLineLeft(visualLine),
            top = layout.getLineTop(visualLine),
            right = right,
            bottom = layout.getLineBottom(visualLine),
        ) {
            this@sweepTo.drawContent()
        }
    }
}


/**
 * Apple Music's lyrics view: big tight type, the playing line crisp and
 * everything else falling out of focus the further it is from it. Blur needs
 * API 31+, so alpha carries the same hierarchy on older devices.
 *
 * Scrolling by hand clears the blur and suspends the auto-follow, so you can
 * read ahead; a couple of seconds after you stop it snaps back to the song.
 */
private val LyricLineShape = RoundedCornerShape(10.dp)

@Composable
private fun LyricsPanel(
    lines: List<LyricLine>,
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
    onSeekToLine: (Long) -> Unit,
    onClose: () -> Unit = {},
    listState: LazyListState = remember(trackKey) { LazyListState() },
    modifier: Modifier = Modifier,
) {
    val clock = rememberLyricClock(positionMs, isPlaying)

    // Which line is playing right now: the last one whose stamp has passed.
    //
    // Read off the frame clock rather than the player's own position, which
    // only lands twice a second. Taken from there, a line change was up to
    // half a second late — and with the highlight itself running on the frame
    // clock, that lateness was visible: the sweep would finish a line and sit
    // at the end of it, waiting for the screen to admit the next one had
    // started. derivedStateOf keeps the cost of the finer clock off
    // composition; it only notifies when the index actually changes, not on
    // every frame that feeds it.
    val activeLine by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
    }
    // A background vocal routinely holds past the *next* line's own stamp —
    // that's the whole reason it's carried apart, see [LyricLine.background].
    // Taken on [activeLine] alone, the row above dropped out of its active
    // treatment the instant the next line's stamp passed, so its sweep lost
    // the glow and full brightness mid-bracket while the words were still
    // being sung. This is the one line behind [activeLine] kept active
    // alongside it for as long as its own end — background included — hasn't
    // arrived yet, so the two rows animate together instead of the first
    // being cut off under the second.
    val alsoActive by remember(lines) {
        derivedStateOf {
            val previous = activeLine - 1
            val line = lines.getOrNull(previous)
            if (line != null && line.hasKnownEnd && clock.longValue < line.endMs) previous else -1
        }
    }
    var browsing by remember(trackKey) { mutableStateOf(false) }
    var placed by remember(trackKey, lines) { mutableStateOf(false) }
    var lastScrolledLine by remember(trackKey, lines) { mutableIntStateOf(-1) }

    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    // The bloom is a blurred copy of the line, so it is off wherever blur is:
    // below API 31 Modifier.blur does nothing and the "glow" would land as a
    // second sharp copy of the text — fake bold, not light. Both of the
    // reduce-* settings turn it off too. Reduce animation because it is the
    // switch for exactly this kind of flourish, and reduce dynamic blur
    // because adding a blur under a setting that says it drops them would be
    // the app disagreeing with itself.
    val glowing = !reduceAnimation && !reduceDynamicBlur &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Track user drag interaction to enter browsing mode.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                browsing = true
                lastScrolledLine = -1
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
    val currentLine by rememberUpdatedState(activeLine)
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

    // Follow the song, keeping the active line a third of the way down.
    //
    // The very first placement is a jump, not a scroll. Later moves animate smoothly
    // when advancing line-by-line, or snap cleanly if a large jump (seek / long browse) occurred.
    LaunchedEffect(activeLine, browsing, listState.isScrollInProgress) {
        if (!browsing && !listState.isScrollInProgress && activeLine >= 0 && activeLine in lines.indices) {
            val currentHeight = listState.layoutInfo.viewportSize.height
            val viewport = if (currentHeight > 0) {
                currentHeight
            } else {
                snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            }
            val third = viewport / 3
            if (!placed) {
                listState.scrollToItem(activeLine, scrollOffset = -third)
                placed = true
                lastScrolledLine = activeLine
            } else if (activeLine != lastScrolledLine) {
                val distance = abs(activeLine - listState.firstVisibleItemIndex)
                if (distance > 5) {
                    listState.scrollToItem(activeLine, scrollOffset = -third)
                } else {
                    listState.animateScrollToItem(activeLine, scrollOffset = -third)
                }
                lastScrolledLine = activeLine
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
            lastScrolledLine = -1
        },
        onClose = onClose,
    )

    if (lines.isEmpty()) {
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

    val lyricStyle = MaterialTheme.typography.headlineLarge.copy(
        fontSize = 27.sp,
        lineHeight = 33.sp,
    )
    val backingStyle = remember(lyricStyle) {
        lyricStyle.copy(
            fontSize = BACKING_FONT_SIZE,
            lineHeight = BACKING_LINE_HEIGHT,
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .bleedHorizontally(PLAYER_GUTTER)
            .fadingEdges()
            .nestedScroll(swallowDownOverscroll)
            .then(swipeDownModifier),
        // Each row carries GLOW_ROOM of its own inset for the halo, so the
        // list hands that much back — otherwise the lines would sit a glow's
        // width further apart and further in than they used to.
        contentPadding = PaddingValues(
            vertical = 40.dp - GLOW_ROOM,
            horizontal = PLAYER_GUTTER - GLOW_ROOM,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        itemsIndexed(
            items = lines,
            key = { index, line -> ((line.timeMs shl 16) or (index.toLong() and 0xFFFF)) },
            contentType = { _, line -> if (line.isGap) "gap" else "lyric" },
        ) { index, line ->
            // Signed rather than absolute: a line already sung and one still to
            // come are not the same distance from being read, even at the same
            // number of rows away, so the two fade at different rates below.
            val offset = if (activeLine < 0) 0 else index - activeLine
            val distance = abs(offset)
            val isActive = index == activeLine || index == alsoActive
            // Lines already sung stay close to legible — they're what the eye
            // just read and glances back to. Lines still to come fade faster
            // and further, so the panel reads as an arrival rather than a wall
            // of equally-weighted text.
            val lineAlpha by animateFloatAsState(
                targetValue = when {
                    browsing -> 1f
                    isActive -> 1f
                    offset < 0 -> (0.55f - distance * 0.05f).coerceAtLeast(0.30f)
                    else -> (0.45f - distance * 0.09f).coerceAtLeast(0.12f)
                },
                label = "lyricAlpha",
            )
            if (line.isGap) {
                val noteSize by animateDpAsState(
                    targetValue = if (isActive) 34.dp else 26.dp,
                    label = "noteSize",
                )
                Icon(
                    imageVector = YZMusicIcons.MusicNote,
                    contentDescription = "Instrumental",
                    tint = Color.White.copy(alpha = lineAlpha),
                    modifier = Modifier
                        .clip(LyricLineShape)
                        .clickable {
                            browsing = false
                            lastScrolledLine = -1
                            onSeekToLine(line.timeMs)
                        }
                        // Matches the inset every sung line carries, so the
                        // rhythm of the list doesn't break at a break.
                        .padding(GLOW_ROOM)
                        .size(noteSize),
                )
            } else {
                // The playing line swells a touch. Anchored to its left edge,
                // so the words don't slide sideways under the highlight as it
                // grows — scaling about the centre would fight the sweep.
                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.04f else 1f,
                    label = "lyricScale",
                )
                // Apple's bloom on the line being sung. Fades in and out with
                // the line rather than switching, so a handover is one line's
                // light going down as the next one's comes up.
                val glow by animateFloatAsState(
                    targetValue = if (isActive && glowing) GLOW_ALPHA else 0f,
                    animationSpec = tween(durationMillis = 420),
                    label = "lyricGlow",
                )
                val shape = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        alpha = lineAlpha
                    }
                    .clip(LyricLineShape)
                    .clickable {
                        browsing = false
                        lastScrolledLine = -1
                        onSeekToLine(line.timeMs)
                    }
                // Lead and answering vocal are one row: they are one line of
                // the song, they scale and dim together, and tapping either
                // seeks to the same place.
                Column(modifier = shape) {
                    PanelVoice(
                        line = line,
                        clock = clock,
                        style = lyricStyle,
                        isActive = isActive,
                        glowAlpha = glow,
                        room = GLOW_ROOM,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    line.background?.let { backing ->
                        val cleanBacking = remember(backing) { backing.withoutBracketPunctuation() }
                        PanelVoice(
                            line = cleanBacking,
                            clock = clock,
                            style = backingStyle,
                            isActive = isActive,
                            // No bloom on the second voice. The glow marks
                            // what is being sung *at you*; putting it on both
                            // makes the row read as two equal lines, which is
                            // the thing this split exists to stop.
                            glowAlpha = 0f,
                            room = 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                // No top inset: the lead's own bottom room is
                                // the gap, which leaves the two voices closer
                                // to each other than to the rows either side.
                                .padding(start = GLOW_ROOM, end = GLOW_ROOM, bottom = GLOW_ROOM)
                                .graphicsLayer { alpha = BACKING_ALPHA },
                        )
                    }
                }
            }
        }
    }
}


/**
 * One voice of a row in [LyricsPanel] — the lead, or the answering line drawn
 * under it.
 *
 * Both go through the same sweep. A backing vocal carries its own word
 * timings, so it lights up on its own clock rather than borrowing the lead's:
 * that is the whole point of splitting it out, and it is why the bracket no
 * longer gets cut off when the next line's stamp arrives mid-phrase.
 */
@Composable
private fun PanelVoice(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    glowAlpha: Float,
    room: Dp,
    modifier: Modifier = Modifier,
) {
    if (line.isWordSynced) {
        // Every word-synced line goes through the sweep, not just the playing
        // one — a line that has already been sung is fully revealed and one
        // still to come is not, which falls out of the same arithmetic.
        //
        // Running it only on the active line meant swapping this composable
        // for a plain Text the instant a line handed over, and the two
        // disagreed about the brightness of the words: the tail of the line
        // popped up to meet the rest of it in a single frame. Animating the
        // tail instead lets a finished line close up as it dims away.
        val tail by animateFloatAsState(
            targetValue = if (isActive) UNSUNG_ALPHA else 1f,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = glowAlpha,
            glowRoom = room,
        )
    } else {
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            modifier = modifier.padding(room),
        )
    }
}

/**
 * The answering vocal without the parentheses every text-only source wraps it
 * in — see [withBackgroundVocals]. Apple Music draws its own equivalent line
 * bare, and the brackets were only ever there to mark the split before there
 * was a row of its own to draw it on.
 *
 * The LRC writer still gets the line with its brackets: that punctuation is
 * what the provider published, so a downloaded file keeps it. This is a
 * display-only trim, done here rather than in the data layer, and applied to
 * the words too, not just [LyricLine.text] — [SweptLyricLine] measures the
 * words against the text it draws, and a sweep reading "(echoed" against a
 * line reading "echoed" would search for a substring that is no longer there.
 */
private fun LyricLine.withoutBracketPunctuation(): LyricLine = copy(
    text = text.stripParens(),
    words = words.mapNotNull { word ->
        word.text.stripParens().takeIf { it.isNotEmpty() }?.let { word.copy(text = it) }
    },
)

private fun String.stripParens(): String = replace("(", "").replace(")", "").trim()


/**
 * The single lyric line above the scrubber.
 *
 * A line dims away just before its time is up and the next one arrives at full
 * strength — no fade in, so the change reads as a cut rather than a dissolve.
 * The fade is a fraction of the line's own length, so rapid-fire lines snap and
 * long held ones ebb out.
 *
 * Position is interpolated between the player's twice-a-second reports,
 * otherwise the fade would step. The alpha is applied in a graphicsLayer so
 * only the draw phase runs each frame; the text itself recomposes just once
 * per line.
 */
@Composable
private fun CurrentLyricLine(
    lines: List<LyricLine>,
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = rememberLyricClock(positionMs, isPlaying)

    val index by remember(lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
    }
    val current = lines.getOrNull(index)
    // Before the first line, and through instrumental breaks, show the note.
    val instrumental = current == null || current.isGap
    // Everything ahead of the first sung line is the intro — LRC files open on a
    // bare [00:00.00] gap, so that stretch is gap lines rather than nothing.
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
    val intro = instrumental && firstSung >= 0 && index < firstSung
    // The intro gets one of the slang lines; mid-song breaks stay plain.
    val introLine = remember(trackKey) { INTRO_LINES.random() }
    // The strip is one line and switches the moment the next one is due, so
    // the answering vocal — where there is one — has nowhere to go: showing
    // it would mean either cutting it short when the next line arrives or
    // holding the strip back and leaving a gap before the next line's own
    // words appear. [LyricsPanel] has the room to draw it properly; here it
    // is simply left off, same as before this line had a bracket in it.
    val text = when {
        intro -> introLine
        instrumental -> INSTRUMENTAL_MARK
        else -> current.text
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .graphicsLayer {
                if (instrumental) {
                    // Nothing is being sung; hold it steady rather than fading.
                    alpha = 0.5f
                    return@graphicsLayer
                }
                val start = lines.getOrNull(index)?.timeMs ?: 0L
                val end = lines.getOrNull(index + 1)?.timeMs
                    ?: durationMs.takeIf { it > start }
                    ?: (start + 4_000L)
                val fade = ((end - start) * LYRIC_FADE_FRACTION)
                    .coerceIn(LYRIC_FADE_MIN_MS, LYRIC_FADE_MAX_MS)
                val remaining = (end - clock.longValue).toFloat()
                alpha = 0.78f * (remaining / fade).coerceIn(0f, 1f)
            },
    ) {
        if (instrumental) {
            Icon(
                imageVector = YZMusicIcons.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        val swept = current?.takeIf { !instrumental && it.isWordSynced }
        if (swept != null) {
            SweptLyricLine(
                line = swept,
                clock = clock,
                style = MaterialTheme.typography.titleMedium,
                dimAlpha = UNSUNG_ALPHA_STRIP,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.width(6.dp))
        // Disclosure hint: this strip opens the full lyrics screen.
        Icon(
            imageVector = YZMusicIcons.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * Stands in for [CurrentLyricLine] once a lookup has come back empty — shown
 * for a few seconds so it registers, then left to fade rather than snapping
 * out or lingering for the rest of the track.
 */
@Composable
private fun LyricsUnavailableLine(trackKey: Any, modifier: Modifier = Modifier) {
    var visible by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        delay(LYRICS_UNAVAILABLE_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.55f else 0f,
        animationSpec = tween(durationMillis = LYRICS_UNAVAILABLE_FADE_MS),
        label = "lyricsUnavailableAlpha",
    )
    Text(
        text = "Lyrics not available",
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

/** Stands in for [CurrentLyricLine] while a lookup is still in flight. */
@Composable
private fun LyricsLoadingLine(trackKey: Any, modifier: Modifier = Modifier) {
    val text = remember(trackKey) { LYRICS_LOADING_LINES.random() }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

/**
 * Translucent circular button used for the track menu and the like control.
 *
 * [active] brightens the disc rather than only the glyph: this sits on album
 * artwork of any colour, and a white icon on a white-ish sleeve has no tint
 * change left to make. The filled heart carries the state as a shape too —
 * see [YZMusicIcons.HeartFilled].
 */
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

    val manualRows = remember(queue, autoplayStart) {
        if (autoplayStart in 0..queue.size) queue.subList(0, autoplayStart) else queue
    }
    val autoplayRows = remember(queue, autoplayStart) {
        if (autoplayStart in 0..queue.size) queue.subList(autoplayStart, queue.size) else emptyList()
    }

    // Stable keys per row, unique even for duplicate tracks and persisting across reordering.
    val queueKeys = rememberQueueKeys(queue)

    val headingShown = (autoplayEnabled || autoplayStart < queue.size) && autoplayStart in 0..queue.size
    val totalLazyRows = queue.size + if (headingShown) 1 else 0

    // Single unified drag state for the entire queue.
    val dragState = rememberQueueDragState(
        listState = listState,
        lazyRange = 0 until totalLazyRows,
        toQueueIndex = { lazyIndex ->
            lazyToQueueIndex(lazyIndex, autoplayStart, headingShown)
        },
        onMove = onMove,
    )

    // Open on what's playing, not at the top of a long queue.
    LaunchedEffect(currentIndex) {
        if (!dragState.isDragging && currentIndex in queue.indices) {
            val targetLazyIndex = queueToLazyIndex(currentIndex, autoplayStart, headingShown)
            listState.scrollToItem(targetLazyIndex)
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val holding = dragState.isDragging

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
                .nestedScroll(swallowDownOverscroll)
                .queueSwipeDown(
                    enabled = true,
                    listState = listState,
                    scope = coroutineScope,
                    isReordering = holding,
                    allowScrollToTop = false,
                    consumeDownDeltas = false,
                    onClose = onClose,
                ),
            contentPadding = PaddingValues(horizontal = PLAYER_GUTTER),
        ) {
            itemsIndexed(
                items = manualRows,
                key = { index, _ -> queueKeys.getOrElse(index) { "queue_$index" } },
                contentType = { _, _ -> "queue_song" },
            ) { index, song ->
                val key = queueKeys.getOrElse(index) { "queue_$index" }
                val dragging = dragState.draggedKey == key
                InlineQueueRow(
                    song = song,
                    isCurrent = index == currentIndex,
                    onClick = { onJumpTo(index) },
                    onRemove = { onRemove(index) },
                    draggable = true,
                    dragging = dragging,
                    onDragStart = { dragState.onDragStart(key) },
                    onDrag = dragState::onDrag,
                    onDragEnd = dragState::onDragEnd,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragState.renderOffset else 0f }
                        .then(if (dragState.draggedKey != null) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)),
                )
            }
            // Heading first, then what AutoPlay has lined up under it. With
            // nothing lined up yet it closes the queue as a promise instead.
            if (headingShown) {
                item(key = "autoplay-heading", contentType = "autoplay_heading") {
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
                key = { index, _ -> queueKeys.getOrElse(autoplayStart + index) { "queue_${autoplayStart + index}" } },
                contentType = { _, _ -> "queue_song" },
            ) { index, song ->
                val at = autoplayStart + index
                val key = queueKeys.getOrElse(at) { "queue_$at" }
                val dragging = dragState.draggedKey == key
                InlineQueueRow(
                    song = song,
                    isCurrent = at == currentIndex,
                    onClick = { onJumpTo(at) },
                    onRemove = { onRemove(at) },
                    draggable = true,
                    dragging = dragging,
                    onDragStart = { dragState.onDragStart(key) },
                    onDrag = dragState::onDrag,
                    onDragEnd = dragState::onDragEnd,
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragState.renderOffset else 0f }
                        .then(if (dragState.draggedKey != null) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)),
                )
            }
        }
    }
}

internal fun lazyToQueueIndex(lazyIndex: Int, autoplayStart: Int, headingShown: Boolean): Int {
    return if (headingShown && lazyIndex > autoplayStart) lazyIndex - 1 else lazyIndex
}

internal fun queueToLazyIndex(queueIndex: Int, autoplayStart: Int, headingShown: Boolean): Int {
    return if (headingShown && queueIndex >= autoplayStart) queueIndex + 1 else queueIndex
}

/**
 * A key per row, stable across a reorder and unique even when the same song
 * appears twice. Uses [Song.playlistSetVideoId] if present, disambiguating
 * any collisions so LazyColumn never throws on duplicate keys.
 */
@Composable
private fun rememberQueueKeys(queue: List<Song>): List<String> {
    val keyCache = remember { java.util.IdentityHashMap<Song, String>() }
    return remember(queue) {
        val used = HashSet<String>()
        queue.mapIndexed { index, song ->
            val existing = keyCache[song]
            if (existing != null && used.add(existing)) {
                existing
            } else {
                val candidate = song.setVideoId?.takeIf { it.isNotBlank() }
                    ?: "${song.videoId}#${System.identityHashCode(song)}"
                var key = candidate
                var seq = 1
                while (!used.add(key)) {
                    key = "${candidate}_$seq"
                    seq++
                }
                keyCache[song] = key
                key
            }
        }
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
    toQueueIndex: (Int) -> Int = { it },
    onMove: (Int, Int) -> Unit,
): QueueDragState {
    val state = remember(listState) { QueueDragState(listState) }
    state.lazyRange = lazyRange
    state.toQueueIndex = toQueueIndex
    state.onMove = onMove
    with(LocalDensity.current) {
        state.edgeZone = QUEUE_EDGE_SCROLL_ZONE.toPx()
        state.edgeSpeed = QUEUE_EDGE_SCROLL_SPEED.toPx()
    }

    val direction = state.autoScrollDir
    LaunchedEffect(state, direction) {
        if (direction == 0) return@LaunchedEffect
        listState.scroll {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
                previous = now
                val scrolled = scrollBy(state.autoScrollSpeed * seconds)
                if (scrolled == 0f) break
                state.onScrolled()
            }
        }
    }
    return state
}

private class QueueDragState(private val listState: LazyListState) {
    var lazyRange: IntRange = IntRange.EMPTY
    var toQueueIndex: (Int) -> Int = { it }
    var onMove: (Int, Int) -> Unit = { _, _ -> }

    val isDragging: Boolean get() = draggedKey != null

    /** [QUEUE_EDGE_SCROLL_ZONE] and [QUEUE_EDGE_SCROLL_SPEED], in pixels. */
    var edgeZone: Float = 0f
    var edgeSpeed: Float = 0f

    /** LazyColumn key of the row being dragged; null at rest. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set

    var renderOffset by mutableFloatStateOf(0f)
        private set

    var autoScrollDir by mutableIntStateOf(0)
        private set

    var autoScrollSpeed: Float = 0f
        private set

    private var heldCenter: Float = Float.NaN
    private var awaiting: Int? = null

    fun onDragStart(key: Any) {
        draggedKey = key
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    fun onDrag(deltaY: Float) = settle(deltaY)

    fun onScrolled() = settle(0f)

    fun onDragEnd() {
        draggedKey = null
        heldCenter = Float.NaN
        renderOffset = 0f
        awaiting = null
        setAutoScroll(0f)
    }

    private fun settle(deltaY: Float) {
        val key = draggedKey ?: return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.find { it.key == key } ?: run {
            setAutoScroll(0f)
            return
        }
        val half = dragged.size / 2f
        if (heldCenter.isNaN()) heldCenter = dragged.offset + half
        heldCenter += deltaY
        holdToSection(items, dragged)

        val top = heldCenter - half
        aimAutoScroll(top, dragged)
        renderOffset = insideViewport(top, dragged.size) - dragged.offset

        awaiting?.let {
            if (dragged.index != it) return
            awaiting = null
        }
        val target = swapTarget(items, dragged) ?: return
        val fromQueue = toQueueIndex(dragged.index)
        val toQueue = toQueueIndex(target.index)
        if (fromQueue != toQueue) {
            onMove(fromQueue, toQueue)
            awaiting = target.index
        }
    }

    private fun swapTarget(
        items: List<LazyListItemInfo>,
        dragged: LazyListItemInfo,
    ): LazyListItemInfo? {
        val target = items
            .filter { it.index in lazyRange && it.index != dragged.index && it.contentType == "queue_song" && it.key != "autoplay-heading" }
            .minByOrNull { abs((it.offset + it.size / 2f) - heldCenter) }
            ?: return null
        if (abs(heldCenter - (target.offset + target.size / 2f)) > target.size / 2f) return null
        if (target.index == listState.firstVisibleItemIndex && listState.canScrollBackward) {
            return null
        }
        return target
    }

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
            lazyRange.isEmpty() -> true
            speed < 0f -> dragged.index <= lazyRange.first || !listState.canScrollBackward
            speed > 0f -> dragged.index >= lazyRange.last || !listState.canScrollForward
            else -> true
        }
        setAutoScroll(if (blocked) 0f else speed)
    }

    private fun holdToSection(items: List<LazyListItemInfo>, dragged: LazyListItemInfo) {
        if (lazyRange.isEmpty()) return
        val half = dragged.size / 2f
        items.firstOrNull { it.index == lazyRange.first }?.let {
            heldCenter = heldCenter.coerceAtLeast(it.offset + half)
        }
        items.firstOrNull { it.index == lazyRange.last }?.let {
            heldCenter = heldCenter.coerceAtMost(it.offset + it.size - half)
        }
    }

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
    if (dragging) {
        val endDrag by rememberUpdatedState(onDragEnd)
        DisposableEffect(Unit) {
            onDispose { endDrag() }
        }
    }
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
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
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
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
 * Detects double-taps on the left or right half of the playback area.
 * Completely non-intrusive: does not consume down or drag events, allowing
 * vertical sheet dismiss and horizontal track swipes to work unimpeded.
 */
private suspend fun PointerInputScope.detectDoubleTapSeek(
    onDoubleTap: (Boolean) -> Unit,
) {
    val doubleTapTimeout = 350L
    val touchSlop = viewConfiguration.touchSlop
    val longPressTimeout = 500L

    awaitEachGesture {
        val down1 = awaitFirstDown(requireUnconsumed = false)
        val downTime1 = System.currentTimeMillis()
        val downPos1 = down1.position
        val isForward1 = downPos1.x >= size.width / 2f

        var up1: PointerInputChange? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down1.id } ?: break
            if ((change.position - downPos1).getDistance() > touchSlop) {
                return@awaitEachGesture
            }
            if (!change.pressed) {
                up1 = change
                break
            }
        }
        val firstUp = up1 ?: return@awaitEachGesture
        if (System.currentTimeMillis() - downTime1 > longPressTimeout) return@awaitEachGesture

        var down2: PointerInputChange? = null
        try {
            withTimeout(doubleTapTimeout) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val candidate = event.changes.firstOrNull { it.changedToDown() }
                    if (candidate != null) {
                        down2 = candidate
                        break
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            return@awaitEachGesture
        }
        val secondDown = down2 ?: return@awaitEachGesture
        val downTime2 = System.currentTimeMillis()
        val downPos2 = secondDown.position
        val isForward2 = downPos2.x >= size.width / 2f

        // Both taps must land on the same half (left for rewind, right for forward)
        if (isForward1 != isForward2) return@awaitEachGesture

        var up2: PointerInputChange? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == secondDown.id } ?: break
            if ((change.position - downPos2).getDistance() > touchSlop) {
                return@awaitEachGesture
            }
            if (!change.pressed) {
                up2 = change
                break
            }
        }
        if (up2 == null) return@awaitEachGesture
        if (System.currentTimeMillis() - downTime2 > longPressTimeout) return@awaitEachGesture

        onDoubleTap(isForward2)
    }
}

/**
 * Detects a deliberate upward swipe on the Full Player to open Queue.
 *
 * Requirements:
 * - Works anywhere on non-interactive areas (artwork, metadata, background, lower player area).
 * - Exactly ONE deliberate swipe opens Queue (never requires two swipes).
 * - Directionally locked: clear UP = Queue; horizontal movement never opens Queue.
 * - Accidental movements do nothing.
 * - Does not steal touches from interactive controls (e.g. ThinSlider, buttons).
 */
private fun Modifier.fullPlayerSwipeUp(
    enabled: Boolean,
    onSwipeUp: () -> Unit,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled) {
        val thresholdPx = 48.dp.toPx()
        val flickVelocityPx = 450.dp.toPx()
        val minFlickDistancePx = 20.dp.toPx()
        val touchSlopPx = viewConfiguration.touchSlop

        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = thresholdPx,
            flickVelocityPx = flickVelocityPx,
            minFlickDistancePx = minFlickDistancePx,
            touchSlopPx = touchSlopPx,
        )

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val pointerId = down.id
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            tracker.onGestureEnd()

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                // If a child control (e.g. ThinSlider) consumed position changes, abort swipe-up
                if (change.isConsumed && !tracker.gestureHandled) {
                    break
                }

                if (!change.pressed) {
                    val velocity = velocityTracker.calculateVelocity()
                    if (tracker.onRelease(velocity.y)) {
                        change.consume()
                        onSwipeUp()
                    }
                    break
                }

                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val totalDx = change.position.x - startPos.x
                val totalDy = change.position.y - startPos.y

                if (tracker.onPosition(totalDx, totalDy)) {
                    change.consume()
                    onSwipeUp()
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


