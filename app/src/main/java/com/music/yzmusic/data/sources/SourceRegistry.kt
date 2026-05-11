package com.music.yzmusic.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.music.yzmusic.BuildConfig
import com.music.yzmusic.data.TrackLog
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.UUID

/**
 * One configured source: which protocol, which index.
 *
 * Stored in encrypted prefs — [baseUrl] is a module index the user called
 * "for my private use", not something to leave sitting in plain-text
 * SharedPreferences on a device someone else might get into.
 */
@Serializable
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: SourceKind,
    /** What the user called it. Blank falls back to the server's host, or the kind's own label. */
    val label: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = true,
) {
    /** What the sources screen and the player show. Never blank. */
    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?: kind.label
        }

    /** Whether this has enough filled in to be worth contacting at all. */
    val isComplete: Boolean
        get() = !kind.needsServer || baseUrl.isNotBlank()
}

/**
 * The user's sources, always tried in a fixed order: the module source
 * first, YouTube Music second.
 *
 * [SourceKind.YOUTUBE] is seeded on first run and cannot be deleted, only
 * disabled — it needs no configuration, so a "remove" would delete something
 * the user could not then re-create by typing anything in, it would just be a
 * switch that hides itself. The module source is entirely optional: with none
 * configured, YouTube is all there is.
 */
object SourceRegistry {

    private const val TAG = "YZ Music"

    private lateinit var prefs: SharedPreferences

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Every configured source, enabled or not. */
    val configs = MutableStateFlow<List<SourceConfig>>(emptyList())

    /**
     * Built instances, keyed by config id, rebuilt whenever [configs] changes.
     *
     * Held rather than constructed per call so that a source with any warmed
     * state — a module whose index has already been fetched — keeps it across
     * tracks instead of re-probing on every resolve.
     */
    private var instances: Map<String, MusicSource> = emptyMap()

    fun init(context: Context) {
        prefs = runCatching {
            EncryptedSharedPreferences.create(
                context,
                "yzmusic_sources",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // Same degradation as AuthStore: a handful of OEM builds cannot
            // init the keystore, and refusing to run at all is worse than
            // storing this the way every other setting in the app is stored.
            TrackLog.w(TAG, "EncryptedSharedPreferences unavailable for sources: ${it.message}")
            context.getSharedPreferences("yzmusic_sources_plain", Context.MODE_PRIVATE)
        }

        val stored = prefs.getString(KEY_SOURCES, null)?.let(::decodeStored) ?: emptyList()

        // Seeded rather than persisted-on-first-write, so that a build that
        // adds a new built-in kind picks it up for existing installs too.
        val seeded = stored + BUILT_IN_KINDS
            .filter { kind -> stored.none { it.kind == kind } }
            .map { SourceConfig(kind = it, enabled = true) }

        // If a module index URL was baked in at build time, ensure it is the
        // one stored — add the module source if missing, or silently update its
        // URL if it changed. The toggle’s enabled state is always preserved so
        // the user’s on/off choice survives an app update.
        val envUrl = BuildConfig.MODULE_INDEX_URL.trim()
        val withModule = if (envUrl.isNotEmpty()) {
