package com.druware.musickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MusicKit's `PlaybackStates` names to [MusicPlaybackState]. A name this library does not know must
 * read as unrecognised — so the host can log it and fall back to [MusicPlaybackState.None] — rather
 * than be guessed at.
 */
class PlaybackStateNameTest {

    @Test
    fun `every name musickit uses maps`() {
        val cases = listOf(
            "none" to MusicPlaybackState.None,
            "loading" to MusicPlaybackState.Loading,
            "playing" to MusicPlaybackState.Playing,
            "paused" to MusicPlaybackState.Paused,
            "stopped" to MusicPlaybackState.Stopped,
            "ended" to MusicPlaybackState.Ended,
            "seeking" to MusicPlaybackState.Seeking,
            "waiting" to MusicPlaybackState.Waiting,
            "stalled" to MusicPlaybackState.Stalled,
            "completed" to MusicPlaybackState.Completed,
            // Case-insensitive, and trimmed.
            "Playing" to MusicPlaybackState.Playing,
            " playing " to MusicPlaybackState.Playing,
        )

        for ((name, expected) in cases) {
            assertEquals("'$name' did not map as expected", expected, PlaybackStateNames.tryParse(name))
        }
    }

    @Test
    fun `an unknown name is reported as unknown and reads as none`() {
        // "2" is the load-bearing case: a numeric string must NOT parse as an enum ordinal, or a
        // MusicKit change of shape would become a plausible-looking wrong answer.
        val unknown = listOf(null, "", "   ", "buffering", "2")

        for (name in unknown) {
            val state = PlaybackStateNames.tryParse(name)
            assertNull("'$name' should not have been recognised", state)
            assertEquals(MusicPlaybackState.None, state ?: MusicPlaybackState.None)
        }
    }

    @Test
    fun `the states the spike observed after play all map`() {
        // The real sequence from the spike log: playing, waiting, loading, playing.
        for (name in listOf("playing", "waiting", "loading", "playing")) {
            assertNotNull(name, PlaybackStateNames.tryParse(name))
        }
    }
}
