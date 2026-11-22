package com.music.laya.data.innertube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * YouTube Premium audio (itag 774, ~256kbps Opus): whether the signed-in account has it, and whether to ask for it.
 *
 * The account is detected, not taken on the listener's word: the `account_menu` popup (see
 * [InnertubeParser.parsePremium]) is read once per sign-in, and a stream that actually came back as Premium's Opus
 * confirms it. The verdict is remembered per account. [override] is the listener's say, winning over detection
 * either way; null means "follow detection".
 *
 * Storage is the platform's own, via [read] and [write].
 */
object PremiumAudio {
    /** The platform's key-value store; null deletes. Set before [load]. */
    var read: (String) -> String? = { null }
    var write: (String, String?) -> Unit = { _, _ -> }

    /** The listener's choice, or null to follow [detected]. */
    val override = MutableStateFlow<Boolean?>(null)

    /** Whether the signed-in account has Premium; null while unknown (or signed out). */
    val detected = MutableStateFlow<Boolean?>(null)

    private var account: String? = null
    private var checkedFor: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Whether to ask for Premium's Opus: the override, else detection. */
    fun enabled(): Boolean = override.value ?: (detected.value == true)

    /** Reads what the store holds: the override and the last account's remembered verdict. */
    fun load() {
        override.value = read(KEY_OVERRIDE)?.toBooleanStrictOrNull()
        account = read(KEY_ACCOUNT)
        detected.value = account?.let { read(KEY_DETECTED + it)?.toBooleanStrictOrNull() }
    }

    fun setOverride(value: Boolean?) {
        override.value = value
        write(KEY_OVERRIDE, value?.toString())
        // Remembered URLs were picked under the old route; the next play asks again.
        StreamResolver.forgetResolved()
    }

    /** Reads the account's verdict from an `account_menu` [response]; an unclear menu leaves the last one standing. */
    fun observe(response: JsonElement) {
        val account = InnertubeParser.parseAccount(response) ?: return
        remember(account.email.ifBlank { account.name }, InnertubeParser.parsePremium(response) ?: return)
    }

    /** A signed-in stream came back as Premium's Opus: the account has it, whatever the menu said. */
    fun confirm() {
        remember(account ?: return, true)
    }

    private fun remember(key: String, premium: Boolean) {
        if (account != key) write(KEY_ACCOUNT, key)
        account = key
        checkedFor = Innertube.cookie
        if (detected.value != premium) detected.value = premium
        write(KEY_DETECTED + key, premium.toString())
    }

    /** Once per sign-in, reads the account menu in the background. Cheap to call on every resolve. */
    fun ensureChecked() {
        val cookie = Innertube.cookie
        if (cookie.isNullOrBlank() || checkedFor == cookie) return
        checkedFor = cookie
        scope.launch { runCatching { observe(Innertube.accountMenu()) }.onFailure { checkedFor = null } }
    }

    /** Signed out or switched: the old account's verdict no longer applies. */
    fun onSessionChanged() {
        checkedFor = null
        account = null
        detected.value = null
    }

    private const val KEY_OVERRIDE = "youtube_premium_audio"
    private const val KEY_ACCOUNT = "youtube_premium_account"
    private const val KEY_DETECTED = "youtube_premium_detected:"
}
