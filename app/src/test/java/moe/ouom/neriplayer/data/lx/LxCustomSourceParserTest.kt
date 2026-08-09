package moe.ouom.neriplayer.data.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LxCustomSourceParserTest {

    @Test
    fun parse_readsLxMetadataHeader() {
        val source = LxCustomSourceParser.parse(
            """
            /*
             * @name Demo source
             * @description Used by tests
             * @version 2.1.0
             * @author Neri
             * @homepage https://example.com/source
             */
            lx.on(lx.EVENT_NAMES.request, async () => 'https://example.com/audio.mp3')
            """.trimIndent()
        )

        assertEquals("Demo source", source.name)
        assertEquals("Used by tests", source.description)
        assertEquals("2.1.0", source.version)
        assertEquals("Neri", source.author)
        assertEquals("https://example.com/source", source.homepage)
    }

    @Test
    fun parse_rejectsScriptWithoutMetadataHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            LxCustomSourceParser.parse("lx.send(lx.EVENT_NAMES.inited, {})")
        }
    }
}
