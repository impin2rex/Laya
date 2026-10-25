package com.music.laya.ios

import com.music.laya.data.model.SearchHistoryEntity
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** Recent searches, same shape and 20-entry cap as Android's SearchHistory; stored as JSON in NSUserDefaults. */
internal object IosSearchHistory {
    private const val KEY = "search_history"
    private const val MAX_ENTRIES = 20
    private val prefs = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SearchHistoryEntity.serializer())

    private val _recent = MutableStateFlow(
        runCatching { json.decodeFromString(serializer, prefs.getString(KEY, "[]")) }.getOrDefault(emptyList()),
    )
    val recent: StateFlow<List<SearchHistoryEntity>> = _recent

    fun record(entity: SearchHistoryEntity) =
        save((listOf(entity) + _recent.value.filterNot { it.id.equals(entity.id, ignoreCase = true) }).take(MAX_ENTRIES))

    fun remove(id: String) = save(_recent.value.filterNot { it.id.equals(id, ignoreCase = true) })

    fun clear() = save(emptyList())

    private fun save(value: List<SearchHistoryEntity>) {
        _recent.value = value
        prefs.putString(KEY, json.encodeToString(serializer, value))
    }
}
