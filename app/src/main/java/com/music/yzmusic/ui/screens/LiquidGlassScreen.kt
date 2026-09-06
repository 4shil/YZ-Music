package com.music.yzmusic.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.R
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.BackdropQuality
import com.music.yzmusic.ui.components.isGlassSupported
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassBlur by AppSettings.glassBlur.collectAsStateWithLifecycle()
    val glassRefraction by AppSettings.glassRefraction.collectAsStateWithLifecycle()
    val backdropQuality by AppSettings.backdropQuality.collectAsStateWithLifecycle()

    var pickingBackdropQuality by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.liquid_glass),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        SettingsGroup(
            footer = stringResource(
                if (liquidGlassSupported) {
                    R.string.liquid_glass_subtitle
                } else {
                    R.string.liquid_glass_unavailable
                }
            )
        ) {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.liquid_glass),
                enabled = liquidGlassSupported,
                trailing = {
                    Switch(
                        checked = liquidGlass && liquidGlassSupported,
                        onCheckedChange = AppSettings::setLiquidGlass,
                        enabled = liquidGlassSupported,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                },
                onClick = { if (liquidGlassSupported) AppSettings.setLiquidGlass(!liquidGlass) },
            )
        }

        if (liquidGlass && liquidGlassSupported) {
            SettingsGroup(header = stringResource(R.string.controls)) {
                SliderRow(
                    icon = Icons.Rounded.BlurOn,
                    title = stringResource(R.string.glass_blur_title),
                    subtitle = stringResource(
                        if (reduceDynamicBlur) R.string.glass_blur_disabled_subtitle else R.string.glass_blur_subtitle
                    ),
                    value = "${(glassBlur * 100f).roundToInt()}%",
                    sliderValue = glassBlur,
                    onSliderValue = AppSettings::setGlassBlur,
                    valueRange = 0f..1f,
                    steps = 0,
                    enabled = !reduceDynamicBlur,
                    onReset = AppSettings::resetGlassBlur,
                )
                RowDivider()
                SliderRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = stringResource(R.string.glass_refraction_title),
                    subtitle = stringResource(
                        if (reduceDynamicBlur) R.string.glass_refraction_disabled_subtitle else R.string.glass_refraction_subtitle
                    ),
                    value = "${(glassRefraction * 100f).roundToInt()}%",
                    sliderValue = glassRefraction,
                    onSliderValue = AppSettings::setGlassRefraction,
                    valueRange = 0f..1f,
                    steps = 0,
                    enabled = !reduceDynamicBlur,
                    onReset = AppSettings::resetGlassRefraction,
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.HighQuality,
                    title = stringResource(R.string.backdrop_quality),
                    subtitle = stringResource(
                        if (reduceDynamicBlur) R.string.backdrop_quality_disabled_subtitle else R.string.backdrop_quality_subtitle
                    ),
                    value = backdropQuality.label,
                    enabled = !reduceDynamicBlur,
                    onClick = { pickingBackdropQuality = true },
                )
            }

            AnimatedVisibility(
                visible = backdropQuality == BackdropQuality.HIGH && !reduceDynamicBlur,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GROUP_INSET, vertical = 8.dp),
                    shape = GroupShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.backdrop_quality_high_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (pickingBackdropQuality) {
        ModalBottomSheet(
            onDismissRequest = { pickingBackdropQuality = false },
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            BackdropQualitySheet(
                selected = backdropQuality,
                onSelect = { quality ->
                    AppSettings.setBackdropQuality(quality)
                    pickingBackdropQuality = false
                },
            )
        }
    }
}

@Composable
private fun BackdropQualitySheet(
    selected: BackdropQuality,
    onSelect: (BackdropQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.HighQuality,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.backdrop_quality),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.backdrop_quality_sheet_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
        BackdropQuality.entries.forEach { quality ->
            val chosen = quality == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(quality)
                    }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (quality) {
                            BackdropQuality.LOW -> stringResource(R.string.low)
                            BackdropQuality.MEDIUM -> stringResource(R.string.medium)
                            BackdropQuality.HIGH -> stringResource(R.string.high)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = when (quality) {
                            BackdropQuality.LOW -> stringResource(R.string.backdrop_quality_low_subtitle)
                            BackdropQuality.MEDIUM -> stringResource(R.string.backdrop_quality_medium_subtitle)
                            BackdropQuality.HIGH -> stringResource(R.string.backdrop_quality_high_subtitle)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
