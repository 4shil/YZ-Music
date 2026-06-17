package com.music.yzmusic.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.music.yzmusic.ui.rememberIsForeground
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.music.yzmusic.data.Http
import com.music.yzmusic.data.canvas.CanvasArtwork
import com.music.yzmusic.data.canvas.CanvasCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * How long a clip gets to paint itself onto a surface it was just handed back
 * before the still art is brought in behind it instead. Long enough to cover a
 * decoder being re-created from cold, short enough that a clip which is never
 * coming back does not sit there as a hole for the length of a glance.
 */
private const val REPAINT_TIMEOUT_MS = 700L

/**
 * The looping video that plays over a track's cover art, sized to fill and
 * clipped by whatever laid it out.
 *
 * A second, deliberately unassuming ExoPlayer: silent, with its audio track
 * switched off entirely so a clip's soundtrack is never even fetched, and no
 * audio attributes — taking focus here would duck the music this is decorating.
 * It follows the transport, so pausing the track stops the sleeve moving too.
 *
 * Nothing is drawn until the first frame arrives, and the fade in from there
 * means a failed or slow clip simply leaves the still art showing rather than
 * flashing a black square over it. [CanvasArtwork.fallbackUrl] gets one try if
 * the first rendition won't decode.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    /** Fires once the clip has an actual frame on screen, and again if it drops back to none. */
    onRenderedChanged: (Boolean) -> Unit = {},
    /** A single frame off the playing clip, for callers that want to re-tint around it. */
    onFrameCaptured: (Bitmap) -> Unit = {},
    /**
     * Keep calling [onFrameCaptured] every so many milliseconds instead of
     * only once — for a caller re-tinting its backdrop off a
     * [CanvasSource.SPOTIFY][com.music.yzmusic.data.canvas.CanvasSource.SPOTIFY]
     * clip, which is worth following as it plays rather than settling on
     * whatever colours its opening frame happened to have. Null everywhere
     * else: re-reading a texture off the GPU costs a frame stall, and for the
     * other three sources there is nothing about a clip's own colour that its
     * first frame doesn't already say.
     */
    refreshFrameEveryMs: Long? = null,
    /**
     * How much of whatever is behind the clip it is currently hiding: 0 while
     * nothing is drawn, ramping to 1 as the first frame fades in, and back down
     * if it drops out again.
     *
     * A caller that stacks a still image under the clip needs this to take that
     * image back out from under it, and cannot get there from
     * [onRenderedChanged] alone — that fires when the fade *starts*. It matters
     * most with [bottomFade]: one gradient over each of two stacked layers
     * leaves the lower one showing through the upper one instead of the backdrop
     * showing through both, so the still art stays half-visible over the clip
     * for as long as it is left lit underneath.
     */
    onCoverChanged: (Float) -> Unit = {},
    /**
     * Share of the clip's height, measured up from its bottom edge, over which
     * it dissolves to nothing — 0 for a hard edge. See [setBottomFade] for why
     * this is a parameter here rather than a mask the caller could draw.
     */
    bottomFade: Float = 0f,
) {
    val context = LocalContext.current

    var url by remember(canvas) { mutableStateOf(canvas.url) }
    var rendered by remember(canvas) { mutableStateOf(false) }
    // Aspect of the clip itself. Zero until the decoder reports it, which is
    // also the signal that there is nothing sensible to crop to yet.
    var clipAspect by remember(canvas) { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var textureView by remember(canvas) { mutableStateOf<TextureView?>(null) }
    // Frames are counted rather than flagged, because [rendered] cannot answer
    // the question the repaint below has to ask: "did a frame land on *this*
    // surface", not "has one ever landed".
    var frameTick by remember(canvas) { mutableIntStateOf(0) }
    // Bumped each time the view is handed a surface to replace one that was
    // taken away — which, in practice, means each time the app comes back from
    // off screen. Not bumped for the first surface of all, which arrives with
    // nothing needing doing to it. See the repaint effect below.
    var surfaceGeneration by remember(canvas) { mutableIntStateOf(0) }

    val player = remember {
        ExoPlayer.Builder(context)
            // Shares the app's one OkHttp client, as everything that fetches
            // over the network here does — and wrapped in CanvasCache so a
            // loop past the first is read off disk rather than re-fetched;
            // see that object's doc for why this matters far more here than
            // it would for a clip played once.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(CanvasCache.dataSourceFactory(OkHttpDataSource.Factory(Http.client))),
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                rendered = true
                frameTick++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width * videoSize.pixelWidthHeightRatio
                if (width > 0f && videoSize.height > 0) {
                    clipAspect = width / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // One retry, at the other rendition. If that is the one that
                // just failed there is nowhere left to go: leave the still
                // art up rather than looping through a broken URL.
                val alternate = canvas.fallbackUrl
                if (alternate != null && alternate != url) {
                    url = alternate
                } else {
                    rendered = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(url) {
        rendered = false
        clipAspect = 0f
        val item = MediaItem.Builder().setUri(url)
        mimeTypeOf(url)?.let { item.setMimeType(it) }
        player.setMediaItem(item.build())
        player.prepare()
    }

    // Gated on the app being on screen as well as on the caller's own state.
    //
    // This is a video decoder. Left to [isPlaying] alone it goes on decoding
    // frames into a surface nobody can see for as long as the composition is
    // alive — which, with the phone in a pocket and music playing, is the whole
    // album. Worse on a detail page, whose caller passes a constant `true`
    // because "the page is only up while it's being read": true of a page being
    // looked at, not of one left open behind a locked screen.
    //
    // Held inside this component rather than asked of each caller, so no call
    // site can forget it. Pausing keeps the last frame on the surface and the
    // player prepared, so coming back resumes rather than reloads.
    val foreground = rememberIsForeground()
    LaunchedEffect(isPlaying, foreground) { player.playWhenReady = isPlaying && foreground }

    // Repaint a paused clip onto a surface it has just been given back.
    //
    // A TextureView's SurfaceTexture does not survive the app going off screen:
    // it is torn down with the activity's hardware layer and a brand new, empty
    // one is handed over on the way back. A clip that is playing fills it on the
    // next frame and nobody notices. A paused one has no next frame — the
    // decoder is parked, `setOutputSurface` does not redraw what was already
    // released to the old surface, and the view sits there transparent.
    //
    // Which reads as a hole rather than as a still sleeve, because by then the
    // still art underneath has been faded out from under the clip (see
    // [onCoverChanged]). So: seek to where we already are, which is the one
    // thing that makes a paused player render, and if no frame arrives from it
    // give up and drop back to the still art rather than leaving the hole.
    LaunchedEffect(surfaceGeneration) {
        if (surfaceGeneration == 0) return@LaunchedEffect
        // Playback repaints on its own, and prepare() paints the first frame.
        if (player.playWhenReady || player.playbackState == Player.STATE_IDLE) return@LaunchedEffect
        val before = frameTick
        player.seekTo(player.currentPosition)
        delay(REPAINT_TIMEOUT_MS)
        if (frameTick == before) rendered = false
    }

    LaunchedEffect(rendered) {
        onRenderedChanged(rendered)
        if (!rendered) return@LaunchedEffect
        // Let the surface actually paint the frame that just triggered this
        // before reading it back — grabbing it the instant the callback fires
        // can still catch the previous, empty buffer.
        withFrameMillis { }
        val view = textureView ?: return@LaunchedEffect
        runCatching { view.getBitmap() }.getOrNull()?.let(onFrameCaptured)
    }

    // The opt-in follow-up to the capture above, for a caller that asked for
    // one — see [refreshFrameEveryMs]. A separate effect rather than a loop
    // folded into the one above: that one is keyed on [rendered] so it fires
    // again on every fade-in, and this one only needs to start once a fade-in
    // has actually happened and then keep going for as long as it holds.
    LaunchedEffect(rendered, refreshFrameEveryMs) {
        val interval = refreshFrameEveryMs ?: return@LaunchedEffect
        if (!rendered) return@LaunchedEffect
        while (isActive) {
            delay(interval)
