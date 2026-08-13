@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.auth.netease

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.auth.common.PlatformAccountPurpose
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import org.json.JSONArray
import org.json.JSONObject

private const val NETEASE_AUTH_PREFS = "netease_auth_secure_prefs"
private const val KEY_NETEASE_AUTH_BUNDLE = "netease_auth_bundle"
private const val NETEASE_COOKIE_FALLBACK_OS = "pc"
private const val NETEASE_COOKIE_FALLBACK_APPVER = "8.10.35"

private val Context.cookieDataStore by preferencesDataStore("auth_store")

object CookieKeys {
    val NETEASE_COOKIE_JSON = stringPreferencesKey("netease_cookie_json")
}

private val NETEASE_LOGIN_COOKIE_KEYS = listOf("MUSIC_U")
private val NETEASE_COOKIE_NAME_REGEX = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

data class NeteaseCookieValidationResult(
    val sanitizedCookies: Map<String, String> = emptyMap(),
    val rejectedKeys: List<String> = emptyList()
) {
    val hasLoginCookie: Boolean
        get() = NETEASE_LOGIN_COOKIE_KEYS.any { key -> !sanitizedCookies[key].isNullOrBlank() }
    val isAccepted: Boolean
        get() = sanitizedCookies.isNotEmpty() && hasLoginCookie
}

internal fun validateAndSanitizeNeteaseCookies(
    cookies: Map<String, String>,
    includeFallbackCookies: Boolean = true
): NeteaseCookieValidationResult {
    val sanitized = linkedMapOf<String, String>()
    val rejected = linkedSetOf<String>()
    cookies.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        val rejectedKey = key.ifBlank { "<blank>" }
        when {
            key.isBlank() -> rejected += rejectedKey
            !NETEASE_COOKIE_NAME_REGEX.matches(key) -> rejected += rejectedKey
            value.isBlank() -> rejected += rejectedKey
            value.any { it.isISOControl() } -> rejected += rejectedKey
            ';' in value -> rejected += rejectedKey
            else -> sanitized[key] = value
        }
    }
    if (includeFallbackCookies && sanitized.isNotEmpty()) {
        sanitized.putIfAbsent("os", NETEASE_COOKIE_FALLBACK_OS)
        sanitized.putIfAbsent("appver", NETEASE_COOKIE_FALLBACK_APPVER)
    }
    return NeteaseCookieValidationResult(sanitized, rejected.toList())
}

data class NeteaseAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean = NETEASE_LOGIN_COOKIE_KEYS.any { !cookies[it].isNullOrBlank() }
    fun normalized(savedAt: Long = this.savedAt): NeteaseAuthBundle = copy(
        cookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() }),
        savedAt = savedAt
    )
    fun toJson(): String = JSONObject().apply {
        put("cookies", cookies.toJsonObject())
        put("savedAt", savedAt)
    }.toString()

    companion object {
        fun fromJson(json: String): NeteaseAuthBundle = runCatching {
            val root = JSONObject(json)
            NeteaseAuthBundle(root.optJSONObject("cookies").toStringMap(), root.optLong("savedAt", 0L))
                .normalized(root.optLong("savedAt", 0L))
        }.getOrDefault(NeteaseAuthBundle())
    }
}

data class NeteaseAccount(
    val id: String,
    val name: String,
    val cookies: Map<String, String>,
    val savedAt: Long
) {
    fun toAuthBundle() = NeteaseAuthBundle(cookies, savedAt)
}

data class NeteaseAccountsState(
    val accounts: List<NeteaseAccount> = emptyList(),
    val primaryAccountId: String? = null,
    val playHistoryAccountId: String? = null,
    val streamingAccountId: String? = null
) {
    fun selectedId(purpose: PlatformAccountPurpose): String? = when (purpose) {
        PlatformAccountPurpose.PRIMARY -> primaryAccountId
        PlatformAccountPurpose.PLAY_HISTORY -> playHistoryAccountId ?: primaryAccountId
        PlatformAccountPurpose.STREAMING -> streamingAccountId ?: primaryAccountId
    }

    fun account(purpose: PlatformAccountPurpose): NeteaseAccount? {
        val id = selectedId(purpose)
        return accounts.firstOrNull { it.id == id } ?: accounts.firstOrNull()
    }

    fun normalized(): NeteaseAccountsState {
        val ids = accounts.mapTo(linkedSetOf()) { it.id }
        val primary = primaryAccountId?.takeIf(ids::contains) ?: accounts.firstOrNull()?.id
        return copy(
            primaryAccountId = primary,
            playHistoryAccountId = playHistoryAccountId?.takeIf(ids::contains) ?: primary,
            streamingAccountId = streamingAccountId?.takeIf(ids::contains) ?: primary
        )
    }
}

internal fun evaluateNeteaseAuthHealth(
    bundle: NeteaseAuthBundle,
    now: Long = System.currentTimeMillis()
): SavedCookieAuthHealth {
    val normalized = bundle.normalized(bundle.savedAt)
    val loginCookieKeys = NETEASE_LOGIN_COOKIE_KEYS.filter { !normalized.cookies[it].isNullOrBlank() }
    if (loginCookieKeys.isEmpty()) {
        return SavedCookieAuthHealth(SavedCookieAuthState.Missing, normalized.savedAt, now)
    }
    val ageMs = if (normalized.savedAt > 0L) (now - normalized.savedAt).coerceAtLeast(0L) else Long.MAX_VALUE
    return SavedCookieAuthHealth(
        state = SavedCookieAuthState.Valid,
        savedAt = normalized.savedAt,
        checkedAt = now,
        ageMs = ageMs,
        loginCookieKeys = loginCookieKeys
    )
}

class NeteaseCookieRepository(private val context: Context) {
    private var encryptedPrefs: SharedPreferences = openEncryptedPrefsWithRecovery()
    private val _accountsFlow = MutableStateFlow(loadAccountsState())
    private val _cookieFlow = MutableStateFlow(cookiesFor(PlatformAccountPurpose.PRIMARY))
    private val _playHistoryCookieFlow = MutableStateFlow(cookiesFor(PlatformAccountPurpose.PLAY_HISTORY))
    private val _streamingCookieFlow = MutableStateFlow(cookiesFor(PlatformAccountPurpose.STREAMING))
    private val _authHealthFlow = MutableStateFlow(evaluateNeteaseAuthHealth(primaryBundle()))
    private var addAccountOnNextSave = false

    val accountsFlow: StateFlow<NeteaseAccountsState> = _accountsFlow.asStateFlow()
    val cookieFlow: StateFlow<Map<String, String>> = _cookieFlow.asStateFlow()
    val playHistoryCookieFlow: StateFlow<Map<String, String>> = _playHistoryCookieFlow.asStateFlow()
    val streamingCookieFlow: StateFlow<Map<String, String>> = _streamingCookieFlow.asStateFlow()
    val authHealthFlow: StateFlow<SavedCookieAuthHealth> = _authHealthFlow.asStateFlow()

    fun getCookiesOnce(): Map<String, String> = _cookieFlow.value
    fun getPlayHistoryCookiesOnce(): Map<String, String> = _playHistoryCookieFlow.value
    fun getStreamingCookiesOnce(): Map<String, String> = _streamingCookieFlow.value
    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value
    fun getAuthHealth(now: Long = System.currentTimeMillis()) = evaluateNeteaseAuthHealth(primaryBundle(), now)
    fun validateCookies(cookies: Map<String, String>) = validateAndSanitizeNeteaseCookies(cookies)

    @Synchronized
    fun beginAddAccount() {
        addAccountOnNextSave = true
    }

    @Synchronized
    fun cancelAddAccount() {
        addAccountOnNextSave = false
    }

    @Synchronized
    fun saveCookies(cookies: Map<String, String>, savedAt: Long = System.currentTimeMillis()): Boolean {
        val validation = validateCookies(cookies)
        if (!validation.isAccepted) {
            NPLogger.w("NERI-CookieRepo", "Rejected invalid NetEase cookies: ${validation.rejectedKeys}")
            return false
        }
        val current = _accountsFlow.value
        val updatedAccounts = current.accounts.toMutableList()
        val primaryId = current.primaryAccountId
        val targetIndex = if (addAccountOnNextSave) -1 else updatedAccounts.indexOfFirst { it.id == primaryId }
        val account = NeteaseAccount(
            id = if (targetIndex >= 0) updatedAccounts[targetIndex].id else UUID.randomUUID().toString(),
            name = if (targetIndex >= 0) updatedAccounts[targetIndex].name else defaultAccountName(updatedAccounts.size + 1),
            cookies = validation.sanitizedCookies,
            savedAt = savedAt
        )
        if (targetIndex >= 0) updatedAccounts[targetIndex] = account else updatedAccounts += account
        val updated = current.copy(
            accounts = updatedAccounts,
            primaryAccountId = if (current.primaryAccountId == null) account.id else current.primaryAccountId
        ).normalized()
        addAccountOnNextSave = false
        persistAndPublish(updated)
        return true
    }

    @Synchronized
    fun renameAccount(accountId: String, name: String) {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return
        persistAndPublish(_accountsFlow.value.copy(
            accounts = _accountsFlow.value.accounts.map { if (it.id == accountId) it.copy(name = cleanName) else it }
        ).normalized())
    }

    @Synchronized
    fun selectAccount(purpose: PlatformAccountPurpose, accountId: String) {
        if (_accountsFlow.value.accounts.none { it.id == accountId }) return
        val current = _accountsFlow.value
        val updated = when (purpose) {
            PlatformAccountPurpose.PRIMARY -> current.copy(primaryAccountId = accountId)
            PlatformAccountPurpose.PLAY_HISTORY -> current.copy(playHistoryAccountId = accountId)
            PlatformAccountPurpose.STREAMING -> current.copy(streamingAccountId = accountId)
        }
        persistAndPublish(updated.normalized())
    }

    @Synchronized
    fun deleteAccount(accountId: String) {
        persistAndPublish(_accountsFlow.value.copy(
            accounts = _accountsFlow.value.accounts.filterNot { it.id == accountId }
        ).normalized())
    }

    fun clear() {
        val primaryId = _accountsFlow.value.primaryAccountId ?: return
        deleteAccount(primaryId)
    }

    fun clearAllAccounts() {
        persistAndPublish(NeteaseAccountsState())
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateNeteaseAuthHealth(primaryBundle(), now)
    }

    private fun primaryBundle() = _accountsFlow.value.account(PlatformAccountPurpose.PRIMARY)?.toAuthBundle()
        ?: NeteaseAuthBundle()

    private fun cookiesFor(purpose: PlatformAccountPurpose) =
        _accountsFlow.value.account(purpose)?.cookies.orEmpty()

    private fun persistAndPublish(state: NeteaseAccountsState) {
        val normalized = state.normalized()
        encryptedPrefs.edit { putString(KEY_NETEASE_AUTH_BUNDLE, normalized.toJson()) }
        _accountsFlow.value = normalized
        _cookieFlow.value = normalized.account(PlatformAccountPurpose.PRIMARY)?.cookies.orEmpty()
        _playHistoryCookieFlow.value = normalized.account(PlatformAccountPurpose.PLAY_HISTORY)?.cookies.orEmpty()
        _streamingCookieFlow.value = normalized.account(PlatformAccountPurpose.STREAMING)?.cookies.orEmpty()
        _authHealthFlow.value = evaluateNeteaseAuthHealth(primaryBundle())
    }

    private fun loadAccountsState(): NeteaseAccountsState {
        val raw = encryptedPrefs.getString(KEY_NETEASE_AUTH_BUNDLE, null).orEmpty()
        if (raw.isNotBlank()) {
            val root = runCatching { JSONObject(raw) }.getOrNull()
            if (root?.has("accounts") == true) return root.toNeteaseAccountsState()
            val legacy = NeteaseAuthBundle.fromJson(raw)
            if (legacy.hasLoginCookies()) return singleAccountState(legacy)
        }
        return migrateLegacyCookies()?.let(::singleAccountState) ?: NeteaseAccountsState()
    }

    private fun singleAccountState(bundle: NeteaseAuthBundle): NeteaseAccountsState {
        val id = UUID.randomUUID().toString()
        return NeteaseAccountsState(
            accounts = listOf(NeteaseAccount(id, defaultAccountName(1), bundle.cookies, bundle.savedAt)),
            primaryAccountId = id,
            playHistoryAccountId = id,
            streamingAccountId = id
        ).also { encryptedPrefs.edit { putString(KEY_NETEASE_AUTH_BUNDLE, it.toJson()) } }
    }

    private fun migrateLegacyCookies(): NeteaseAuthBundle? {
        val prefs = runCatching { runBlocking { context.cookieDataStore.data.first() } }.getOrNull() ?: return null
        val cookies = validateAndSanitizeNeteaseCookies(prefs[CookieKeys.NETEASE_COOKIE_JSON].orEmpty().toCookieMap()).sanitizedCookies
        if (cookies.isEmpty()) return null
        runCatching { runBlocking { context.cookieDataStore.edit { it.remove(CookieKeys.NETEASE_COOKIE_JSON) } } }
        return NeteaseAuthBundle(cookies, 0L)
    }

    private fun defaultAccountName(index: Int) = "网易云账号 $index"

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences = runCatching { createEncryptedPrefs() }.getOrElse {
        NPLogger.w("NERI-CookieRepo", "Failed to open NetEase secure prefs, recreating", it)
        context.deleteSharedPreferences(NETEASE_AUTH_PREFS)
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            NETEASE_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

private fun NeteaseAccountsState.toJson(): String = JSONObject().apply {
    put("version", 2)
    put("accounts", JSONArray().apply {
        accounts.forEach { account ->
            put(JSONObject().apply {
                put("id", account.id)
                put("name", account.name)
                put("cookies", account.cookies.toJsonObject())
                put("savedAt", account.savedAt)
            })
        }
    })
    put("primaryAccountId", primaryAccountId)
    put("playHistoryAccountId", playHistoryAccountId)
    put("streamingAccountId", streamingAccountId)
}.toString()

private fun JSONObject.toNeteaseAccountsState(): NeteaseAccountsState = runCatching {
    val array = optJSONArray("accounts") ?: JSONArray()
    val accounts = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val cookies = item.optJSONObject("cookies").toStringMap()
            if (validateAndSanitizeNeteaseCookies(cookies).isAccepted) {
                add(NeteaseAccount(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = item.optString("name").ifBlank { "网易云账号 ${index + 1}" },
                    cookies = validateAndSanitizeNeteaseCookies(cookies).sanitizedCookies,
                    savedAt = item.optLong("savedAt", 0L)
                ))
            }
        }
    }
    NeteaseAccountsState(
        accounts = accounts,
        primaryAccountId = optString("primaryAccountId").takeIf { it.isNotBlank() },
        playHistoryAccountId = optString("playHistoryAccountId").takeIf { it.isNotBlank() },
        streamingAccountId = optString("streamingAccountId").takeIf { it.isNotBlank() }
    ).normalized()
}.getOrDefault(NeteaseAccountsState())

private fun Map<String, String>.toJsonObject() = JSONObject().apply { forEach { (key, value) -> put(key, value) } }
private fun JSONObject?.toStringMap(): Map<String, String> = linkedMapOf<String, String>().apply {
    val source = this@toStringMap ?: return@apply
    val keys = source.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, source.optString(key, ""))
    }
}
private fun String.toCookieMap(): Map<String, String> = runCatching { JSONObject(this).toStringMap() }.getOrDefault(emptyMap())
