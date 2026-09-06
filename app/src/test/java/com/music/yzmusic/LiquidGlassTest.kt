package com.music.yzmusic

import android.os.Build
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.BackdropQuality
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

    @Test
    fun `glassBlur setting defaults to DEFAULT_GLASS_BLUR`() {
        AppSettings.resetGlassBlur()
        assertEquals(AppSettings.DEFAULT_GLASS_BLUR, AppSettings.glassBlur.value, 0.0001f)
        assertEquals(0.6f, AppSettings.DEFAULT_GLASS_BLUR, 0.0001f)
    }

    @Test
    fun `glassBlur setting updates state flow and clamps to 0 to 1 range`() {
        AppSettings.setGlassBlur(0.2f)
        assertEquals(0.2f, AppSettings.glassBlur.value, 0.0001f)

        AppSettings.setGlassBlur(1.0f)
        assertEquals(1.0f, AppSettings.glassBlur.value, 0.0001f)

        // Lower clamp
        AppSettings.setGlassBlur(-0.5f)
        assertEquals(0.0f, AppSettings.glassBlur.value, 0.0001f)

        // Upper clamp
        AppSettings.setGlassBlur(1.5f)
        assertEquals(1.0f, AppSettings.glassBlur.value, 0.0001f)
    }

    @Test
    fun `resetGlassBlur restores default blur value`() {
        AppSettings.setGlassBlur(0.85f)
        assertEquals(0.85f, AppSettings.glassBlur.value, 0.0001f)

        AppSettings.resetGlassBlur()
        assertEquals(AppSettings.DEFAULT_GLASS_BLUR, AppSettings.glassBlur.value, 0.0001f)
    }

    @Test
    fun `calculateGlassBlurRadiusDp preserves baseline 8dp at DEFAULT_GLASS_BLUR`() {
        val radiusAtDefault = com.music.yzmusic.ui.components.calculateGlassBlurRadiusDp(AppSettings.DEFAULT_GLASS_BLUR)
        assertEquals(com.music.yzmusic.ui.components.BLUR_RADIUS_DP, radiusAtDefault, 0.0001f)
        assertEquals(8.0f, radiusAtDefault, 0.0001f)
    }

    @Test
    fun `calculateGlassBlurRadiusDp scales correctly at min and max`() {
        val radiusAtMin = com.music.yzmusic.ui.components.calculateGlassBlurRadiusDp(0.0f)
        assertEquals(0.0f, radiusAtMin, 0.0001f)

        val radiusAtMax = com.music.yzmusic.ui.components.calculateGlassBlurRadiusDp(1.0f)
        val expectedMax = (1.0f / 0.6f) * 8.0f
        assertEquals(expectedMax, radiusAtMax, 0.0001f)
    }

    @Test
    fun `glassRefraction defaults to DEFAULT_GLASS_REFRACTION (1_0f)`() {
        AppSettings.resetGlassRefraction()
        assertEquals(AppSettings.DEFAULT_GLASS_REFRACTION, AppSettings.glassRefraction.value, 0.0001f)
        assertEquals(1.0f, AppSettings.DEFAULT_GLASS_REFRACTION, 0.0001f)
    }

    @Test
    fun `glassRefraction setting updates state flow and clamps to 0 to 1 range`() {
        AppSettings.setGlassRefraction(0.5f)
        assertEquals(0.5f, AppSettings.glassRefraction.value, 0.0001f)

        AppSettings.setGlassRefraction(0.0f)
        assertEquals(0.0f, AppSettings.glassRefraction.value, 0.0001f)

        // Lower clamp
        AppSettings.setGlassRefraction(-0.2f)
        assertEquals(0.0f, AppSettings.glassRefraction.value, 0.0001f)

        // Upper clamp
        AppSettings.setGlassRefraction(1.8f)
        assertEquals(1.0f, AppSettings.glassRefraction.value, 0.0001f)
    }

    @Test
    fun `resetGlassRefraction restores default refraction value`() {
        AppSettings.setGlassRefraction(0.3f)
        assertEquals(0.3f, AppSettings.glassRefraction.value, 0.0001f)

        AppSettings.resetGlassRefraction()
        assertEquals(AppSettings.DEFAULT_GLASS_REFRACTION, AppSettings.glassRefraction.value, 0.0001f)
    }

    @Test
    fun `glassBlur and glassRefraction mutate independently`() {
        AppSettings.resetGlassBlur()
        AppSettings.resetGlassRefraction()

        AppSettings.setGlassBlur(0.25f)
        assertEquals(0.25f, AppSettings.glassBlur.value, 0.0001f)
        assertEquals(1.0f, AppSettings.glassRefraction.value, 0.0001f)

        AppSettings.setGlassRefraction(0.4f)
        assertEquals(0.25f, AppSettings.glassBlur.value, 0.0001f)
        assertEquals(0.4f, AppSettings.glassRefraction.value, 0.0001f)
    }

    @Test
    fun `calculateGlassLensHeightPx and AmountPx scale linearly with glassRefraction`() {
        val density = androidx.compose.ui.unit.Density(density = 2f, fontScale = 1f)

        // 0% Refraction -> exactly 0px (no distortion)
        val h0 = com.music.yzmusic.ui.components.calculateGlassLensHeightPx(0.0f, density)
        val a0 = com.music.yzmusic.ui.components.calculateGlassLensAmountPx(0.0f, density)
        assertEquals(0.0f, h0, 0.0001f)
        assertEquals(0.0f, a0, 0.0001f)

        // 100% Refraction -> full baseline
        val h100 = com.music.yzmusic.ui.components.calculateGlassLensHeightPx(1.0f, density)
        val a100 = com.music.yzmusic.ui.components.calculateGlassLensAmountPx(1.0f, density)
        assertTrue(h100 > 0f)
        assertTrue(a100 > 0f)

        // 50% Refraction -> exactly half
        val h50 = com.music.yzmusic.ui.components.calculateGlassLensHeightPx(0.5f, density)
        val a50 = com.music.yzmusic.ui.components.calculateGlassLensAmountPx(0.5f, density)
        assertEquals(h100 * 0.5f, h50, 0.0001f)
        assertEquals(a100 * 0.5f, a50, 0.0001f)
    }

    @Test
    fun `BackdropQuality enum has correct scale values and names`() {
        assertEquals(0.33f, BackdropQuality.LOW.scale, 0.0001f)
        assertEquals(0.5f, BackdropQuality.MEDIUM.scale, 0.0001f)
        assertEquals(1.0f, BackdropQuality.HIGH.scale, 0.0001f)
        assertEquals(3, BackdropQuality.entries.size)
    }

    @Test
    fun `backdropQuality setting defaults to DEFAULT_BACKDROP_QUALITY (MEDIUM)`() {
        AppSettings.resetBackdropQuality()
        assertEquals(AppSettings.DEFAULT_BACKDROP_QUALITY, AppSettings.backdropQuality.value)
        assertEquals(BackdropQuality.MEDIUM, AppSettings.DEFAULT_BACKDROP_QUALITY)
    }

    @Test
    fun `backdropQuality setting updates state flow`() {
        AppSettings.setBackdropQuality(BackdropQuality.LOW)
        assertEquals(BackdropQuality.LOW, AppSettings.backdropQuality.value)

        AppSettings.setBackdropQuality(BackdropQuality.HIGH)
        assertEquals(BackdropQuality.HIGH, AppSettings.backdropQuality.value)

        AppSettings.setBackdropQuality(BackdropQuality.MEDIUM)
        assertEquals(BackdropQuality.MEDIUM, AppSettings.backdropQuality.value)
    }

    @Test
    fun `resetBackdropQuality restores default MEDIUM`() {
        AppSettings.setBackdropQuality(BackdropQuality.HIGH)
        assertEquals(BackdropQuality.HIGH, AppSettings.backdropQuality.value)

        AppSettings.resetBackdropQuality()
        assertEquals(BackdropQuality.MEDIUM, AppSettings.backdropQuality.value)
    }

    @Test
    fun `glassBlur glassRefraction and backdropQuality mutate independently`() {
        AppSettings.resetGlassBlur()
        AppSettings.resetGlassRefraction()
        AppSettings.resetBackdropQuality()

        AppSettings.setGlassBlur(0.4f)
        AppSettings.setGlassRefraction(0.7f)
        AppSettings.setBackdropQuality(BackdropQuality.HIGH)

        assertEquals(0.4f, AppSettings.glassBlur.value, 0.0001f)
        assertEquals(0.7f, AppSettings.glassRefraction.value, 0.0001f)
        assertEquals(BackdropQuality.HIGH, AppSettings.backdropQuality.value)

        AppSettings.resetGlassBlur()
        assertEquals(AppSettings.DEFAULT_GLASS_BLUR, AppSettings.glassBlur.value, 0.0001f)
        assertEquals(0.7f, AppSettings.glassRefraction.value, 0.0001f)
        assertEquals(BackdropQuality.HIGH, AppSettings.backdropQuality.value)
    }

    @Test
    fun `calculateGlassLensHeightPx and AmountPx scale with custom scale parameter`() {
        val density = androidx.compose.ui.unit.Density(density = 2f, fontScale = 1f)

        val hDefault = com.music.yzmusic.ui.components.calculateGlassLensHeightPx(1.0f, density, scale = 0.33f)
        val hMedium = com.music.yzmusic.ui.components.calculateGlassLensHeightPx(1.0f, density, scale = 0.5f)
        val hHigh = com.music.yzmusic.ui.components.calculateGlassLensHeightPx(1.0f, density, scale = 1.0f)

        assertTrue(hDefault > 0f)
        assertTrue(hMedium > hDefault)
        assertTrue(hHigh > hMedium)

        assertEquals(hHigh * 0.5f, hMedium, 0.0001f)
        assertEquals(hHigh * 0.33f, hDefault, 0.0001f)

        val aDefault = com.music.yzmusic.ui.components.calculateGlassLensAmountPx(1.0f, density, scale = 0.33f)
        val aMedium = com.music.yzmusic.ui.components.calculateGlassLensAmountPx(1.0f, density, scale = 0.5f)
        val aHigh = com.music.yzmusic.ui.components.calculateGlassLensAmountPx(1.0f, density, scale = 1.0f)

        assertTrue(aDefault > 0f)
        assertTrue(aMedium > aDefault)
        assertTrue(aHigh > aMedium)

        assertEquals(aHigh * 0.5f, aMedium, 0.0001f)
        assertEquals(aHigh * 0.33f, aDefault, 0.0001f)
    }
}
