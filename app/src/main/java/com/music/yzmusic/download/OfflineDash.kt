package com.music.yzmusic.download

import android.content.Context
import android.net.Uri
import com.music.yzmusic.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max

/**
 * Downloads an MPEG-DASH audio stream and packages it as an offline HLS/fMP4 directory
 * with a local playlist.m3u8, allowing ExoPlayer to play it offline without format issues.
 */
internal object OfflineDash {

    fun handles(url: String): Boolean =
        url.substringBefore('?').endsWith(".mpd", ignoreCase = true)

    suspend fun save(
        context: Context,
        id: String,
        url: String,
        headers: Map<String, String>,
        lyrics: LyricsTag.Embeddable? = null,
        coverBytes: ByteArray? = null,
        onProgress: (Long, Long) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val root = File(File(context.filesDir, "offline-hls"), id.hashCode().toUInt().toString(16))
        root.deleteRecursively()
        if (!root.mkdirs()) error("Could not create offline package")
        try {
            fun request(target: String) = Request.Builder().url(target).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()

            val manifest = Http.client.newCall(request(url)).execute().use { response ->
                if (!response.isSuccessful) error("DASH manifest failed (HTTP ${response.code})")
                response.body?.string() ?: error("Empty DASH manifest")
            }
            val plan = parse(manifest)
            val base = url.toHttpUrlOrNull() ?: error("Invalid DASH URL")

            val remotes = listOf(plan.initialization) + plan.media
            remotes.forEachIndexed { index, remote ->
                coroutineContext.ensureActive()
                val target = base.resolve(remote)?.toString() ?: error("Invalid DASH segment")
                Http.client.newCall(request(target)).execute().use { response ->
                    if (!response.isSuccessful) error("DASH segment failed (HTTP ${response.code})")
                    val body = response.body?.byteStream() ?: error("Empty DASH segment")
                    body.use { input -> File(root, localName(index)).outputStream().use(input::copyTo) }
                }
                onProgress((index + 1).toLong(), remotes.size.toLong())
            }

            File(root, "playlist.m3u8").writeText(playlist(plan))
            lyrics?.let { File(root, "lyrics.lrc").writeText(it.enhanced ?: it.plain.orEmpty()) }
            coverBytes?.let { File(root, "cover.jpg").writeBytes(it) }
            Uri.fromFile(File(root, "playlist.m3u8"))
        } catch (e: Throwable) {
            root.deleteRecursively()
            throw e
        }
    }

    private fun localName(index: Int) = "segment-${index.toString().padStart(5, '0')}.m4s"

    internal class Plan(
        val initialization: String,
        val media: List<String>,
        val seconds: List<Double>,
    )

    internal fun parse(manifest: String): Plan {
        if (Regex("<ContentProtection", RegexOption.IGNORE_CASE).containsMatchIn(manifest)) {
            error("Encrypted DASH cannot be saved")
        }
        if (Regex("<Period[\\s>]").findAll(manifest).count() > 1) {
            error("Multi-period DASH cannot be saved")
        }
        val template = Regex("<SegmentTemplate([^>]*)>", RegexOption.IGNORE_CASE).find(manifest)?.groupValues?.get(1)
            ?: error("DASH manifest has no segment template")
        fun attr(name: String) =
            Regex("""\b$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(template)?.groupValues?.get(1)

        val initialization = attr("initialization")?.let(::unescape)
            ?: error("DASH manifest has no initialization segment")
        val mediaTemplate = attr("media")?.let(::unescape)
            ?: error("DASH manifest has no media template")

        if (!mediaTemplate.contains("\$Number\$")) error("Unsupported DASH media template")
        val timescale = attr("timescale")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val startNumber = attr("startNumber")?.toIntOrNull() ?: 1

        val ticks = mutableListOf<Long>()
        Regex("<SegmentTimeline>(.*?)</SegmentTimeline>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(manifest)?.groupValues?.get(1)
            ?.let { timeline ->
                Regex("<S\\b([^>]*)/?>", RegexOption.IGNORE_CASE).findAll(timeline).forEach { entry ->
                    val body = entry.groupValues[1]
                    fun of(name: String) =
                        Regex("""\b$name\s*=\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
                    val d = of("d")?.toLongOrNull() ?: return@forEach
                    repeat((of("r")?.toIntOrNull() ?: 0) + 1) { ticks += d }
                }
            }
        if (ticks.isEmpty()) {
            val d = attr("duration")?.toLongOrNull()?.takeIf { it > 0 }
                ?: error("DASH manifest has neither a timeline nor a segment duration")
            val total = Regex("""mediaPresentationDuration\s*=\s*"([^"]*)"""").find(manifest)
                ?.groupValues?.get(1)?.let(::isoSeconds)
                ?: error("DASH manifest states no duration")
            repeat(max(1, ceil(total / (d / timescale)).toInt())) { ticks += d }
        }

        val media = ticks.indices.map { at ->
            mediaTemplate.replace("\$Number\$", (startNumber + at).toString())
        }
        if (media.isEmpty()) error("DASH manifest has no segments")
        return Plan(initialization, media, ticks.map { it / timescale })
    }

    internal fun playlist(plan: Plan): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-TARGETDURATION:${ceil(plan.seconds.maxOrNull() ?: 0.0).toInt()}")
        appendLine("""#EXT-X-MAP:URI="${localName(0)}"""")
        plan.seconds.forEachIndexed { at, seconds ->
            appendLine("#EXTINF:${"%.3f".format(Locale.ROOT, seconds)},")
            appendLine(localName(at + 1))
        }
        appendLine("#EXT-X-ENDLIST")
    }

    private fun unescape(value: String) = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun isoSeconds(value: String): Double? {
        val match = Regex("""PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""")
            .find(value) ?: return null
        val (h, m, s) = match.destructured
        val seconds = (h.toDoubleOrNull() ?: 0.0) * 3600 +
            (m.toDoubleOrNull() ?: 0.0) * 60 +
            (s.toDoubleOrNull() ?: 0.0)
        return seconds.takeIf { it > 0 }
    }
}
