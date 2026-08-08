package moe.ouom.neriplayer.core.api.lx

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.lx.LxCustomSourceRepository
import moe.ouom.neriplayer.data.model.SongItem
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class LxMusicSourceManager(
    context: Context,
    private val repository: LxCustomSourceRepository,
    private val client: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val runtimes = ConcurrentHashMap<String, LxScriptRuntime>()
    private val _statuses = MutableStateFlow<Map<String, LxSourceRuntimeStatus>>(emptyMap())

    val statuses: StateFlow<Map<String, LxSourceRuntimeStatus>> = _statuses.asStateFlow()

    suspend fun resolveNetease(song: SongItem, preferredQuality: String): LxResolvedAudio? {
        val sources = repository.sources.value.filter { it.enabled }
        for (source in sources) {
            val runtime = runtimes.getOrPut(source.id) {
                LxScriptRuntime(appContext, source, client) { status ->
                    _statuses.value = _statuses.value + (source.id to status)
                }
            }
            try {
                val capabilities = runtime.initialize()
                val qualities = capabilities.qualityCandidates("wy", preferredQuality)
                for (quality in qualities) {
                    val url = try {
                        runtime.resolveMusicUrl(
                            platform = "wy",
                            quality = quality,
                            musicInfo = buildNeteaseMusicInfo(song)
                        )
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        NPLogger.w(
                            TAG,
                            "LX source ${source.name} failed for quality=$quality: ${error.message}"
                        )
                        continue
                    }
                    if (url.isNotBlank()) {
                        return LxResolvedAudio(
                            url = url,
                            sourceId = source.id,
                            sourceName = source.name,
                            quality = quality
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                NPLogger.w(TAG, "LX source ${source.name} unavailable: ${error.message}")
            }
        }
        return null
    }

    fun invalidate(sourceId: String) {
        runtimes.remove(sourceId)?.close()
        _statuses.value = _statuses.value - sourceId
    }

    fun invalidateAll() {
        runtimes.values.forEach(LxScriptRuntime::close)
        runtimes.clear()
        _statuses.value = emptyMap()
    }

    private fun buildNeteaseMusicInfo(song: SongItem): JSONObject {
        val durationSeconds = (song.durationMs.coerceAtLeast(0L) / 1_000L).toInt()
        val interval = "%02d:%02d".format(durationSeconds / 60, durationSeconds % 60)
        val qualityArray = JSONArray().apply {
            listOf("128k", "320k", "flac", "flac24bit").forEach { quality ->
                put(JSONObject().put("type", quality).put("size", JSONObject.NULL))
            }
        }
        val qualityMap = JSONObject().apply {
            listOf("128k", "320k", "flac", "flac24bit").forEach { quality ->
                put(quality, JSONObject().put("size", JSONObject.NULL))
            }
        }
        val songId = song.id.toString()
        return JSONObject()
            .put("id", "wy_$songId")
            .put("name", song.originalName ?: song.name)
            .put("singer", song.originalArtist ?: song.artist)
            .put("source", "wy")
            .put("interval", interval)
            .put("songmid", songId)
            .put("albumName", song.album.removePrefix("Netease").trim().ifBlank { song.album })
            .put("albumId", song.albumId)
            .put("img", song.coverUrl)
            .put("types", qualityArray)
            .put("_types", qualityMap)
            .put("typeUrl", JSONObject())
            .put(
                "meta",
                JSONObject()
                    .put("songId", songId)
                    .put("albumName", song.album.removePrefix("Netease").trim().ifBlank { song.album })
                    .put("albumId", song.albumId)
                    .put("picUrl", song.coverUrl)
                    .put("qualitys", qualityArray)
                    .put("_qualitys", qualityMap)
            )
    }

    private companion object {
        const val TAG = "NERI-LXSource"
    }
}
