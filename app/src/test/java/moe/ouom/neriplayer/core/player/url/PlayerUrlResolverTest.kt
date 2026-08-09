package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUrlResolverTest {

    @Test
    fun buildYouTubeOfflineCacheAudioInfo_usesPreferredQualityAndSource() {
        val audioInfo = buildYouTubeOfflineCacheAudioInfo("high") { it.toString() }

        assertEquals(PlaybackAudioSource.YOUTUBE_MUSIC, audioInfo.source)
        assertEquals("high", audioInfo.qualityKey)
        assertEquals(R.string.settings_audio_quality_high.toString(), audioInfo.qualityLabel)
        assertEquals(4, audioInfo.qualityOptions.size)
        assertTrue(audioInfo.mimeType.isNullOrBlank())
    }

    @Test
    fun buildYouTubeOfflineCacheAudioInfo_fallsBackWhenPreferredQualityBlank() {
        val audioInfo = buildYouTubeOfflineCacheAudioInfo("   ") { it.toString() }

        assertEquals("high", audioInfo.qualityKey)
        assertEquals(R.string.settings_audio_quality_high.toString(), audioInfo.qualityLabel)
    }

    @Test
    fun buildNeteaseOfflineCacheAudioInfo_usesPreferredQualityAndSource() {
        val audioInfo = buildNeteaseOfflineCacheAudioInfo("lossless") { it.toString() }

        assertEquals(PlaybackAudioSource.NETEASE, audioInfo.source)
        assertEquals("lossless", audioInfo.qualityKey)
        assertEquals(R.string.quality_lossless.toString(), audioInfo.qualityLabel)
        assertEquals(8, audioInfo.qualityOptions.size)
        assertTrue(audioInfo.mimeType.isNullOrBlank())
    }

    @Test
    fun buildNeteaseOfflineCacheAudioInfo_fallsBackWhenPreferredQualityBlank() {
        val audioInfo = buildNeteaseOfflineCacheAudioInfo("   ") { it.toString() }

        assertEquals("exhigh", audioInfo.qualityKey)
        assertEquals(R.string.quality_very_high.toString(), audioInfo.qualityLabel)
    }

    @Test
    fun lxSourcesOnly_appliesOnlyToRemoteNeteaseSongs() {
        assertTrue(
            shouldEnforceLxSourceOnly(
                lxSourcesOnlyEnabled = true,
                isLocalSong = false,
                isYouTubeMusicSong = false,
                isBiliSong = false
            )
        )
        assertTrue(
            listOf(
                shouldEnforceLxSourceOnly(true, true, false, false),
                shouldEnforceLxSourceOnly(true, false, true, false),
                shouldEnforceLxSourceOnly(true, false, false, true),
                shouldEnforceLxSourceOnly(false, false, false, false)
            ).none { it }
        )
    }

    @Test
    fun lxSourcesOnly_usesFreshCacheKeyForAvailabilityTests() {
        val normalKey = buildLxPlaybackCacheKey("source", 42L, "320k", false, cacheNonce = 7L)
        val testKey = buildLxPlaybackCacheKey("source", 42L, "320k", true, cacheNonce = 7L)

        assertEquals("lx-${"source".hashCode()}-42-320k", normalKey)
        assertEquals("$normalKey-test-7", testKey)
    }
}
