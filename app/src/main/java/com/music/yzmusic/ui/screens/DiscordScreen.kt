package com.music.yzmusic.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.yzmusic.R
import com.music.yzmusic.data.discord.DiscordRPC
import com.music.yzmusic.data.discord.SuperProperties
import com.music.yzmusic.data.model.CARD_ART_PX
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.ui.components.ChoiceAlert
import com.music.yzmusic.ui.components.DiscordTokenAlert
import com.music.yzmusic.ui.components.TextValueAlert
import com.music.yzmusic.ui.components.thumbnailBorder
import com.my.kizzy.rpc.KizzyRPC
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Which dot Discord draws on the avatar, and what it tells other people. */
enum class DiscordPresenceStatus(val value: String, val label: String, val detail: String) {
    ONLINE("online", "Online", "Green dot"),
    IDLE("idle", "Idle", "Amber crescent — as if away"),
    DND("dnd", "Do not disturb", "Red dash — suppresses their notifications too"),
}

/**
 * The verb on the profile. All four render the same card; only the line above it
 * changes, so this is purely how you'd rather have it read.
 */
enum class DiscordActivityKind(val value: String, val verb: String, val label: String) {
    LISTENING("listening", "Listening to", "Listening"),
    PLAYING("playing", "Playing", "Playing"),
    WATCHING("watching", "Watching", "Watching"),
    COMPETING("competing", "Competing in", "Competing"),
}

/**
 * The dialogs the Discord screen opens. Hoisted out to an enum because they are
 * rendered by the activity, above the tab bar and mini player, rather than
 * inside the scrolling screen where a full-screen scrim would be trapped.
 */
enum class DiscordDialog { TOKEN, STATUS, ACTIVITY_TYPE, ACTIVITY_NAME, BUTTON_1, BUTTON_2 }

private fun statusOf(value: String) =
    DiscordPresenceStatus.entries.firstOrNull { it.value == value } ?: DiscordPresenceStatus.ONLINE

private fun kindOf(value: String) =
    DiscordActivityKind.entries.firstOrNull { it.value == value } ?: DiscordActivityKind.LISTENING

/**
 * Discord Rich Presence: the account it posts as, what the card says, and a
 * live preview of it.
 *
 * The preview is the point of the screen. Every field here changes something
 * about a card the user cannot see from inside this app — so it draws the card
 * as Discord will, from the track actually playing, and updates as they type.
 */
@Composable
fun DiscordScreen(
    song: Song?,
    positionMs: Long,
    durationMs: Long,
    onOpenLogin: () -> Unit,
    onOpenDialog: (DiscordDialog) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val token by AppSettings.discordToken.collectAsStateWithLifecycle()
    val username by AppSettings.discordUsername.collectAsStateWithLifecycle()
    val name by AppSettings.discordName.collectAsStateWithLifecycle()
    val avatar by AppSettings.discordAvatar.collectAsStateWithLifecycle()
    val rpcEnabled by AppSettings.discordRpcEnabled.collectAsStateWithLifecycle()
    val useDetails by AppSettings.discordUseDetails.collectAsStateWithLifecycle()
