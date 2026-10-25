package com.music.laya.data.listentogether

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.music.laya.BuildConfig
import com.music.laya.LayaApplication
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/** Wires [ListenTogether] to Android: prefs, the clock, the account and the connectivity callback. */
fun ListenTogether.init(context: Context) {
    val app = context.applicationContext
    init(
        PartyHost(
            settings = SharedPreferencesSettings(app.getSharedPreferences("laya_listen_together", Context.MODE_PRIVATE)),
            defaultServer = BuildConfig.LISTEN_TOGETHER_SERVER,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
            account = {
                val store = LayaApplication.authStore
                val session = store.activeSession.takeIf { store.isSignedIn }
                session?.let { account ->
                    val profile = account.profiles.firstOrNull { it.profileId == account.activeProfileId }
                        ?: account.profiles.firstOrNull()
                    PartyAccount(
                        idSeed = "${account.accountId}:${profile?.profileId.orEmpty()}",
                        names = listOf(profile?.name.orEmpty(), account.name, account.email.substringBefore('@')),
                        avatar = profile?.avatar,
                    )
                }
            },
            createClient = { config ->
                HttpClient(OkHttp) {
                    engine {
                        config {
                            // Not the shared Http.client: its read timeout would take down a
                            // socket that is merely quiet.
                            readTimeout(0, TimeUnit.MILLISECONDS)
                            connectTimeout(15, TimeUnit.SECONDS)
                            pingInterval(20, TimeUnit.SECONDS)
                            retryOnConnectionFailure(true)
                        }
                    }
                    config()
                }
            },
        ),
    )
    runCatching {
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkAvailable()
                override fun onLost(network: Network) = onNetworkLost()
            },
        )
    }
}
