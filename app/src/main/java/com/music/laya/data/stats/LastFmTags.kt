package com.music.laya.data.stats

import android.content.Context
import android.net.Uri
import com.music.laya.BuildConfig
import com.music.laya.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Path.Companion.toPath
import java.io.File

/**
 * Opens the listening record and the artist cache where Android has always kept them
 * (`filesDir/listening`, `filesDir/artist_facts.json`), and gives [ArtistFacts] its Last.fm
 * lookup. The logic itself lives in :shared; only the storage location and the HTTP client
 * are the phone's.
 */
fun initListening(context: Context) {
    ListeningStats.init(File(context.filesDir, ListeningStats.DIRECTORY).absolutePath.toPath())
}

fun initArtistFacts(context: Context) {
    if (BuildConfig.LASTFM_API_KEY.isNotBlank()) {
        ArtistFacts.genreTagsFetcher = ::fetchLastFmTags
    }
    ArtistFacts.init(File(context.filesDir, ArtistFacts.FILE_NAME).absolutePath.toPath())
}

private suspend fun fetchLastFmTags(name: String): String? = withContext(Dispatchers.IO) {
    val url = "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags" +
        "&artist=${Uri.encode(name)}" +
        "&api_key=${Uri.encode(BuildConfig.LASTFM_API_KEY)}" +
        "&autocorrect=1&format=json"
    val request = Request.Builder().url(url).header("User-Agent", "Laya/${BuildConfig.VERSION_NAME}").build()
    Http.client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) null else response.body.string()
    }
}
