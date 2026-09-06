package com.druware.musickit

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The three tiers. Apple Music names the now-playing item by whichever identifier it feels like, so
 * each tier exists because the one above it answered null in a real session.
 *
 * Every assertion is `assertSame`, not `assertEquals`: the resolver must hand back the very
 * instance that is in the queue, because the player tracks the playing track by identity.
 */
class NowPlayingResolverTest {

    private val libraryTrack = MusicSong(
        id = "i.gaXXXXXpo",
        title = "Here Comes the Sun",
        artistName = "The Beatles",
        albumName = "Abbey Road",
        duration = 185.seconds,
        catalogId = "1441164589",
        libraryId = "i.gaXXXXXpo",
    )

    private val catalogTrack = MusicSong(
        id = "1441164829",
        title = "In My Life",
        artistName = "The Beatles",
        albumName = "Rubber Soul",
        duration = 146.seconds,
        catalogId = "1441164829",
        libraryId = null,
    )

    private val queue = listOf(libraryTrack, catalogTrack)

    @Test
    fun `tier one matches the identifier as given`() {
        assertSame(catalogTrack, NowPlayingResolver.resolve(queue, item("1441164829")))
    }

    @Test
    fun `tier two matches a library track reported by its catalog id`() {
        // Queued as i.gaXXXXXpo, reported as 1441164589 — the case that made this resolver necessary.
        assertSame(
            libraryTrack,
            NowPlayingResolver.resolve(queue, item("1441164589", catalogId = "1441164589")),
        )
    }

    @Test
    fun `tier two matches a library track that was queued by its catalog id`() {
        // What queueing by catalogId leaves behind: the queued song's own id is still the library
        // one, and the now-playing item names the catalog identifier and carries nothing else.
        assertSame(libraryTrack, NowPlayingResolver.resolve(queue, item("1441164589")))
    }

    @Test
    fun `tier two matches a catalog track reported by a library id`() {
        assertSame(
            catalogTrack,
            NowPlayingResolver.resolve(
                queue,
                item("i.somethingelse", catalogId = "1441164829", libraryId = "i.somethingelse"),
            ),
        )
    }

    @Test
    fun `tier three matches on title and artist when no identifier meets`() {
        assertSame(
            catalogTrack,
            NowPlayingResolver.resolve(
                queue,
                NowPlayingItem("something-else-entirely", "in my life", "the beatles", null, null),
            ),
        )
    }

    @Test
    fun `tier three matches on title alone when no artist was reported`() {
        assertSame(
            libraryTrack,
            NowPlayingResolver.resolve(
                queue,
                NowPlayingItem("unknown", "Here Comes the Sun", null, null, null),
            ),
        )
    }

    @Test
    fun `a title that matches but an artist that does not is not a match`() {
        assertNull(
            NowPlayingResolver.resolve(
                queue,
                NowPlayingItem("unknown", "In My Life", "Someone Else", null, null),
            ),
        )
    }

    @Test
    fun `nothing playing resolves to nothing`() {
        assertNull(NowPlayingResolver.resolve(queue, null))
    }

    @Test
    fun `an empty queue resolves to nothing`() {
        assertNull(NowPlayingResolver.resolve(emptyList(), item("1441164829")))
    }

    @Test
    fun `an item that matches nothing resolves to nothing`() {
        assertNull(NowPlayingResolver.resolve(queue, item("9999999999")))
    }

    @Test
    fun `empty identifiers never match each other`() {
        val blanks = listOf(
            MusicSong("a", "A", "B", null, null, catalogId = null, libraryId = null),
        )

        assertNull(
            NowPlayingResolver.resolve(blanks, NowPlayingItem("b", null, null, null, null)),
        )
    }

    private fun item(id: String, catalogId: String? = null, libraryId: String? = null) =
        NowPlayingItem(id, null, null, catalogId, libraryId)
}
