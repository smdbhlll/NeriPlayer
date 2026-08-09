package moe.ouom.neriplayer.core.api.lx

import moe.ouom.neriplayer.data.lx.LxCustomSource

data class LxSourceCapabilities(
    val sources: Map<String, LxSourceCapability>
) {
    fun qualityCandidates(source: String, preferredQuality: String): List<String> {
        val supported = sources[source]?.qualities.orEmpty()
        if (supported.isEmpty()) return emptyList()
        val preferred = mapNeriQualityToLx(preferredQuality)
        val ordered = when (preferred) {
            "flac24bit" -> listOf("flac24bit", "flac", "320k", "128k")
            "flac" -> listOf("flac", "320k", "128k")
            "320k" -> listOf("320k", "128k")
            else -> listOf("128k")
        }
        return (ordered.filter { it in supported } + supported.asReversed()).distinct()
    }
}

data class LxSourceCapability(
    val actions: Set<String>,
    val qualities: List<String>
)

data class LxResolvedAudio(
    val url: String,
    val sourceId: String,
    val sourceName: String,
    val quality: String
)

sealed interface LxSourceRuntimeStatus {
    data object Idle : LxSourceRuntimeStatus
    data object Initializing : LxSourceRuntimeStatus
    data class Ready(val capabilities: LxSourceCapabilities) : LxSourceRuntimeStatus
    data class Failed(val message: String) : LxSourceRuntimeStatus
}

data class LxSourceAttempt(
    val source: LxCustomSource,
    val error: String
)

internal fun mapNeriQualityToLx(quality: String): String = when (quality.trim().lowercase()) {
    "jymaster", "sky", "jyeffect", "hires" -> "flac24bit"
    "lossless" -> "flac"
    "exhigh", "higher" -> "320k"
    else -> "128k"
}
