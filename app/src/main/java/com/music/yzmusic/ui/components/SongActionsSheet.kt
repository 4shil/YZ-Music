package com.music.yzmusic.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.yzmusic.data.model.LikeStatus
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.download.DownloadState
import com.music.yzmusic.download.Downloads
import com.music.yzmusic.playback.SleepTimer
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.theme.ArtworkPalette
import com.music.yzmusic.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Long-press menu for a track, in the shape music apps normally use.
 *
 * The account actions lead — rating, playlists, library — because they are
 * what the menu is opened for; the queue and navigation rows below it were
 * always the fallback for "I meant to do something with this song".
 *
 * Everything that writes to the account is hidden outright when [signedIn] is
 * false rather than shown and refused. The same goes for a track that is
 * playing from a local file or a finished download (`song.localUri != null`):
 * rating, playlists, downloading it again and sharing all assume a YouTube
 * identity the file doesn't carry, so those rows drop out regardless of
 * [signedIn].
 *
 * [showSleepTimer] and [onShare] are the player's extras: a sleep timer isn't a
 * property of some row in a list, so it only appears where it means something.
 *
 * [onDownload] is only the *start* of a download — cancelling one and deleting
 * a saved file are answered here, because neither needs anything the caller
 * has. Starting one might: below API 29 it needs a storage permission that only
 * an Activity can ask for.
 *
 * The sheet is painted in the track's own colours, the same way its album page
 * is — it is opened *from* that artwork, usually with it still on screen behind
 * the scrim, and a slab of flat grey in front of a coloured page reads as
 * something borrowed from another app. The host supplies no container colour
 * and no drag handle; both are drawn here, over the tint.
 */
@Composable
fun SongActionsSheet(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    showSleepTimer: Boolean = false,
    onShare: (() -> Unit)? = null,
    /**
     * Copies what the app logged while starting this track. Null everywhere
     * except the player, where "this track" means something.
     */
    onCopyLog: (() -> Unit)? = null,
    /**
     * True while a lookup for this track's album/artist ids is still in
     * flight, so it isn't yet known whether "Open album" and "Open artist"
     * belong on this sheet at all. Only the player ever opens a sheet before
     * it knows; everywhere else this is simply false, and the two rows behave
     * as before — present when the id is there, absent when it never was.
     */
    resolvingLinks: Boolean = false,
) {
    var pickingSleepTimer by remember { mutableStateOf(false) }
    // Read from the thumbnail the row that opened this sheet was already
    // showing, not a larger copy of it: the tint is a blur and a handful of
    // swatches, neither of which a bigger image improves, and going back for
    // one is what had the sheet opening grey and colouring in afterwards.
    val palette = rememberArtworkPalette(song.thumbnailUrl, artPx = ROW_ART_PX)
    val liked = likeStatus == LikeStatus.LIKE
    val disliked = likeStatus == LikeStatus.DISLIKE
    // A local file or a finished download has no YouTube identity behind it to
    // rate, save, queue into a playlist, fetch again, or share a link for.
    val isOffline = song.localUri != null

    TintedSheet(palette = palette, imageUrl = song.thumbnailUrl, modifier = modifier) {
        if (pickingSleepTimer) {
            SleepTimerPicker(palette = palette, onBack = { pickingSleepTimer = false })
            return@TintedSheet
        }

        SheetTrackHeader(song, subtitleColor = palette.onBackgroundVariant)
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        if (signedIn && !isOffline) {
            ActionRow(
                icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (liked) "Remove from Liked Music" else "Like",
                tint = if (liked) palette.accent else null,
                accent = palette.accent,
                onClick = onToggleLike,
            )
            ActionRow(
                icon = if (disliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                label = if (disliked) "Undo dislike" else "Dislike",
                tint = if (disliked) palette.accent else null,
                accent = palette.accent,
                onClick = onToggleDislike,
            )
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = "Add to playlist",
                accent = palette.accent,
                onClick = onAddToPlaylist,
            )
            onRemoveFromPlaylist?.let {
                ActionRow(
                    icon = Icons.Rounded.PlaylistRemove,
                    label = "Remove from this playlist",
                    accent = palette.accent,
                    onClick = it,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                thickness = 0.5.dp,
                color = palette.divider,
            )
        }

        DownloadRow(song, palette, isOffline, onDownload)
        ActionRow(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            label = "Play next",
            accent = palette.accent,
            onClick = onPlayNext,
        )
        ActionRow(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            label = "Add to queue",
            accent = palette.accent,
            onClick = onAddToQueue,
        )
        when (val id = song.albumId) {
            null -> if (resolvingLinks) LoadingActionRow(Icons.Rounded.Album, "Open album", palette)
            else -> ActionRow(Icons.Rounded.Album, "Open album", accent = palette.accent) { onOpenAlbum(id) }
        }
        when (val id = song.artistId) {
            null -> if (resolvingLinks) LoadingActionRow(Icons.Rounded.Person, "Open artist", palette)
            else -> ActionRow(Icons.Rounded.Person, "Open artist", accent = palette.accent) { onOpenArtist(id) }
        }
        if (showSleepTimer) {
            ActionRow(
                icon = Icons.Rounded.Bedtime,
                label = "Sleep timer",
                value = sleepTimerStatus(),
                accent = palette.accent,
            ) { pickingSleepTimer = true }
        }
        if (!isOffline) {
            onShare?.let {
                ActionRow(Icons.Rounded.Share, "Share", accent = palette.accent, onClick = it)
            }
        }
        // Last, and only from the player: it is about the track playing right
        // now rather than about the song as a thing in a library, and it is
        // the one row here nobody reaches for by accident.
        onCopyLog?.let {
            ActionRow(Icons.Rounded.BugReport, "Copy Log", accent = palette.accent, onClick = it)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A bottom sheet wearing the artwork's colours: the tint and its blurred wash
 * behind, the rounded top corners and the drag handle drawn over it.
 *
 * The corners and the handle are this composable's job rather than
 * `ModalBottomSheet`'s because the host has to pass a transparent container for
 * the wash to be visible at all — and a transparent container has nothing left
 * to clip or to hang a handle on.
 */
@Composable
private fun TintedSheet(
    palette: ArtworkPalette,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(SHEET_SHAPE),
    ) {
        ArtworkBackdrop(
            palette = palette,
            imageUrl = imageUrl,
            modifier = Modifier.matchParentSize(),
            // A sheet is a fraction of the height of a page, so the wash has
            // to resolve over a much shorter run to read the same way.
            washFraction = 0.75f,
            artPx = ROW_ART_PX,
        )
        Column(Modifier.fillMaxWidth()) {
            // Drawn rather than taken from BottomSheetDefaults, whose handle
            // carries 22dp of padding on each side — half a row's worth of
            // nothing between the grip and the track it is about.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 34.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(palette.onBackground.copy(alpha = 0.35f)),
                )
            }
            content()
        }
    }
}

private val SHEET_SHAPE = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * One row carrying the whole life of a download: start it, watch it, cancel it,
 * and delete what it produced.
 *
 * A row rather than a screen because that is the size of the decision. The
 * files land in the device's own Music folder, which already has a manager
 * — the Files app — and building a second one inside this app would be
 * duplicating it in a worse place. What this app uniquely knows is which *song*
 * a file belongs to, and that is exactly what this row says.
 *
 * The state comes straight from [Downloads] rather than through the caller: it
 * changes while the sheet is open, and threading a flow through the sheet's
 * signature would buy nothing over reading it where it's drawn — the same
 * arrangement the sleep timer row already uses.
 */
@Composable
private fun DownloadRow(song: Song, palette: ArtworkPalette, isOffline: Boolean, onDownload: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
