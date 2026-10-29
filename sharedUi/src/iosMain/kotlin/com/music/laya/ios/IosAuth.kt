package com.music.laya.ios

import com.music.laya.data.LikeState
import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.Innertube
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.Account
import com.music.laya.platform.sha256
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

/** One Personal or Brand identity under a Google login (Android's YouTubeProfile). */
@Serializable
internal data class IosProfile(
    val id: String,
    val name: String,
    val handle: String = "",
    val avatar: String? = null,
    val pageId: String? = null,
    val dataSyncId: String? = null,
    val authUser: String? = null,
    val brand: Boolean = false,
) {
    fun asAccount() = Account(name = name, email = handle, thumbnailUrl = avatar)
}

/** A Google login kept independently of every other one (Android's GoogleAccountSession). */
@Serializable
internal data class IosAccount(
    val id: String,
    val cookie: String,
    val name: String = "",
    val email: String = "",
    val profiles: List<IosProfile> = emptyList(),
    val activeProfileId: String? = null,
) {
    val activeProfile: IosProfile? get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}

@Serializable
private data class IosAccountStore(val accounts: List<IosAccount> = emptyList(), val activeId: String? = null) {
    val active: IosAccount? get() = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
}

/**
 * YouTube Music sessions on iOS: every Google login (and its brand channels) Keychain-persisted
 * as one JSON entry, the active one installed into [Innertube]. Mirrors Android's AccountSessions
 * and MainViewModel.onWebSession / selectProfile / removeAccount.
 */
internal object IosAuth {
    private const val STORE = "google_accounts_v1"
    // The single-account keys from before multi-account; read once to migrate, then deleted.
    private const val LEGACY_COOKIE = "cookie"
    private const val LEGACY_PAGE_ID = "channel_page_id"
    private const val LEGACY_DATASYNC_ID = "channel_datasync_id"
    private const val LEGACY_AUTH_USER = "channel_auth_user"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val background = CoroutineScope(Dispatchers.Default)

    val signedIn = MutableStateFlow(false)
    val account = MutableStateFlow<Account?>(null)
    val accounts = MutableStateFlow<List<IosAccount>>(emptyList())
    val activeAccountId = MutableStateFlow<String?>(null)
    /** Bumped on every identity change, so pages that belong to a listener reload. */
    val sessionVersion = MutableStateFlow(0)

    // Same rule as Android's AuthStore.hasApiSid: by cookie *name*, since "__Secure-3PAPISID" contains "SAPISID".
    fun hasApiSid(cookieHeader: String) = cookieHeader.split(';').any { entry ->
        entry.substringBefore('=').trim() in setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID") &&
            entry.substringAfter('=', "").isNotBlank()
    }

    private fun hex(value: String) = sha256(value.encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // Android's profileId(): a provisional name hash until Google reports real ids.
    private fun profileId(pageId: String?, dataSyncId: String?, name: String) =
        pageId ?: dataSyncId ?: "profile:${hex(name).take(16)}"

    private fun load(): IosAccountStore = Keychain.get(STORE)
        ?.let { runCatching { json.decodeFromString<IosAccountStore>(it) }.getOrNull() }
        ?: migrateLegacy()

    private fun save(store: IosAccountStore) {
        Keychain.set(STORE, json.encodeToString(store))
        accounts.value = store.accounts
        activeAccountId.value = store.active?.id
    }

    private fun migrateLegacy(): IosAccountStore {
        val cookie = Keychain.get(LEGACY_COOKIE)?.takeIf(::hasApiSid) ?: return IosAccountStore()
        val pageId = Keychain.get(LEGACY_PAGE_ID)
        val dataSyncId = Keychain.get(LEGACY_DATASYNC_ID)
        val profile = IosProfile(
            id = profileId(pageId, dataSyncId, "YouTube Music"),
            name = "YouTube Music",
            pageId = pageId,
            dataSyncId = dataSyncId,
            authUser = Keychain.get(LEGACY_AUTH_USER),
            brand = pageId != null,
        )
        val migrated = IosAccount(hex(cookie).take(24), cookie, profiles = listOf(profile), activeProfileId = profile.id)
        return IosAccountStore(listOf(migrated), migrated.id).also { store ->
            save(store)
            listOf(LEGACY_COOKIE, LEGACY_PAGE_ID, LEGACY_DATASYNC_ID, LEGACY_AUTH_USER).forEach { Keychain.set(it, null) }
        }
    }

    /** Signs requests as [profile] of [login]; cookie first, since setting it wipes the channel. */
    private fun install(login: IosAccount?, profile: IosProfile?) {
        Innertube.cookie = login?.cookie
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }

    /** Everything cached for the identity being left goes; pages keyed on [sessionVersion] refetch. */
    private fun identityChanged(shown: Account?) {
        StreamResolver.onSessionChanged()
        LikeState.clear()
        account.value = shown
        signedIn.value = shown != null
        sessionVersion.value++
    }

    /** Before the first Innertube call. */
    fun restore() {
        val store = load()
        accounts.value = store.accounts
        activeAccountId.value = store.active?.id
        val active = store.active ?: return
        install(active, active.activeProfile)
        signedIn.value = true
        account.value = active.activeProfile?.asAccount()
        background.launch {
            Innertube.ensureSessionScope()
            refreshActive()
        }
    }

    /**
     * Validates [s] against the account endpoint before persisting it, like MainViewModel.onWebSession.
     * The same Google login (by email) updates its entry; any other is added beside the rest.
     */
    suspend fun adopt(s: WebSession): Boolean {
        Innertube.cookie = s.cookie
        Innertube.adoptSessionScope(s.pageId, s.dataSyncId, s.authUser, s.visitorData, s.clientVersion, loggedIn = true)
        Innertube.selectChannel(s.pageId, s.dataSyncId, s.authUser)
        val fetched = withTimeoutOrNull(20_000L) {
            var r = YtMusicRepository.account()
            if (r.isFailure) { delay(750L); r = YtMusicRepository.account() }
            r.getOrNull()
        }
        val store = load()
        if (fetched == null) {
            install(store.active, store.active?.activeProfile) // put the previous session (or none) back
            return false
        }
        val previous = store.accounts.firstOrNull {
            fetched.email.isNotBlank() && it.email.equals(fetched.email, ignoreCase = true)
        }
        val profile = IosProfile(
            id = profileId(s.pageId, s.dataSyncId, fetched.name),
            name = fetched.name,
            handle = fetched.email,
            avatar = fetched.thumbnailUrl,
            pageId = s.pageId,
            dataSyncId = s.dataSyncId,
            authUser = s.authUser,
            brand = s.pageId != null,
        )
        val hasRealIdentity = profile.pageId != null || profile.dataSyncId != null
        val login = IosAccount(
            id = previous?.id ?: hex(s.cookie).take(24),
            cookie = s.cookie,
            name = fetched.name,
            email = fetched.email,
            profiles = previous?.profiles.orEmpty().filterNot {
                it.id == profile.id || (hasRealIdentity && it.id.startsWith("profile:"))
            } + profile,
            activeProfileId = profile.id,
        )
        val others = store.accounts.filterNot { it.id == login.id }
        save(IosAccountStore(if (previous != null) store.accounts.map { if (it.id == login.id) login else it } else others + login, login.id))
        identityChanged(fetched)
        background.launch { refreshProfiles() }
        return true
    }

    /** Acts as [profileId] of [accountId] from here on. */
    fun select(accountId: String, profileId: String) {
        val store = load()
        val login = store.accounts.firstOrNull { it.id == accountId } ?: return
        val profile = login.profiles.firstOrNull { it.id == profileId } ?: return
        if (store.active?.id == accountId && login.activeProfile?.id == profileId) return
        val updated = login.copy(activeProfileId = profileId)
        save(IosAccountStore(store.accounts.map { if (it.id == accountId) updated else it }, accountId))
        install(updated, profile)
        identityChanged(profile.asAccount())
        background.launch { refreshActive() }
    }

    /** Forgets one login; the next one takes over, or nobody is signed in. */
    suspend fun remove(accountId: String) {
        val store = load()
        val remaining = store.accounts.filterNot { it.id == accountId }
        if (remaining.isEmpty()) return signOut()
        val wasActive = store.active?.id == accountId
        val next = if (wasActive) remaining.first() else store.active!!
        save(IosAccountStore(remaining, next.id))
        if (!wasActive) return
        install(next, next.activeProfile)
        identityChanged(next.activeProfile?.asAccount())
        background.launch { refreshActive() }
    }

    /** Forgets every login. */
    suspend fun signOut() {
        save(IosAccountStore())
        install(null, null)
        identityChanged(null)
        clearGoogleCookies()
    }

    private suspend fun refreshActive() {
        YtMusicRepository.account().getOrNull()?.let { fetched ->
            if (signedIn.value) account.value = fetched
        }
        refreshProfiles()
    }

    /**
     * The brand channels the active login can act as, merged into its profiles (Android's
     * persistDetectedProfiles). An empty answer never drops a known profile.
     */
    private suspend fun refreshProfiles() {
        val accountId = activeAccountId.value ?: return
        val channels = YtMusicRepository.accountChannels().getOrNull().orEmpty()
        if (channels.isEmpty()) return
        val store = load()
        val login = store.accounts.firstOrNull { it.id == accountId } ?: return
        val authUser = login.activeProfile?.authUser
        val detected = channels.map { c ->
            IosProfile(profileId(c.pageId, c.dataSyncId, c.name), c.name, c.subtitle, c.thumbnailUrl, c.pageId, c.dataSyncId, authUser, c.pageId != null)
        }
        val kept = login.profiles.filter { known -> !known.id.startsWith("profile:") && detected.none { it.id == known.id } }
        val profiles = kept + detected
        val active = login.activeProfileId?.takeIf { id -> profiles.any { it.id == id } }
            ?: detected.firstOrNull { it.dataSyncId != null && it.dataSyncId == login.activeProfile?.dataSyncId }?.id
            ?: profiles.first().id
        save(store.copy(accounts = store.accounts.map { if (it.id == accountId) login.copy(profiles = profiles, activeProfileId = active) else it }))
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
