package com.music.yzmusic.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.yzmusic.data.model.SearchHistoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where the recent searches live, as the search screen sees them.
 *
 * The storage behind it is a process-wide object ([SearchHistory]) because that
 * is what the application and the settings backup reach, but nothing else has
 * to know that: this is the whole of the contract the search state works
 * against, and it is small enough to stand in for.
 */
interface SearchHistoryStore {
    val recent: StateFlow<List<SearchHistoryEntity>>
    fun record(entry: SearchHistoryEntity)
    fun remove(identity: String)
    fun clear()
}

/**
 * Recent searches, kept on the device.
 *
 * Nothing here reaches the network: a recent is a row of the user's own
 * history, and it has to still be there — and still openable — on a train with
 * no signal. That is the reason an entry keeps the id and metadata of what was
 * picked rather than only the text that found it, and the reason a track or a
 * page it names can be reopened from what is stored.
 */
object SearchHistory : SearchHistoryStore {
    private lateinit var prefs: SharedPreferences
    private val _recent = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
    override val recent: StateFlow<List<SearchHistoryEntity>> = _recent.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("yzmusic_settings", Context.MODE_PRIVATE)
        reload()
    }

    /** Re-reads the stored list, after an import or an external change. */
    fun reload() {
        if (!this::prefs.isInitialized) return
        _recent.value = SearchHistoryList.decode(prefs.getString(KEY_HISTORY, null))
    }

    override fun record(entry: SearchHistoryEntity) {
        if (!entry.isUsable) return
        save(SearchHistoryList.record(_recent.value, entry))
    }

    override fun remove(identity: String) {
        save(SearchHistoryList.remove(_recent.value, identity))
    }

    override fun clear() = save(emptyList())

    private fun save(value: List<SearchHistoryEntity>) {
        _recent.value = value
        if (this::prefs.isInitialized) {
            prefs.edit().putString(KEY_HISTORY, SearchHistoryList.encode(value)).apply()
        }
    }

    private const val KEY_HISTORY = "search_history"
}

/**
 * The rules the recent searches list follows, with no storage in them.
 *
 * Kept apart from [SearchHistory] so that what the list does to a set of
 * entries can be exercised without a device behind it — the ordering, the cap,
 * the deduplication and the reading of what an older install left behind are
 * all decisions, and decisions are worth being able to ask about directly.
 */
object SearchHistoryList {
    /**
     * How many recents are kept.
     *
     * Long enough to cover a session of looking for one particular thing, and
     * short enough that the list stays a list: past this, entries at the
     * bottom are ones nobody has come back for.
     */
    const val MAX_ENTRIES = 20

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SearchHistoryEntity.serializer())

    /**
     * [entry] at the front, [current] behind it without its earlier self.
     *
     * Re-recording is a move to the top, not a second row: playing the same
     * album again, or searching the same term twice, is the user saying the
     * entry is worth more now, and it should not push the rest of the list
     * down by having done so.
     */
    fun record(
        current: List<SearchHistoryEntity>,
        entry: SearchHistoryEntity,
    ): List<SearchHistoryEntity> {
        if (!entry.isUsable) return current
        val deduped = current.filterNot { it.identity == entry.identity }
        return (listOf(entry) + deduped).take(MAX_ENTRIES)
    }

    fun remove(current: List<SearchHistoryEntity>, identity: String): List<SearchHistoryEntity> =
        current.filterNot { it.identity == identity }

    fun encode(entries: List<SearchHistoryEntity>): String = json.encodeToString(serializer, entries)

    /**
     * Reads whatever is stored into a list worth showing.
     *
     * An install from before entities were recorded has a bare array of terms
     * there, and those are not lost: each becomes a query entry that searches
     * exactly as it did before, which is all such an entry ever could do.
     *
     * An entry that is not readable at all — a half-written value, a type this
     * build doesn't know, a record with no id or no name — is dropped rather
     * than shown as an empty row. A recent list is short by construction, so
     * one bad entry costs nothing by being refused.
     */
    fun decode(raw: String?): List<SearchHistoryEntity> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        // The format before entities were recorded was a bare array of terms
        // and nothing else, so an array of terms is that format. A term among
        // objects is a half-written value instead, and reading it as a search
        // would put a row of nonsense into the user's own history.
        val terms = array.isNotEmpty() && array.all { it is JsonPrimitive && it.isString }
        val entries = LinkedHashMap<String, SearchHistoryEntity>()
        for (element in array) {
            val entry = if (terms) element.toTerm() else element.toEntity()
            if (entry == null || !entry.isUsable) continue
            // Newest first as stored, so the first of a repeated entry is the
            // one that is current and the ones behind it are history.
            if (entries.containsKey(entry.identity)) continue
            entries[entry.identity] = entry
        }
        return entries.values.take(MAX_ENTRIES).toList()
    }

    /** A term as it was stored before entries were: a search, and nothing else. */
    private fun JsonElement.toTerm(): SearchHistoryEntity? =
        (this as? JsonPrimitive)?.contentOrNull?.let { SearchHistoryEntity.forQuery(it) }

    private fun JsonElement.toEntity(): SearchHistoryEntity? = when (this) {
        is JsonObject -> runCatching {
            json.decodeFromJsonElement(SearchHistoryEntity.serializer(), this)
        }.getOrNull()
        else -> null
    }
}
