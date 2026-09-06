package com.druware.musickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * When the developer token is replaced. Rotation stops playback and clears the queue, so every
 * answer here is really "is this a seam?".
 */
class TokenRotationPolicyTest {

    private val now: Instant = Instant.parse("2026-09-05T12:00:00Z")
    private val leadTime = 10.minutes
    private val policy = TokenRotationPolicy(leadTime)

    private val everyTrigger = RotationTrigger.entries

    private fun Instant.plusMinutes(minutes: Long): Instant = plusSeconds(minutes * 60)

    @Test
    fun `a token outside the lead time is never rotated`() {
        for (trigger in everyTrigger) {
            assertFalse(
                "$trigger rotated a token that is not due",
                policy.shouldRotate(trigger, MusicPlaybackState.None, now.plusMinutes(11), now),
            )
        }
    }

    @Test
    fun `a host holding no token yet rotates nothing`() {
        for (trigger in everyTrigger) {
            assertFalse(
                "$trigger rotated with no token held",
                policy.shouldRotate(trigger, MusicPlaybackState.None, null, now),
            )
        }
    }

    @Test
    fun `setting a queue inside the lead time always rotates`() {
        val states = listOf(
            MusicPlaybackState.None,
            MusicPlaybackState.Playing,
            MusicPlaybackState.Paused,
            MusicPlaybackState.Stopped,
        )

        for (state in states) {
            assertTrue(
                "SetQueue did not rotate in state $state",
                policy.shouldRotate(RotationTrigger.SetQueue, state, now.plusMinutes(9), now),
            )
        }
    }

    @Test
    fun `skipping inside the lead time always rotates`() {
        val states = listOf(
            MusicPlaybackState.None,
            MusicPlaybackState.Playing,
            MusicPlaybackState.Paused,
        )

        for (state in states) {
            assertTrue(
                "Skip did not rotate in state $state",
                policy.shouldRotate(RotationTrigger.Skip, state, now.plusMinutes(9), now),
            )
        }
    }

    @Test
    fun `playing rotates from anything but a track already playing`() {
        val cases = listOf(
            MusicPlaybackState.None to true,
            MusicPlaybackState.Stopped to true,
            MusicPlaybackState.Completed to true,
            MusicPlaybackState.Paused to true,
            MusicPlaybackState.Playing to false,
        )

        for ((state, expected) in cases) {
            assertEquals(
                "Play in state $state",
                expected,
                policy.shouldRotate(RotationTrigger.Play, state, now.plusMinutes(9), now),
            )
        }
    }

    @Test
    fun `the idle timer only acts on a host doing nothing`() {
        val cases = listOf(
            MusicPlaybackState.None to true,
            MusicPlaybackState.Stopped to true,
            MusicPlaybackState.Completed to true,
            MusicPlaybackState.Playing to false,
            MusicPlaybackState.Paused to false,
            MusicPlaybackState.Loading to false,
            MusicPlaybackState.Waiting to false,
        )

        for ((state, expected) in cases) {
            assertEquals(
                "IdleTimer in state $state",
                expected,
                policy.shouldRotate(RotationTrigger.IdleTimer, state, now.plusMinutes(9), now),
            )
        }
    }

    @Test
    fun `a token that has already expired is inside the lead time`() {
        assertTrue(
            policy.shouldRotate(
                RotationTrigger.IdleTimer,
                MusicPlaybackState.Stopped,
                now.plusMinutes(-1),
                now,
            ),
        )
    }

    @Test
    fun `the boundary is exclusive so exactly the lead time does not rotate`() {
        assertFalse(
            policy.shouldRotate(
                RotationTrigger.SetQueue,
                MusicPlaybackState.None,
                now.plusSeconds(leadTime.inWholeSeconds),
                now,
            ),
        )
    }
}
