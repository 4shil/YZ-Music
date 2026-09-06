package com.music.yzmusic.playback

import com.music.yzmusic.data.settings.OutputPcmMode

/**
 * Audio output policy for PCM float mode and DAC routing safety.
 */
object AudioOutputPolicy {

    /**
     * Decides whether Media3 may configure a PCM-float AudioTrack.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32 &&
        isPreferredUsbRoute &&
        advertisesPcmFloat

    /**
     * Detects buggy vendor FLAC decoders (e.g. Samsung c2.sec.flac.decoder) that
     * emit invalid timestamps when combined with PCM float output.
     */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }

    /**
     * Whether [format] indicates an immersive Dolby Atmos stream.
     */
    fun isAtmosFormat(format: com.music.yzmusic.data.sources.StreamFormat?): Boolean {
        if (format == null) return false
        return format.isAtmos || format.isDolbyAtmos
    }

    /**
     * Inspects stream URL or mime type to identify Atmos / E-AC-3 streams.
     */
    fun isAtmosStream(url: String?, mimeType: String?): Boolean {
        val mime = mimeType?.lowercase()
        if (mime == "audio/eac3-joc" || mime == "audio/eac3") return true
        val u = url?.lowercase() ?: return false
        return u.contains("atmos") || u.contains("eac3-joc")
    }

    /**
     * Centralized policy arbitration:
     * When Dolby Atmos is active, binaural virtualizer DSP is bypassed to let Atmos native
     * spatialization render directly through hardware/spatial decoders.
     */
    fun shouldEnableSpatialAudio(userSpatialAudioEnabled: Boolean, isAtmosActive: Boolean): Boolean =
        userSpatialAudioEnabled && !isAtmosActive
}
