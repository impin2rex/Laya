package com.music.laya.ios

import com.music.laya.data.LikeState
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.Innertube
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.Account
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.WebKit.WKWebsiteDataStore
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Keychain get/set/delete (kSecClassGenericPassword), copied from the on-device-proven BitChord-iOS.
 * AfterFirstUnlockThisDeviceOnly: readable with the screen locked, never synced or restored elsewhere.
 */
@OptIn(BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
internal object Keychain {
    private const val SERVICE = "com.music.laya"

    private inline fun <T> withQuery(key: String, fill: (CFMutableDictionaryRef?) -> Unit, use: (CFMutableDictionaryRef?) -> T): T {
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val service = CFBridgingRetain(SERVICE)
        val account = CFBridgingRetain(key)
        try {
            CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(dict, kSecAttrService, service)
            CFDictionaryAddValue(dict, kSecAttrAccount, account)
            fill(dict)
            return use(dict)
        } finally {
            CFRelease(service)
            CFRelease(account)
            CFRelease(dict)
        }
    }

    fun get(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(
            key,
            fill = { dict ->
                CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
                CFDictionaryAddValue(dict, kSecMatchLimit, kSecMatchLimitOne)
            },
            use = { dict -> SecItemCopyMatching(dict, result.ptr) },
        )
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    fun set(key: String, value: String?) {
        withQuery(key, fill = {}, use = { dict -> SecItemDelete(dict) })
        if (value == null) return
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val bridged: CFTypeRef? = CFBridgingRetain(data)
        try {
            withQuery(
                key,
                fill = { dict ->
                    CFDictionaryAddValue(dict, kSecValueData, bridged)
                    CFDictionaryAddValue(dict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                },
                use = { dict -> SecItemAdd(dict, null) },
            )
        } finally {
            CFRelease(bridged)
        }
    }
}

/** What the sign-in page reported about itself (Android's CapturedSession). */
internal class WebSession(
    val cookie: String,
    val pageId: String?,
    val dataSyncId: String?,
    val authUser: String?,
    val visitorData: String?,
    val clientVersion: String?,
)

/**
 * Single-account YouTube Music session on iOS: Keychain-persisted cookie + channel scope,
 * installed into [Innertube]. ponytail: no multi-account / brand-channel switcher (Android's AccountSessions).
 */
internal object IosAuth {
    private const val COOKIE = "cookie"
    private const val PAGE_ID = "channel_page_id"
    private const val DATASYNC_ID = "channel_datasync_id"
    private const val AUTH_USER = "channel_auth_user"
    private val KEYS = listOf(COOKIE, PAGE_ID, DATASYNC_ID, AUTH_USER)

    val signedIn = MutableStateFlow(false)
    val account = MutableStateFlow<Account?>(null)

    // Same rule as Android's AuthStore.hasApiSid: by cookie *name*, since "__Secure-3PAPISID" contains "SAPISID".
    fun hasApiSid(cookieHeader: String) = cookieHeader.split(';').any { entry ->
        entry.substringBefore('=').trim() in setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID") &&
            entry.substringAfter('=', "").isNotBlank()
    }

    /** Installs the stored session into [Innertube] (or clears it). Cookie first: setting it wipes the channel. */
    private fun applyStored(): Boolean {
        val cookie = Keychain.get(COOKIE)?.takeIf(::hasApiSid)
        Innertube.cookie = cookie
        val pageId = Keychain.get(PAGE_ID)
        val dataSyncId = Keychain.get(DATASYNC_ID)
        Innertube.selectChannel(if (cookie != null) pageId else null, if (cookie != null) dataSyncId else null, Keychain.get(AUTH_USER))
        return cookie != null
    }

    /** Before the first Innertube call. */
    fun restore() {
        if (!applyStored()) return
        signedIn.value = true
        CoroutineScope(Dispatchers.Default).launch {
            Innertube.ensureSessionScope()
            account.value = YtMusicRepository.account().getOrNull()
        }
    }

    /** Validates [s] against the account endpoint before persisting it, like MainViewModel.onWebSession. */
    suspend fun adopt(s: WebSession): Boolean {
        Innertube.cookie = s.cookie
        Innertube.adoptSessionScope(s.pageId, s.dataSyncId, s.authUser, s.visitorData, s.clientVersion, loggedIn = true)
        Innertube.selectChannel(s.pageId, s.dataSyncId, s.authUser)
        val fetched = withTimeoutOrNull(20_000L) {
            var r = YtMusicRepository.account()
            if (r.isFailure) { delay(750L); r = YtMusicRepository.account() }
            r.getOrNull()
        }
        if (fetched == null) {
            applyStored() // put the previous session (or none) back
            return false
        }
        Keychain.set(COOKIE, s.cookie)
        Keychain.set(PAGE_ID, s.pageId)
        Keychain.set(DATASYNC_ID, s.dataSyncId)
        Keychain.set(AUTH_USER, s.authUser)
        StreamResolver.onSessionChanged()
        LikeState.clear()
        account.value = fetched
        signedIn.value = true
        return true
    }

    suspend fun signOut() {
        KEYS.forEach { Keychain.set(it, null) }
        Innertube.cookie = null
        Innertube.selectChannel(null, null)
        StreamResolver.onSessionChanged()
        LikeState.clear()
        signedIn.value = false
        account.value = null
        clearGoogleCookies()
    }

    // ---- the WKWebView's own cookie jar (separate from the app's, as on Android) ----

    private val store get() = WKWebsiteDataStore.defaultDataStore().httpCookieStore

    private suspend fun allCookies(): List<NSHTTPCookie> = withContext(Dispatchers.Main) {
        suspendCoroutine { c -> store.getAllCookies { c.resume(it.orEmpty().filterIsInstance<NSHTTPCookie>()) } }
    }

    /** Cookie header the page would send to [host], null if none. */
    suspend fun cookieHeader(host: String): String? = allCookies().filter {
        val d = it.domain
        if (d.startsWith('.')) host.endsWith(d.drop(1)) else host == d
    }.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }

    /** Google's cookies only; never visits the logout endpoint, so other sessions stay valid. */
    suspend fun clearGoogleCookies() = deleteCookies { d -> listOf("youtube.com", "google.com").any { d.trimStart('.').endsWith(it) } }

    /** Deletes the web view's cookies whose domain matches [byDomain]. */
    suspend fun deleteCookies(byDomain: (String) -> Boolean) {
        allCookies().filter { byDomain(it.domain) }.forEach { c ->
            withContext(Dispatchers.Main) { suspendCoroutine<Unit> { k -> store.deleteCookie(c) { k.resume(Unit) } } }
        }
    }
}
