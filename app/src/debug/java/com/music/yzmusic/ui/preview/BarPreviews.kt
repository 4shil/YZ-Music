package com.music.yzmusic.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.ui.components.BottomFadeScrim
import com.music.yzmusic.ui.components.BottomTab
import com.music.yzmusic.ui.components.FloatingBottomBar
import com.music.yzmusic.ui.components.FrostedTopBar
import com.music.yzmusic.ui.components.MiniPlayer
import com.music.yzmusic.ui.components.TopFadeBlur
import com.music.yzmusic.ui.icons.YZMusicIcons
import com.music.yzmusic.ui.theme.YZMusicTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/*
 * Design previews for the two bars and the surfaces behind them.
 *
 * In `src/debug` rather than `src/main` on purpose: the sample data and the
 * scaffolding below are worth nothing to a shipped APK, and the `ui-tooling`
 * renderer that draws them is itself a `debugImplementation`. Android Studio
 * picks these up on any *Debug variant, which is the one being worked in.
 *
 * WHAT THESE DO AND DO NOT SHOW
 *
 * [BottomFadeScrim] is a plain shader over a rect, so what the preview draws is
 * exactly what the device draws — the gradient can be judged here.
 *
 * [TopFadeBlur] cannot. Haze blurs by way of RenderEffect against a real
 * window, and the preview renderer has none, so the fade comes out as a flat
 * pane or as nothing at all. These previews are the place to settle the bar's
 * layout, type, colour and the scrim; the blur ramp itself has to be read on a
 * device — `./gradlew :app:installDevDebug`.
 */

/** Stand-in feed rows, so the bars have something to sit over. */
@Composable
private fun MockFeed(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(12) { i ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        // Alternating tones, so the ramp has both a light and a
                        // dark edge to be judged against rather than one flat
                        // field that hides where it starts.
                        .background(if (i % 2 == 0) Color(0xFF3A3A3C) else Color(0xFF8E8E93)),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Track title $i", style = MaterialTheme.typography.titleMedium)
                    Text(
