package moe.ouom.neriplayer.data.history

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.biliCidOrNull
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.auth.bili.BiliCookieRepository
import moe.ouom.neriplayer.data.auth.netease.NeteaseCookieRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class PlatformPlaybackHistoryReporter(
    private val neteaseHistoryClient: NeteaseClient,
    private val neteaseCookieRepository: NeteaseCookieRepository,
    private val biliCookieRepository: BiliCookieRepository,
    private val httpClient: OkHttpClient
) {
    private val reportedSessions = ConcurrentHashMap.newKeySet<String>()

    suspend fun report(
        song: SongItem,
        playbackSessionId: Long,
        playedMs: Long,
        completed: Boolean = false
    ) {
        if (!completed && playedMs < MIN_PLAYED_MS && playedMs * 2 < song.durationMs) return
        val sessionKey = "${song.stableKey()}:$playbackSessionId"
        if (!reportedSessions.add(sessionKey)) return
        val sent = runCatching {
            when {
                song.channelId.equals("netease", ignoreCase = true) && song.id > 0L -> {
                    reportNetease(song, playedMs)
                }
                song.channelId.equals("bilibili", ignoreCase = true) -> reportBilibili(song, playedMs)
                else -> false
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "Platform play-history report failed for ${song.stableKey()}", error)
        }.getOrDefault(false)
        if (!sent) reportedSessions.remove(sessionKey) else trimReportedSessions()
    }

    private suspend fun reportNetease(song: SongItem, playedMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (neteaseCookieRepository.getPlayHistoryCookiesOnce()["MUSIC_U"].isNullOrBlank()) {
            return@withContext false
        }
        val log = JSONObject().apply {
            put("action", "play")
            put("json", JSONObject().apply {
                put("download", 0)
                put("end", "playend")
                put("id", song.id)
                put("sourceId", song.playlistContextId.orEmpty())
                put("time", (playedMs / 1000L).coerceAtLeast(1L))
                put("type", "song")
                put("wifi", 0)
            })
        }
        neteaseHistoryClient.callWeApi(
            "/feedback/weblog",
            mapOf<String, Any>("logs" to JSONArray().put(log).toString()),
            usePersistedCookies = true
        )
        true
    }

    private suspend fun reportBilibili(song: SongItem, playedMs: Long): Boolean = withContext(Dispatchers.IO) {
        val cookies = biliCookieRepository.getPlayHistoryCookiesOnce()
        val csrf = cookies["bili_jct"].orEmpty()
        val aid = song.audioId?.toLongOrNull()?.takeIf { it > 0L } ?: song.id.takeIf { it > 0L }
        val cid = song.biliCidOrNull()?.takeIf { it > 0L }
        if (cookies["SESSDATA"].isNullOrBlank() || csrf.isBlank() || aid == null || cid == null) {
            return@withContext false
        }
        val body = FormBody.Builder()
            .add("aid", aid.toString())
            .add("cid", cid.toString())
            .add("played_time", (playedMs / 1000L).coerceAtLeast(1L).toString())
            .add("type", "3")
            .add("csrf", csrf)
            .build()
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/click-interface/web/heartbeat")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Referer", "https://www.bilibili.com")
            .header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Bilibili heartbeat HTTP ${response.code}")
            val code = JSONObject(response.body.string()).optInt("code", -1)
            if (code != 0) error("Bilibili heartbeat code=$code")
        }
        true
    }

    private fun trimReportedSessions() {
        if (reportedSessions.size <= MAX_REPORTED_SESSIONS) return
        val iterator = reportedSessions.iterator()
        repeat(REPORTED_SESSIONS_TRIM_COUNT) {
            if (iterator.hasNext()) reportedSessions.remove(iterator.next())
        }
    }

    companion object {
        private const val TAG = "PlatformHistory"
        private const val MIN_PLAYED_MS = 30_000L
        private const val MAX_REPORTED_SESSIONS = 512
        private const val REPORTED_SESSIONS_TRIM_COUNT = 256
        private const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"
    }
}
