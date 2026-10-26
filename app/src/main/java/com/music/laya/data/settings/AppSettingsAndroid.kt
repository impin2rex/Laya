package com.music.laya.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.music.laya.BuildConfig
import com.music.laya.auth.AuthStore
import com.music.laya.data.canvas.SpotifyToken
import com.music.laya.data.webdav.update
import com.russhwolf.settings.SharedPreferencesSettings

/** Android's side of [AppSettings]: the same `laya_settings` file as ever, plus the encrypted [AuthStore]. */
private class AndroidAppSettingsHost(
    private val prefs: SharedPreferences,
    private val authStore: AuthStore,
) : AppSettingsHost() {
    override var webdavPassword: String? by authStore::webdavPassword
    override var smbPassword: String? by authStore::smbPassword
    override var discordToken: String? by authStore::discordToken
    override val lastfmApiKey: String = BuildConfig.LASTFM_API_KEY
    override val lastfmSecret: String = BuildConfig.LASTFM_SECRET
    override val signatureApiUrl: String = BuildConfig.SIGNATURE_API_URL

    override fun smbAuthChanged(host: String, share: String, basePath: String, username: String, password: String) =
        com.music.laya.data.smb.SmbAuth.update(host, share, basePath, username, password)

    override fun webdavAuthChanged(url: String, username: String, password: String) =
        com.music.laya.data.webdav.WebDavAuth.update(url, username, password)

    override fun spotifyTokenChanged() = SpotifyToken.invalidate()

    override fun dumpPrefs(): Map<String, Any?> = prefs.all

    override fun replacePrefs(values: Map<String, Any?>) {
        prefs.edit().apply {
            clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
        }.apply()
    }
}

/**
 * [authStore] is the application's own, passed in rather than opened again:
 * each open of the encrypted store is a keystore round trip, and a second
 * one here was a measurable slice of cold start.
 */
fun AppSettings.init(context: Context, authStore: AuthStore) {
    val prefs = context.getSharedPreferences("laya_settings", Context.MODE_PRIVATE)
    init(SharedPreferencesSettings(prefs), AndroidAppSettingsHost(prefs, authStore))
    com.music.laya.data.spotify.LocalPlaylistStore.init(context)
    watchConnection(context)
}

/**
 * Track the active network so [effectiveAudioQuality] can answer without
 * touching ConnectivityManager. Stream resolution happens off the main
 * thread mid-playback; a callback keeps that lookup off the hot path and
 * lets the settings page show which ceiling is currently in force.
 */
private fun AppSettings.watchConnection(context: Context) {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
    val refresh = {
        meteredConnection.value = runCatching {
            if (manager.activeNetwork == null) null else manager.isActiveNetworkMetered
        }.getOrNull()
    }
    refresh()
    runCatching {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) = refresh()
            },
        )
    }
}
