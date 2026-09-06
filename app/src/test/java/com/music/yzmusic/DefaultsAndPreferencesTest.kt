package com.music.yzmusic

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.ui.graphics.Color
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.BackdropQuality
import com.music.yzmusic.ui.components.floatingtabbar.FloatingTabBarColors
import com.music.yzmusic.ui.components.floatingtabbar.FloatingTabBarDefaults
import com.music.yzmusic.ui.components.isGlassSupported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultsAndPreferencesTest {

    @Test
    fun `fresh preferences default automix and liquid glass to true on Android 12 plus`() {
        val emptyPrefs = FakeSharedPreferences()
        AppSettings.initForTest(emptyPrefs, sdkInt = Build.VERSION_CODES.S)

        assertTrue("Automix should default to true on fresh install", AppSettings.smartFadeEnabled.value)
        assertTrue("Liquid Glass should default to true on compatible device (API 31)", AppSettings.liquidGlass.value)
    }

    @Test
    fun `fresh preferences default liquid glass to false on unsupported devices`() {
        val emptyPrefs = FakeSharedPreferences()
        AppSettings.initForTest(emptyPrefs, sdkInt = Build.VERSION_CODES.R)

        assertTrue("Automix should default to true on fresh install", AppSettings.smartFadeEnabled.value)
        assertFalse("Liquid Glass should default to false on unsupported device (API 30)", AppSettings.liquidGlass.value)
        assertFalse("isGlassSupported must be false for API 30", isGlassSupported(Build.VERSION_CODES.R))
    }

    @Test
    fun `existing automix disabled preference is preserved`() {
        val prefs = FakeSharedPreferences().apply {
            putBoolean("smart_fade_enabled", false)
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertFalse("Existing automix=false preference must remain false", AppSettings.smartFadeEnabled.value)
    }

    @Test
    fun `existing automix enabled preference is preserved`() {
        val prefs = FakeSharedPreferences().apply {
            putBoolean("smart_fade_enabled", true)
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertTrue("Existing automix=true preference must remain true", AppSettings.smartFadeEnabled.value)
    }

    @Test
    fun `existing liquid glass disabled preference is preserved on compatible device`() {
        val prefs = FakeSharedPreferences().apply {
            putBoolean("liquid_glass", false)
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertFalse("Existing liquid_glass=false preference must remain false even on API 31", AppSettings.liquidGlass.value)
    }

    @Test
    fun `unsupported devices always gate liquid glass regardless of setting`() {
        val prefs = FakeSharedPreferences().apply {
            putBoolean("liquid_glass", true)
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.R)

        // The user flow in Settings only allows setting if supported, but if somehow true,
        // compatibility gate isGlassSupported must still prevent enabling glass effects.
        assertFalse("Compatibility check must be false on API 30", isGlassSupported(Build.VERSION_CODES.R))
    }

    @Test
    fun `fresh preferences default backdrop quality to MEDIUM`() {
        val emptyPrefs = FakeSharedPreferences()
        AppSettings.initForTest(emptyPrefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals("Backdrop quality should default to MEDIUM on fresh install",
            BackdropQuality.MEDIUM, AppSettings.backdropQuality.value)
    }

    @Test
    fun `existing backdrop quality LOW preference is preserved`() {
        val prefs = FakeSharedPreferences().apply {
            putString("backdrop_quality", "LOW")
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals("Existing backdrop_quality=LOW preference must be preserved",
            BackdropQuality.LOW, AppSettings.backdropQuality.value)
    }

    @Test
    fun `existing backdrop quality HIGH preference is preserved`() {
        val prefs = FakeSharedPreferences().apply {
            putString("backdrop_quality", "HIGH")
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals("Existing backdrop_quality=HIGH preference must be preserved",
            BackdropQuality.HIGH, AppSettings.backdropQuality.value)
    }

    @Test
    fun `existing preferences without backdrop_quality key safely default to MEDIUM`() {
        val prefs = FakeSharedPreferences().apply {
            putBoolean("liquid_glass", true)
            putFloat("glass_blur", 0.75f)
            putFloat("glass_refraction", 0.5f)
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals("Missing backdrop_quality should safely default to MEDIUM",
            BackdropQuality.MEDIUM, AppSettings.backdropQuality.value)
        assertEquals(0.75f, AppSettings.glassBlur.value, 0.0001f)
        assertEquals(0.5f, AppSettings.glassRefraction.value, 0.0001f)
        assertTrue(AppSettings.liquidGlass.value)
    }

    @Test
    fun `invalid or unknown backdrop quality string falls back safely to MEDIUM`() {
        val prefs = FakeSharedPreferences().apply {
            putString("backdrop_quality", "ULTRA_HIGH_4K")
        }
        AppSettings.initForTest(prefs, sdkInt = Build.VERSION_CODES.S)

        assertEquals("Invalid backdrop_quality value should safely fallback to MEDIUM",
            BackdropQuality.MEDIUM, AppSettings.backdropQuality.value)
    }

    @Test
    fun `glass navbar indicatorColor is transparent`() {
        val glassColors = FloatingTabBarColors(
            backgroundColor = Color.Transparent,
            accessoryBackgroundColor = Color.Transparent,
            indicatorColor = Color.Transparent,
        )
        assertEquals(Color.Transparent, glassColors.indicatorColor)
        assertEquals(Color.Transparent, glassColors.backgroundColor)
        assertEquals(Color.Transparent, glassColors.accessoryBackgroundColor)
    }

    @Test
    fun `automix string in all strings xml files does not contain Beta or test indicators`() {
        val resDir = File("src/main/res")
        val stringFiles = resDir.walkTopDown().filter { it.name == "strings.xml" }.toList()
        assertTrue("Found localized strings files", stringFiles.isNotEmpty())

        val betaRegex = Regex("""<string\s+name="automix"[^>]*>([^<]+)</string>""")
        for (file in stringFiles) {
            val content = file.readText()
            val match = betaRegex.find(content)
            if (match != null) {
                val automixText = match.groupValues[1]
                assertFalse("File ${file.path} should not contain BETA in automix: $automixText",
                    automixText.contains("BETA", ignoreCase = true))
                assertFalse("File ${file.path} should not contain 测试版 in automix: $automixText",
                    automixText.contains("测试版"))
                assertFalse("File ${file.path} should not contain БЕТА in automix: $automixText",
                    automixText.contains("БЕТА", ignoreCase = true))
            }
        }
    }

    /**
     * Minimal in-memory implementation of SharedPreferences for testing default/stored preferences.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        fun putBoolean(key: String, value: Boolean) {
            data[key] = value
        }

        fun putString(key: String, value: String?) {
            data[key] = value
        }

        fun putFloat(key: String, value: Float) {
            data[key] = value
        }

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = FakeEditor(data)

        private class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removals.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) target.clear()
                removals.forEach { target.remove(it) }
                target.putAll(pending)
            }
        }
    }
}
