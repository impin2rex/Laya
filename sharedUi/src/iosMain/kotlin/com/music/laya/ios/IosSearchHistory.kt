package com.music.laya.ios

import com.music.laya.data.model.SearchHistoryEntity
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** Recent searches, same shape and 20-entry cap as Android's SearchHistory; stored as JSON in NSUserDefaults. */
internal object IosSearchHistory {
    private const val KEY = "search_history"
    private const val ADDON_KEY = "addon_search_history"
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

    // The Addons tab remembers words, not entities (Android's SearchHistory.addonRecent).
    private val addonSerializer = ListSerializer(String.serializer())
    private val _addonRecent = MutableStateFlow(
        runCatching { json.decodeFromString(addonSerializer, prefs.getString(ADDON_KEY, "[]")) }.getOrDefault(emptyList()),
    )
    val addonRecent: StateFlow<List<String>> = _addonRecent

    fun recordAddon(query: String) {
        val text = query.trim()
        if (text.isNotEmpty()) saveAddon((listOf(text) + _addonRecent.value.filterNot { it.equals(text, ignoreCase = true) }).take(MAX_ENTRIES))
    }

    fun removeAddon(query: String) = saveAddon(_addonRecent.value.filterNot { it == query })

    fun clearAddons() = saveAddon(emptyList())

    private fun saveAddon(value: List<String>) {
        _addonRecent.value = value
        prefs.putString(ADDON_KEY, json.encodeToString(addonSerializer, value))
    }

    private fun save(value: List<SearchHistoryEntity>) {
        _recent.value = value
        prefs.putString(KEY, json.encodeToString(serializer, value))
    }
}
