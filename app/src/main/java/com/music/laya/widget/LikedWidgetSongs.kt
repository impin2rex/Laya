package com.music.laya.widget

import android.content.Context
import com.music.laya.data.model.Song

/**
 * The first few Liked Music tracks, for [MediaWidgetLibrary]'s bottom row.
 *
 * Persisted for the same reason [MediaWidgetSnapshot] is: the widget is drawn
 * while the process is dead, and the liked list otherwise only lives in the
 * view model. [MainViewModel][com.music.laya.ui.MainViewModel] writes it
 * whenever the library loads, and empties it on sign-out.
 */
internal object LikedWidgetSongs {

    data class Entry(val videoId: String, val title: String, val artworkUrl: String?)

    fun save(context: Context, songs: List<Song>) {
        val encoded = songs.take(MAX).joinToString(LINE) { song ->
            listOf(song.videoId, song.title, song.thumbnailUrl.orEmpty())
                .joinToString(FIELD) { it.replace(FIELD, " ").replace(LINE, " ") }
        }
        val prefs = prefs(context)
        // The library reloads often and mostly unchanged; only a real change redraws.
        if (prefs.getString(KEY, null) == encoded) return
        prefs.edit().putString(KEY, encoded).apply()
        MediaWidgetLibrary.refresh(context)
    }

    fun load(context: Context): List<Entry> =
        prefs(context).getString(KEY, null).orEmpty()
            .split(LINE)
            .mapNotNull { line ->
                val fields = line.split(FIELD)
                val id = fields.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(id, fields.getOrNull(1).orEmpty(), fields.getOrNull(2)?.takeIf { it.isNotBlank() })
            }

    private fun prefs(context: Context) =
        context.getSharedPreferences("laya_widget", Context.MODE_PRIVATE)

    private const val KEY = "liked_songs"
    private const val MAX = 5
    private const val FIELD = "\t"
    private const val LINE = "\n"
}
