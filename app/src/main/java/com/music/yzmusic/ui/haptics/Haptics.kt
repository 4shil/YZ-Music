package com.music.yzmusic.ui.haptics

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a touch *meant*, not what it should feel like — the shape of the buzz is
 * this file's business, so a screen never has to know what the motor under it
 * can do.
 *
 * Everything here is deliberately brief. The longest pattern is a three-beat
 * one under 65ms; a haptic that outlasts the finger stops reading as a response
 * to the tap and starts reading as the phone ringing.
 */
enum class Haptic {
    /**
     * The lightest single beat, for something that repeats while a finger is
     * still down — a drag crossing a tab boundary, say. Anything firmer becomes
     * a rattle once it fires ten times in a row.
     */
    Tick,

    /** A plain button press with no state behind it: More, Download, Menu. */
    Tap,

    /** A discrete choice landing: a tab, a filter pill, the end of a scrub. */
    Select,

    /** Switching something on — a light lead-in *rising* into a firm beat. */
    ToggleOn,

    /** Switching it back off — the same pair mirrored, so it falls away. */
    ToggleOff,

    /** Forward through the queue: an accelerating triplet. */
    SkipNext,

    /** Backward: [SkipNext] reversed, which is what makes the pair legible. */
    SkipPrevious,

    /** Playback starting — swells into the beat that lands. */
    Resume,

    /** Playback stopping — lands first, then releases. */
    Pause,

    /** Something growing to fill the screen, e.g. the mini player opening. */
    Expand,
}

/**
 * A handle on the device's motor, obtained with [rememberHaptics].
 *
 * Cheap to hold and cheap to call: the capability probe and the compiled
 * [VibrationEffect]s live in [HapticDevice], one set for the whole process.
 */
class Haptics internal constructor(context: Context) {
    private val app = context.applicationContext

    fun play(haptic: Haptic) {
        HapticDevice.of(app)?.play(haptic)
    }
}

/** `val haptics = rememberHaptics()`, then `haptics.play(Haptic.Select)`. */
@Composable
