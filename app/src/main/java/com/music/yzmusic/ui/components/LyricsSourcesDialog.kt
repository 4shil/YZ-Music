package com.music.yzmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.data.lyrics.LyricsSource
import com.music.yzmusic.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Which lyric databases the player is allowed to ask.
 *
 * Same frosted iOS alert as [UpdateAvailableDialog], down to the shared
 * [ALERT_WIDTH]/[ALERT_CORNER] metrics and hairline [AlertRule]s, with the
 * action rows swapped for checkable ones. Checkmarks rather than Material
 * checkboxes: that is what a multiple-selection list looks like in this
 * lineage, and a column of square boxes would be the one Material thing left
 * on an otherwise Apple-shaped alert.
 *
 * The order shown is the order they are tried, and it is the user's to set:
 * drag a row by its handle to move it, which reorders independently of
 * whether the row is ticked — priority and participation are different
 * questions, and this is the one dialog for both.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LyricsSourcesDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val selected by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val savedOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                // Swallows the tap before it reaches the scrim behind, so
                // touching the card itself never dismisses it.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Lyrics Sources",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Tried in this order — drag to reorder. The highest-priority " +
                        "source to answer at all wins, unless Prioritize Syllable Lyrics says " +
                        "to keep looking for a word-synced one.",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            ReorderableSourceList(
                order = savedOrder,
                selected = selected,
                onReorder = AppSettings::setLyricsSourceOrder,
                onToggle = { source ->
                    val checked = source in selected
                    // The last one standing can't be unchecked — an empty list
                    // is indistinguishable from switching lyrics off, and there
                    // is already a switch for that a row above this dialog.
                    if (checked && selected.size <= 1) return@ReorderableSourceList
                    AppSettings.setLyricsSources(
                        if (checked) selected - source else selected + source,
                    )
                },
            )

            AlertRule()
            SyllableSyncToggle(
                checked = prioritizeSyllableSync,
                onToggle = { AppSettings.setPrioritizeSyllableSync(!prioritizeSyllableSync) },
            )

            AlertRule()
            AlertAction(
                label = "Reset to Default",
                emphasised = false,
                onClick = AppSettings::resetLyricsSourceSettings,
            )
            AlertRule()
            AlertAction(label = "Done", emphasised = true, onClick = onDismiss)
        }
    }
}

/**
 * Whether a merely line-synced answer is good enough on its own, or worth
 * holding out on for a word-synced one further down the priority order —
 * see the note on [AppSettings.prioritizeSyllableSync]. A single row rather
 * than one more entry in the checkable list above: this isn't a source to
 * ask or not, it's a rule about what to do once one has answered.
 */
@Composable
private fun SyllableSyncToggle(checked: Boolean, onToggle: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Prioritize Syllable Lyrics",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Keep searching past a whole-line match for a word-by-word one, " +
                    "wherever it falls in the order above",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(10.dp))
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Enabled",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * The checkable, drag-reorderable list of sources.
 *
 * Reordering is entirely local until a drag ends — [liveOrder] tracks the
 * list as rows are dragged past each other, and only the finished order is
 * written back through [onReorder]. Writing on every intermediate swap would
 * mean [AppSettings] round-tripping the list back down through
 * [savedOrder][AppSettings.lyricsSourceOrder] on every frame of a drag, fighting
 * the gesture that produced it.
 *
 * The drag keeps exactly two numbers: how far the finger has come since it
 * went down ([totalDrag]), and which slot it went down on ([startIndex]).
 * Where to draw the row and which slot it belongs in are both *derived* from
 * those, so neither can drift from the other however many swaps happen on the
 * way. See [SWAP_THRESHOLD] for why the crossing point is past the halfway
 * mark rather than on it.
 */
@Composable
