package moe.ouom.neriplayer.data.lx

data class LxCustomSource(
    val id: String,
    val name: String,
    val description: String,
    val script: String,
    val enabled: Boolean = true,
    val author: String = "",
    val homepage: String = "",
    val version: String = ""
)

data class LxCustomSourceInfo(
    val name: String,
    val description: String,
    val author: String = "",
    val homepage: String = "",
    val version: String = ""
)

internal object LxCustomSourceParser {
    private const val MAX_NAME_LENGTH = 24
    private const val MAX_DESCRIPTION_LENGTH = 200
    private const val MAX_AUTHOR_LENGTH = 56
    private const val MAX_HOMEPAGE_LENGTH = 1_024
    private const val MAX_VERSION_LENGTH = 36
    const val MAX_SCRIPT_LENGTH = 2 * 1024 * 1024

    private val headerRegex = Regex("^\\s*/\\*([\\s\\S]*?)\\*/")
    private val fieldRegex = Regex("^\\s*\\*?\\s*@([A-Za-z]+)\\s+(.+?)\\s*$")

    fun parse(script: String): LxCustomSourceInfo {
        require(script.isNotBlank()) { "LX source script is empty" }
        require(script.length <= MAX_SCRIPT_LENGTH) { "LX source script is too large" }
        val header = headerRegex.find(script)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("Invalid LX source: metadata header is missing")
        val fields = buildMap {
            header.lineSequence().forEach { line ->
                val match = fieldRegex.matchEntire(line) ?: return@forEach
                put(match.groupValues[1].lowercase(), match.groupValues[2].trim())
            }
        }
        val name = fields["name"].orEmpty().take(MAX_NAME_LENGTH).ifBlank { "LX source" }
        return LxCustomSourceInfo(
            name = name,
            description = fields["description"].orEmpty().take(MAX_DESCRIPTION_LENGTH),
            author = fields["author"].orEmpty().take(MAX_AUTHOR_LENGTH),
            homepage = fields["homepage"].orEmpty().take(MAX_HOMEPAGE_LENGTH),
            version = fields["version"].orEmpty().take(MAX_VERSION_LENGTH)
        )
    }
}
