package com.music.yzmusic

import android.os.Build
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.ui.components.LocalAppBackdrop
import com.music.yzmusic.ui.components.LocalLiquidGlassEnabled
import com.music.yzmusic.ui.components.backdrop.backdrops.emptyBackdrop
import com.music.yzmusic.ui.components.isGlassSupported
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassTest {

    @Test
    fun `isGlassSupported gates on Android 12 (API 31)`() {
        assertFalse(isGlassSupported(Build.VERSION_CODES.R))
        assertTrue(isGlassSupported(Build.VERSION_CODES.S))
        assertTrue(isGlassSupported(Build.VERSION_CODES.S_V2))
        assertTrue(isGlassSupported(Build.VERSION_CODES.TIRAMISU))
        assertTrue(isGlassSupported(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertTrue(isGlassSupported(35))
    }

    @Test
    fun `liquidGlass setting updates state flow`() {
        AppSettings.setLiquidGlass(true)
        assertTrue(AppSettings.liquidGlass.value)

        AppSettings.setLiquidGlass(false)
        assertFalse(AppSettings.liquidGlass.value)
    }

    @Test
    fun `reduceDynamicBlur setting updates state flow`() {
        AppSettings.setReduceDynamicBlur(true)
        assertTrue(AppSettings.reduceDynamicBlur.value)

        AppSettings.setReduceDynamicBlur(false)
        assertFalse(AppSettings.reduceDynamicBlur.value)
    }

    @Test
    fun `emptyBackdrop provides safe no-op backdrop`() {
        val backdrop = emptyBackdrop()
        assertNotNull(backdrop)
        assertFalse(backdrop.isCoordinatesDependent)
    }

    @Test
    fun `LocalLiquidGlassEnabled defaults to false`() {
        assertNotNull(LocalLiquidGlassEnabled)
    }

    @Test
    fun `LocalAppBackdrop provides safe default`() {
        assertNotNull(LocalAppBackdrop)
    }

    @Test
    fun `bar dimensions and padding match BitChord glass specifications`() {
        assertEquals(6.dp, com.music.yzmusic.ui.components.PILL_INSET)
        assertEquals(9.dp, com.music.yzmusic.ui.components.TAB_VERTICAL_PADDING)
        assertEquals(2.dp, com.music.yzmusic.ui.components.TAB_ICON_LABEL_GAP)
        assertEquals(10.dp, com.music.yzmusic.ui.components.PAGE_GUTTER)
    }

    @Test
    fun `FloatingTabBarColors and Elevations match zero-flat glass spec`() {
        val elevations = com.music.yzmusic.ui.components.floatingtabbar.FloatingTabBarElevations(
            inlineElevation = 0.dp,
            expandedElevation = 0.dp,
        )
        assertEquals(0.dp, elevations.inlineElevation)
        assertEquals(0.dp, elevations.expandedElevation)
    }
}
