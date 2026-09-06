package com.music.yzmusic.data.sources

import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.music.yzmusic.data.DebugLog as Log
import java.util.Locale

/**
 * Probes device hardware decoding capabilities once at startup.
 *
 * Checks specifically for Dolby Atmos (E-AC-3 JOC) and plain E-AC-3 support.
 */
object DeviceCodecs {

    private const val TAG = "YZMusic"

    private val DOLBY_MIMES = listOf("audio/eac3-joc", "audio/eac3")

    @Volatile
    private var probed: Boolean? = null

    @Volatile
    internal var forced: Boolean? = null

    /**
     * Whether an E-AC-3 (JOC) stream has a hardware/platform decoder on this device.
     */
    val playsDolbyAtmos: Boolean
        get() = forced ?: probed ?: probe().also { probed = it }

    private fun probe(): Boolean {
        val viaMedia3 = media3Decoders()
        if (viaMedia3 != null) {
            Log.d(TAG, "Dolby Atmos decoders (Media3): ${viaMedia3.ifEmpty { "none" }}")
            return viaMedia3.isNotEmpty()
        }
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.lowercase(Locale.ROOT) in DOLBY_MIMES
                }
            }.map { it.name }
        }.onSuccess {
            Log.d(TAG, "Dolby Atmos decoders (platform): ${it.ifEmpty { "none" }}")
        }.map {
            it.isNotEmpty()
        }.getOrElse {
            Log.w(TAG, "Could not read codec list; assuming Dolby Atmos plays: ${it.message}")
            true
        }
    }

    @OptIn(UnstableApi::class)
    private fun media3Decoders(): List<String>? = runCatching {
        DOLBY_MIMES.flatMap { MediaCodecUtil.getDecoderInfos(it, false, false) }
            .map { it.name }
            .distinct()
    }.getOrElse {
        Log.w(TAG, "Media3 could not enumerate Dolby decoders: ${it.message}")
        null
    }
}
