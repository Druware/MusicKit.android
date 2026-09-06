package com.druware.musickit

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * [MusicKitPlayer.indexOf] pins reference identity, not structural equality: [MusicSong] is a data
 * class, and a queue is allowed to hold two instances that compare equal — the same track twice, or
 * two instances parsed separately that land on identical fields. [NowPlayingResolver] hands back the
 * very instance out of the queue, so `indexOf` has to find *that* instance, not merely an equal one
 * — otherwise it would always answer with the first match, pointing [MusicKitPlayer.currentIndex] (and
 * a later rotation or skip, which re-applies the queue from that index onward) at the wrong tail.
 */
class MusicKitPlayerTest {

    private fun song() = MusicSong(
        id = "1441164829",
        title = "In My Life",
        artistName = "The Beatles",
        albumName = "Rubber Soul",
        duration = 146.seconds,
        catalogId = "1441164829",
        libraryId = null,
    )

    @Test
    fun `indexOf finds the actual instance among equal duplicates, not the first equal one`() {
        val first = song()
        val second = song()
        check(first == second) { "fixture bug: the two instances must compare equal" }
        check(first !== second) { "fixture bug: the two instances must not be identical" }

        val queue = listOf(first, second)

        assertEquals(1, MusicKitPlayer.indexOf(queue, second))
        assertEquals(0, MusicKitPlayer.indexOf(queue, first))
    }

    @Test
    fun `indexOf reports no match for an equal instance that is not actually in the queue`() {
        val queued = song()
        val notQueued = song()
        check(queued == notQueued) { "fixture bug: the two instances must compare equal" }

        assertEquals(-1, MusicKitPlayer.indexOf(listOf(queued), notQueued))
    }
}
