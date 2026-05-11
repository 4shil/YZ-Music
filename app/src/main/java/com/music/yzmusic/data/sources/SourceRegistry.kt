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
            val existingModule = seeded.firstOrNull { it.kind == SourceKind.MODULE }
            if (existingModule == null) {
                seeded + SourceConfig(
                    kind = SourceKind.MODULE,
                    label = ENV_MODULE_LABEL,
                    baseUrl = envUrl,
                    enabled = true,
                )
            } else if (existingModule.baseUrl != envUrl || existingModule.label.isBlank()) {
                // The label is filled in as well as the URL, so the env-managed
                // module is named rather than showing the bare index host —
                // which is what [SourceConfig.displayName] falls back to.
                seeded.map {
                    if (it.kind == SourceKind.MODULE) {
                        it.copy(baseUrl = envUrl, label = it.label.ifBlank { ENV_MODULE_LABEL })
                    } else {
                        it
                    }
                }
            } else {
                seeded
            }
        } else {
            // No env URL: keep whatever the user had stored, but ensure there
            // is no leftover env-managed module config lying around from a
            // previous build that did have one.
            seeded
        }

        // YouTube is not switchable — see [setEnabled] — so a config persisted
        // as disabled by an earlier build would strand the app with no source
        // it is allowed to turn back on.
        val after = withModule.map {
            if (it.kind == SourceKind.YOUTUBE && !it.enabled) it.copy(enabled = true) else it
        }

        publish(after, persist = after != stored)
    }

    /**
     * Decodes a stored source list one entry at a time rather than as a
     * single list, so one entry naming a kind this build no longer has —
     * left over from before a kind was retired — doesn't take every other
     * entry down with it. A strict `List<SourceConfig>` decode fails whole:
     * one bad enum value and the user's real, working module config is
     * silently gone along with it.
     */
    private fun decodeStored(raw: String): List<SourceConfig> {
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { return emptyList() }
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "dropping unreadable stored source: ${it.message}") }
                .getOrNull()
        }
    }

    /** The enabled sources, module first and YouTube last, however they're stored. */
    fun active(): List<MusicSource> =
        configs.value
            .filter { it.enabled && it.isComplete }
            .sortedBy { it.kind.ordinal }
            .mapNotNull { instances[it.id] }

    fun instance(configId: String): MusicSource? = instances[configId]

    fun config(configId: String): SourceConfig? = configs.value.firstOrNull { it.id == configId }

    // ── Editing ─────────────────────────────────────────────────────────

    fun add(config: SourceConfig) = publish(configs.value + config)

    fun update(config: SourceConfig) =
        publish(configs.value.map { if (it.id == config.id) config else it })

    fun remove(configId: String) {
        val target = config(configId) ?: return
        if (target.kind in BUILT_IN_KINDS) return
        publish(configs.value.filterNot { it.id == configId })
    }

    /**
     * Turns one source on or off.
     *
     * YouTube is not switchable and silently ignores a request to disable it.
     * It is the only source that can supply a home feed, radio or related
     * tracks, and nothing else holds the full catalogue — switching it off
     * doesn't even stop it being played, because a YouTube-queued track whose
     * substitutes all miss still falls back to it. A switch that cannot honour
     * its own off position is worse than no switch, so it isn't offered one:
     * see [SourcesScreen][com.music.yzmusic.ui.screens.SourcesScreen].
     */
    fun setEnabled(configId: String, enabled: Boolean) {
        if (!enabled && config(configId)?.kind == SourceKind.YOUTUBE) return
        publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
    }

    /** Toggle the MODULE source on or off by its config id. */
    fun setModuleEnabled(enabled: Boolean) {
        val module = configs.value.firstOrNull { it.kind == SourceKind.MODULE } ?: return
        setEnabled(module.id, enabled)
    }

    /** The user's own module index, if they have set one. */
