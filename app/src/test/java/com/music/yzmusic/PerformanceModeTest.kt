package com.music.yzmusic

import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.AutomixPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceModeTest {

    @Test
    fun `automix performance mode maps to correct thread allocations`() {
        assertEquals(1, AutomixPerformanceMode.EFFICIENT.threads)
        assertEquals(2, AutomixPerformanceMode.BALANCED.threads)
        assertEquals(4, AutomixPerformanceMode.PERFORMANCE.threads)
    }

    @Test
    fun `high performance mode updates state flow`() {
        AppSettings.setHighPerformance(true)
        assertTrue(AppSettings.highPerformance.value)

        AppSettings.setHighPerformance(false)
        assertFalse(AppSettings.highPerformance.value)
    }

    @Test
    fun `allow dolby atmos updates state flow`() {
        AppSettings.setAllowDolbyAtmos(true)
        assertTrue(AppSettings.allowDolbyAtmos.value)

        AppSettings.setAllowDolbyAtmos(false)
        assertFalse(AppSettings.allowDolbyAtmos.value)
    }

    @Test
    fun `automix performance mode updates state flow`() {
        AppSettings.setAutomixPerformance(AutomixPerformanceMode.EFFICIENT)
        assertEquals(AutomixPerformanceMode.EFFICIENT, AppSettings.automixPerformance.value)

        AppSettings.setAutomixPerformance(AutomixPerformanceMode.PERFORMANCE)
        assertEquals(AutomixPerformanceMode.PERFORMANCE, AppSettings.automixPerformance.value)

        AppSettings.setAutomixPerformance(AutomixPerformanceMode.BALANCED)
        assertEquals(AutomixPerformanceMode.BALANCED, AppSettings.automixPerformance.value)
    }
}
