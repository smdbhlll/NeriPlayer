package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.auth.bili.BiliAccount
import moe.ouom.neriplayer.data.auth.bili.BiliAccountsState
import moe.ouom.neriplayer.data.auth.common.PlatformAccountPurpose
import moe.ouom.neriplayer.data.auth.netease.NeteaseAccount
import moe.ouom.neriplayer.data.auth.netease.NeteaseAccountsState
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiAccountStateTest {
    @Test
    fun `bili missing purpose assignment falls back to primary account`() {
        val primary = BiliAccount("primary", "Primary", mapOf("SESSDATA" to "a"), 1L)
        val secondary = BiliAccount("secondary", "Secondary", mapOf("SESSDATA" to "b"), 2L)
        val state = BiliAccountsState(
            accounts = listOf(primary, secondary),
            primaryAccountId = primary.id,
            playHistoryAccountId = "deleted",
            streamingAccountId = secondary.id
        ).normalized()

        assertEquals(primary.id, state.selectedId(PlatformAccountPurpose.PLAY_HISTORY))
        assertEquals(secondary.cookies, state.account(PlatformAccountPurpose.STREAMING)?.cookies)
    }

    @Test
    fun `netease deleting primary promotes first remaining account for every invalid purpose`() {
        val remaining = NeteaseAccount("remaining", "Remaining", mapOf("MUSIC_U" to "token"), 2L)
        val state = NeteaseAccountsState(
            accounts = listOf(remaining),
            primaryAccountId = "deleted",
            playHistoryAccountId = "deleted",
            streamingAccountId = "deleted"
        ).normalized()

        assertEquals(remaining.id, state.primaryAccountId)
        assertEquals(remaining.id, state.playHistoryAccountId)
        assertEquals(remaining.id, state.streamingAccountId)
    }
}
