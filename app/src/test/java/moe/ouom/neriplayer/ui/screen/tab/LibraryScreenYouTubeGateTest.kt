package moe.ouom.neriplayer.ui.screen.tab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScreenYouTubeGateTest {

    @Test
    fun `library tabs exclude YouTube when disabled`() {
        val tabs = libraryTabDisplayOrder(
            isInternational = true,
            youtubeEnabled = false
        )

        assertFalse(tabs.contains(LibraryTab.YTMUSIC))
        assertTrue(tabs.contains(LibraryTab.NETEASE))
        assertTrue(tabs.contains(LibraryTab.BILI))
    }

    @Test
    fun `library tabs honor saved order and permanently exclude QQ Music`() {
        val tabs = libraryTabDisplayOrder(
            isInternational = false,
            youtubeEnabled = true,
            persistedOrder = "BILI,QQMUSIC,LOCAL,BILI,UNKNOWN"
        )

        assertEquals(LibraryTab.BILI, tabs.first())
        assertFalse(tabs.contains(LibraryTab.QQMUSIC))
        assertEquals(tabs.size, tabs.distinct().size)
        assertTrue(tabs.containsAll(listOf(LibraryTab.LOCAL, LibraryTab.FAVORITE, LibraryTab.NETEASE)))
    }

    @Test
    fun `default library tab falls back to first visible tab`() {
        val available = listOf(LibraryTab.BILI, LibraryTab.LOCAL)

        assertEquals(LibraryTab.BILI, resolveLibraryDefaultTab("YTMUSIC", available))
        assertEquals(LibraryTab.LOCAL, resolveLibraryDefaultTab("LOCAL", available))
        assertEquals(LibraryTab.BILI, resolveLibraryDefaultTab("QQMUSIC", available))
    }
}
