@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.auth.bili

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

private const val BILI_AUTH_PREFS = "bili_auth_secure_prefs"
private const val KEY_BILI_AUTH_BUNDLE = "bili_auth_bundle"
private val Context.biliCookieStore by preferencesDataStore("bili_auth_store")

object BiliCookieKeys {
    val COOKIE_JSON = stringPreferencesKey("bili_cookie_json")
}

private val BILI_LOGIN_COOKIE_KEYS = listOf("SESSDATA", "DedeUserID", "bili_jct")

data class BiliAuthBundle(
    val cookies: Map<String, String> = emptyMap(),
    val savedAt: Long = 0L
) {
    fun hasLoginCookies(): Boolean = !cookies["SESSDATA"].isNullOrBlank()
    fun normalized(savedAt: Long = this.savedAt): BiliAuthBundle = copy(
        cookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() }),
        savedAt = savedAt
    )
    fun toJson(): String = JSONObject().apply {
        put("cookies", cookies.toJsonObject())
        put("savedAt", savedAt)
    }.toString()

    companion object {
        fun fromJson(json: String): BiliAuthBundle = runCatching {
            val root = JSONObject(json)
            BiliAuthBundle(root.optJSONObject("cookies").toStringMap(), root.optLong("savedAt", 0L))
                .normalized(root.optLong("savedAt", 0L))
        }.getOrDefault(BiliAuthBundle())
    }
}

data class BiliAccount(
    val id: String,
    val name: String,
    val cookies: Map<String, String>,
    val savedAt: Long
) {
    fun toAuthBundle() = BiliAuthBundle(cookies, savedAt)
}

data class BiliAccountsState(
    val accounts: List<BiliAccount> = emptyList(),
    val primaryAccountId: String? = null,
    val playHistoryAccountId: String? = null,
    val streamingAccountId: String? = null
) {
    fun selectedId(purpose: PlatformAccountPurpose): String? = when (purpose) {
        PlatformAccountPurpose.PRIMARY -> primaryAccountId
        PlatformAccountPurpose.PLAY_HISTORY -> playHistoryAccountId ?: primaryAccountId
        PlatformAccountPurpose.STREAMING -> streamingAccountId ?: primaryAccountId
    }

    fun account(purpose: PlatformAccountPurpose): BiliAccount? {
        val id = selectedId(purpose)
        return accounts.firstOrNull { it.id == id } ?: accounts.firstOrNull()
    }

    fun normalized(): BiliAccountsState {
        val ids = accounts.mapTo(linkedSetOf()) { it.id }
        val primary = primaryAccountId?.takeIf(ids::contains) ?: accounts.firstOrNull()?.id
        return copy(
            primaryAccountId = primary,
            playHistoryAccountId = playHistoryAccountId?.takeIf(ids::contains) ?: primary,
            streamingAccountId = streamingAccountId?.takeIf(ids::contains) ?: primary
        )
    }
}

internal fun evaluateBiliAuthHealth(
    bundle: BiliAuthBundle,
    now: Long = System.currentTimeMillis()
): SavedCookieAuthHealth {
    val normalized = bundle.normalized(bundle.savedAt)
    val loginCookieKeys = BILI_LOGIN_COOKIE_KEYS.filter { !normalized.cookies[it].isNullOrBlank() }
    if (!normalized.hasLoginCookies()) {
        return SavedCookieAuthHealth(
            state = SavedCookieAuthState.Missing,
            savedAt = normalized.savedAt,
            checkedAt = now,
            loginCookieKeys = loginCookieKeys
        )
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

class BiliCookieRepository(private val context: Context) {
    private var encryptedPrefs: SharedPreferences = openEncryptedPrefsWithRecovery()
    private val _accountsFlow = MutableStateFlow(loadAccountsState())
    private val _cookieFlow = MutableStateFlow(cookiesFor(PlatformAccountPurpose.PRIMARY))
    private val _playHistoryCookieFlow = MutableStateFlow(cookiesFor(PlatformAccountPurpose.PLAY_HISTORY))
    private val _streamingCookieFlow = MutableStateFlow(cookiesFor(PlatformAccountPurpose.STREAMING))
    private val _authHealthFlow = MutableStateFlow(evaluateBiliAuthHealth(primaryBundle()))
    private var addAccountOnNextSave = false

    val accountsFlow: StateFlow<BiliAccountsState> = _accountsFlow.asStateFlow()
    val cookieFlow: StateFlow<Map<String, String>> = _cookieFlow.asStateFlow()
    val playHistoryCookieFlow: StateFlow<Map<String, String>> = _playHistoryCookieFlow.asStateFlow()
    val streamingCookieFlow: StateFlow<Map<String, String>> = _streamingCookieFlow.asStateFlow()
    val authHealthFlow: StateFlow<SavedCookieAuthHealth> = _authHealthFlow.asStateFlow()

    fun getCookiesOnce(): Map<String, String> = _cookieFlow.value
    fun getPlayHistoryCookiesOnce(): Map<String, String> = _playHistoryCookieFlow.value
    fun getStreamingCookiesOnce(): Map<String, String> = _streamingCookieFlow.value
    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value
    fun getAuthHealth(now: Long = System.currentTimeMillis()) = evaluateBiliAuthHealth(primaryBundle(), now)

    @Synchronized
    fun beginAddAccount() {
        addAccountOnNextSave = true
    }

    @Synchronized
    fun cancelAddAccount() {
        addAccountOnNextSave = false
    }

    @Synchronized
    fun saveCookies(cookies: Map<String, String>, savedAt: Long = System.currentTimeMillis()) {
        if (cookies["SESSDATA"].isNullOrBlank()) return
        val normalizedCookies = LinkedHashMap(cookies.filterKeys { it.isNotBlank() })
        val current = _accountsFlow.value
        val updatedAccounts = current.accounts.toMutableList()
        val targetIndex = if (addAccountOnNextSave) -1 else updatedAccounts.indexOfFirst { it.id == current.primaryAccountId }
        val account = BiliAccount(
            id = if (targetIndex >= 0) updatedAccounts[targetIndex].id else UUID.randomUUID().toString(),
            name = if (targetIndex >= 0) updatedAccounts[targetIndex].name else defaultAccountName(updatedAccounts.size + 1),
            cookies = normalizedCookies,
            savedAt = savedAt
        )
        if (targetIndex >= 0) updatedAccounts[targetIndex] = account else updatedAccounts += account
        addAccountOnNextSave = false
        persistAndPublish(current.copy(
            accounts = updatedAccounts,
            primaryAccountId = current.primaryAccountId ?: account.id
        ).normalized())
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
        persistAndPublish(BiliAccountsState())
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateBiliAuthHealth(primaryBundle(), now)
    }

    private fun primaryBundle() = _accountsFlow.value.account(PlatformAccountPurpose.PRIMARY)?.toAuthBundle()
        ?: BiliAuthBundle()
    private fun cookiesFor(purpose: PlatformAccountPurpose) = _accountsFlow.value.account(purpose)?.cookies.orEmpty()

    private fun persistAndPublish(state: BiliAccountsState) {
        val normalized = state.normalized()
        encryptedPrefs.edit { putString(KEY_BILI_AUTH_BUNDLE, normalized.toJson()) }
        _accountsFlow.value = normalized
        _cookieFlow.value = normalized.account(PlatformAccountPurpose.PRIMARY)?.cookies.orEmpty()
        _playHistoryCookieFlow.value = normalized.account(PlatformAccountPurpose.PLAY_HISTORY)?.cookies.orEmpty()
        _streamingCookieFlow.value = normalized.account(PlatformAccountPurpose.STREAMING)?.cookies.orEmpty()
        _authHealthFlow.value = evaluateBiliAuthHealth(primaryBundle())
    }

    private fun loadAccountsState(): BiliAccountsState {
        val raw = encryptedPrefs.getString(KEY_BILI_AUTH_BUNDLE, null).orEmpty()
        if (raw.isNotBlank()) {
            val root = runCatching { JSONObject(raw) }.getOrNull()
            if (root?.has("accounts") == true) return root.toBiliAccountsState()
            val legacy = BiliAuthBundle.fromJson(raw)
            if (legacy.hasLoginCookies()) return singleAccountState(legacy)
        }
        return migrateLegacyCookies()?.let(::singleAccountState) ?: BiliAccountsState()
    }

    private fun singleAccountState(bundle: BiliAuthBundle): BiliAccountsState {
        val id = UUID.randomUUID().toString()
        return BiliAccountsState(
            accounts = listOf(BiliAccount(id, defaultAccountName(1), bundle.cookies, bundle.savedAt)),
            primaryAccountId = id,
            playHistoryAccountId = id,
            streamingAccountId = id
        ).also { encryptedPrefs.edit { putString(KEY_BILI_AUTH_BUNDLE, it.toJson()) } }
    }

    private fun migrateLegacyCookies(): BiliAuthBundle? {
        val prefs = runCatching { runBlocking { context.biliCookieStore.data.first() } }.getOrNull() ?: return null
        val cookies = prefs[BiliCookieKeys.COOKIE_JSON].orEmpty().toCookieMap()
        if (cookies["SESSDATA"].isNullOrBlank()) return null
        runCatching { runBlocking { context.biliCookieStore.edit { it.remove(BiliCookieKeys.COOKIE_JSON) } } }
        return BiliAuthBundle(cookies, 0L)
    }

    private fun defaultAccountName(index: Int) = "哔哩哔哩账号 $index"

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences = runCatching { createEncryptedPrefs() }.getOrElse {
        NPLogger.w("NERI-BiliCookieRepo", "Failed to open Bili secure prefs, recreating", it)
        context.deleteSharedPreferences(BILI_AUTH_PREFS)
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            BILI_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

private fun BiliAccountsState.toJson(): String = JSONObject().apply {
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

private fun JSONObject.toBiliAccountsState(): BiliAccountsState = runCatching {
    val array = optJSONArray("accounts") ?: JSONArray()
    val accounts = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val cookies = item.optJSONObject("cookies").toStringMap()
            if (!cookies["SESSDATA"].isNullOrBlank()) {
                add(BiliAccount(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = item.optString("name").ifBlank { "哔哩哔哩账号 ${index + 1}" },
                    cookies = cookies,
                    savedAt = item.optLong("savedAt", 0L)
                ))
            }
        }
    }
    BiliAccountsState(
        accounts = accounts,
        primaryAccountId = optString("primaryAccountId").takeIf { it.isNotBlank() },
        playHistoryAccountId = optString("playHistoryAccountId").takeIf { it.isNotBlank() },
        streamingAccountId = optString("streamingAccountId").takeIf { it.isNotBlank() }
    ).normalized()
}.getOrDefault(BiliAccountsState())

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
