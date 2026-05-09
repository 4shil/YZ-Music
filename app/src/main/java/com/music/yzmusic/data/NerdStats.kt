package com.music.yzmusic.data

import com.music.yzmusic.data.sources.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * What the audio decoder is actually being fed, for "stats for nerds".
 *
 * Every figure here is measured rather than inferred. Codec, sample rate and
 * channel count come from the `Format` the audio renderer was configured with —
 * the decoder's own view of the stream. Bitrate is the one a container usually
 * withholds, so it falls back to the bitrate of the stream the resolver
 * genuinely chose for that track. Anything the player hasn't reported stays
 * null and is left out of the display instead of being guessed at.
 *
 * [claimed] is the one figure here that is *not* measured, and is kept apart
 * from the rest for that reason: it is what a source said it was about to send.
 * Holding both is the point — a source promising 24-bit/192kHz while the
 * decoder reports 16-bit/48kHz is the single most likely way for a lossless
 * setting to be quietly doing nothing, and it is invisible unless the two
 * numbers are put side by side. See [downgraded].
 */
object NerdStats {

    class Snapshot(
        val mimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        /** From the decoder's PCM encoding, where it states one. */
        val bitDepth: Int? = null,
        /** What the source said it would serve, when it came from one that says. */
        val claimed: StreamFormat? = null,
    ) {
        /**
         * Whether what arrived is measurably worse than what was promised.
         *
         * Only ever true when both figures are known — an absent measurement is
         * not evidence of a downgrade, and reporting one on that basis would
         * make the warning worthless the moment it fired on a container that
         * simply doesn't state its rate.
         */
        val downgraded: Boolean
            get() {
                val wantedRate = claimed?.sampleRateHz
                val wantedDepth = claimed?.bitDepth
                return (wantedRate != null && sampleRateHz != null && sampleRateHz < wantedRate) ||
                    (wantedDepth != null && bitDepth != null && bitDepth < wantedDepth)
            }

        /**
         * Whether the decoder is genuinely being fed a lossless codec — the
         * figure the Now Playing screen's "Lossless" badge is gated on, not
         * just what a source promised. [claimed] alone would let a source
         * that said "FLAC" and quietly served Opus still light the badge.
         *
         * Which is exactly what it did, because this was written as
         * `claimed?.isLossless == true || …` — the claim on its own, the very
         * thing the paragraph above says it must not be. Observed: an upgrade
         * to a Tidal FLAC was served, recorded as the declared format, and
         * then died on `ERROR_CODE_IO_BAD_HTTP_STATUS`; playback recovered
         * onto YouTube's Opus and the badge went on reading "Lossless" over
         * it, because the claim outlived the stream that made it.
         *
         * So the decoder gets the last word whenever it has said anything.
         * The claim is only consulted before the renderer has been
         * configured — the gap between a source answering and the first audio
         * frame — where it is the only evidence there is, and where a wrong
         * answer lasts a second rather than a song.
         */
        val isLossless: Boolean
            get() = when {
                mimeType != null -> isLosslessMime(mimeType)
                else -> claimed?.isLossless == true
            }

        /**
         * Whether this is better than CD quality — the line Tidal, Qobuz and
         * Apple Music all draw it at: past 16-bit or past 48kHz, not merely
         * lossless. A 16-bit/44.1kHz FLAC is a bit-exact CD rip and gets
         * called "Lossless"; a 24-bit/96kHz one is "Hi-Res Lossless", because
         * calling both the same thing would flatten a distinction the
         * listener can plausibly hear.
         */
        val isHiRes: Boolean
            get() = isLossless && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)

        /**
         * Whether this is lossy, but at the top of what lossy gets — a 320kbps
         * AAC or MP3 from a module's HIGH tier, rather than YouTube's 160kbps
         * Opus.
         *
         * Worth naming on screen because it is the honest answer often enough
         * to matter: plenty of catalogues simply have no lossless copy of a
         * track, and a badge with only two states — "Lossless" or nothing —
         * makes a good stream and a mediocre one look identical. Not called
         * lossless anywhere, because it isn't.
         *
         * Decided on the bitrate rather than on which source served it: a
         * 256kbps stream is a 256kbps stream wherever it came from.
         */
