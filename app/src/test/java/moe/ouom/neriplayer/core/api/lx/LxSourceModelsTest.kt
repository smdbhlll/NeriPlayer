package moe.ouom.neriplayer.core.api.lx

import org.junit.Assert.assertEquals
import org.junit.Test

class LxSourceModelsTest {

    @Test
    fun qualityCandidates_startsAtPreferredQualityAndFallsBack() {
        val capabilities = LxSourceCapabilities(
            sources = mapOf(
                "wy" to LxSourceCapability(
                    actions = setOf("musicUrl"),
                    qualities = listOf("128k", "320k", "flac", "flac24bit")
                )
            )
        )

        assertEquals(
            listOf("flac", "320k", "128k", "flac24bit"),
            capabilities.qualityCandidates("wy", "lossless")
        )
        assertEquals(
            listOf("320k", "128k", "flac24bit", "flac"),
            capabilities.qualityCandidates("wy", "exhigh")
        )
    }

    @Test
    fun mapNeriQualityToLx_mapsNeteaseQualityNames() {
        assertEquals("flac24bit", mapNeriQualityToLx("jymaster"))
        assertEquals("flac", mapNeriQualityToLx("lossless"))
        assertEquals("320k", mapNeriQualityToLx("exhigh"))
        assertEquals("128k", mapNeriQualityToLx("standard"))
    }
}
