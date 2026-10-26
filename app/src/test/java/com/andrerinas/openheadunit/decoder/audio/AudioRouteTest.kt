package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteTest {

    @Test
    fun `stream ids are not usage routes and pass through unchanged`() {
        for (stream in 0..31) {
            assertFalse("stream=$stream", AudioRoute.isUsage(stream))
            assertNull(AudioRoute.usage(stream))
            assertEquals(stream, AudioRoute.legacyStream(stream))
        }
    }

    @Test
    fun `usage routes round trip`() {
        for (usage in AudioRoute.USAGES) {
            val route = AudioRoute.ofUsage(usage.id)
            assertTrue(AudioRoute.isUsage(route))
            assertEquals(usage.id, AudioRoute.usageOf(route))
            assertEquals(usage, AudioRoute.usage(route))
        }
    }

    @Test
    fun `navigation guidance falls back to the music stream like AOSP`() {
        assertEquals(3, AudioRoute.legacyStream(AudioRoute.ofUsage(12)))
    }

    @Test
    fun `usages are only playable from the api that defined them`() {
        val assistant = AudioRoute.ofUsage(16)
        assertFalse(AudioRoute.isPlayableUsage(assistant, 25))
        assertTrue(AudioRoute.isPlayableUsage(assistant, 26))
        assertTrue(AudioRoute.isPlayableUsage(AudioRoute.ofUsage(12), 21))
    }

    @Test
    fun `unknown and system usages are never playable`() {
        // UNKNOWN, CALL_ASSISTANT and the AAOS system usages EMERGENCY / ANNOUNCEMENT.
        for (usage in listOf(0, 17, 1000, 1003)) {
            assertFalse("usage=$usage", AudioRoute.isPlayableUsage(AudioRoute.ofUsage(usage), 36))
        }
    }

    @Test
    fun `default streams select the usage they already play as`() {
        assertEquals(AudioRoute.ofUsage(1), AudioRoute.usageRouteForStream(3))
        assertEquals(AudioRoute.ofUsage(2), AudioRoute.usageRouteForStream(0))
        assertEquals(AudioRoute.ofUsage(5), AudioRoute.usageRouteForStream(5))
        assertNull(AudioRoute.usageRouteForStream(10))
    }

    @Test
    fun `usage ids are unique`() {
        assertEquals(AudioRoute.USAGES.size, AudioRoute.USAGES.map { it.id }.toSet().size)
    }
}
