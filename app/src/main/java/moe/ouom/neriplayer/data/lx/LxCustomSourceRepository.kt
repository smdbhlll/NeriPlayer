package moe.ouom.neriplayer.data.lx

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class LxCustomSourceRepository(context: Context) {
    private val storage = AtomicFile(File(context.filesDir, FILE_NAME))
    private val mutex = Mutex()
    private val _sources = MutableStateFlow(loadSources())

    val sources: StateFlow<List<LxCustomSource>> = _sources.asStateFlow()

    suspend fun importScript(script: String): LxCustomSource = withContext(Dispatchers.IO) {
        val normalizedScript = script.removePrefix("\uFEFF")
        val info = LxCustomSourceParser.parse(normalizedScript)
        val digest = normalizedScript.sha256()
        mutex.withLock {
            check(_sources.value.none { it.script.sha256() == digest }) {
                "The same LX source script has already been imported"
            }
            val source = LxCustomSource(
                id = "lx_${UUID.randomUUID()}",
                name = info.name,
                description = info.description,
                script = normalizedScript,
                author = info.author,
                homepage = info.homepage,
                version = info.version
            )
            updateLocked(_sources.value + source)
            source
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutate { sources ->
        sources.map { source -> if (source.id == id) source.copy(enabled = enabled) else source }
    }

    suspend fun remove(id: String) = mutate { sources -> sources.filterNot { it.id == id } }

    suspend fun move(id: String, offset: Int) = mutate { sources ->
        val currentIndex = sources.indexOfFirst { it.id == id }
        if (currentIndex < 0) return@mutate sources
        val targetIndex = (currentIndex + offset).coerceIn(sources.indices)
        if (targetIndex == currentIndex) return@mutate sources
        sources.toMutableList().apply {
            add(targetIndex, removeAt(currentIndex))
        }
    }

    private suspend fun mutate(transform: (List<LxCustomSource>) -> List<LxCustomSource>) {
        withContext(Dispatchers.IO) {
            mutex.withLock { updateLocked(transform(_sources.value)) }
        }
    }

    private fun updateLocked(sources: List<LxCustomSource>) {
        writeSources(sources)
        _sources.value = sources
    }

    private fun loadSources(): List<LxCustomSource> {
        if (!storage.baseFile.exists()) return emptyList()
        return runCatching {
            storage.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                val array = JSONArray(reader.readText())
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            LxCustomSource(
                                id = item.getString("id"),
                                name = item.getString("name"),
                                description = item.optString("description"),
                                script = String(
                                    Base64.decode(item.getString("script"), Base64.DEFAULT),
                                    Charsets.UTF_8
                                ),
                                enabled = item.optBoolean("enabled", true),
                                author = item.optString("author"),
                                homepage = item.optString("homepage"),
                                version = item.optString("version")
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeSources(sources: List<LxCustomSource>) {
        val output = JSONArray()
        sources.forEach { source ->
            output.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("description", source.description)
                    .put(
                        "script",
                        Base64.encodeToString(source.script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    )
                    .put("enabled", source.enabled)
                    .put("author", source.author)
                    .put("homepage", source.homepage)
                    .put("version", source.version)
            )
        }
        var stream = storage.startWrite()
        try {
            stream.write(output.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            storage.finishWrite(stream)
        } catch (error: Throwable) {
            storage.failWrite(stream)
            throw error
        }
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val FILE_NAME = "lx_custom_sources.json"
    }
}
