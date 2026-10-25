package com.music.laya.ios

import com.music.laya.data.listentogether.JamInvite
import com.music.laya.data.listentogether.JamInviteLink
import com.music.laya.data.listentogether.ListenTogether
import com.music.laya.data.listentogether.PartyAccount
import com.music.laya.data.listentogether.PartyHost
import com.music.laya.platform.ktorClient
import com.music.laya.ui.player.PartyUi
import com.music.laya.ui.screens.PartyInfo
import com.russhwolf.settings.NSUserDefaultsSettings
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import platform.Foundation.NSUserDefaults
import kotlin.time.TimeSource

/** Listen Together on iOS: the host [ListenTogether] runs on, and the party as the shared UI reads it. */
internal object IosParty {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val boot = TimeSource.Monotonic.markNow()

    val ui: StateFlow<PartyUi> = ListenTogether.state
        .map { PartyUi(it.inParty, it.controlsLocked, it.members, it.you, it.code) }
        .stateIn(scope, SharingStarted.Eagerly, PartyUi())

    val info: StateFlow<PartyInfo> = ListenTogether.state
        .map { PartyInfo(it.code, it.members.size) }
        .stateIn(scope, SharingStarted.Eagerly, PartyInfo(null, 0))

    fun install() {
        ListenTogether.init(
            PartyHost(
                settings = NSUserDefaultsSettings(NSUserDefaults(suiteName = "laya_listen_together")),
                defaultServer = JamInvite.ORIGIN,
                elapsedRealtimeMs = { boot.elapsedNow().inWholeMilliseconds },
                account = {
                    IosAuth.account.value?.takeIf { IosAuth.signedIn.value }?.let {
                        PartyAccount(
                            idSeed = it.email.ifBlank { it.name },
                            names = listOf(it.name, it.email.substringBefore('@')),
                            avatar = it.thumbnailUrl,
                        )
                    }
                },
                createClient = { config -> ktorClient(config) },
                isTransportError = { it is DarwinHttpRequestException },
            ),
        )
    }
}

/** Called by Swift with a `laya://party/...` or `https://.../invite/...` link; false when it is neither. */
fun handleInviteUrl(url: String): Boolean = JamInviteLink.offer(url)
