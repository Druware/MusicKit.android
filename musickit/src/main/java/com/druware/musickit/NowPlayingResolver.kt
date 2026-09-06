package com.druware.musickit

/**
 * Matches the item MusicKit says is playing back to the track that was queued.
 *
 * **Why this is not a dictionary lookup.** Apple Music can report a catalog identifier for a track
 * that was queued by library identifier, and the other way round, so the obvious
 * `queue.first { it.id == nowPlaying.id }` answers null often enough to make a now-playing panel
 * flicker. The native app works around it the same way, in three tiers: the identifier as given,
 * then the catalog/library cross-reference out of the item's play parameters, then the title and
 * artist.
 *
 * Each tier is scanned across the whole queue before the next is tried, so the first match at the
 * lowest-numbered tier wins, in queue order within that tier.
 */
internal object NowPlayingResolver {

    /**
     * Finds the queued song the now-playing item refers to.
     *
     * @param queue the songs that were queued, in order.
     * @param nowPlaying what MusicKit says is playing, or null when nothing is.
     * @return the matching song — the very instance out of [queue] — or null when no tier matched.
     */
    fun resolve(queue: List<MusicSong>, nowPlaying: NowPlayingItem?): MusicSong? {
        if (nowPlaying == null || queue.isEmpty()) {
            return null
        }

        // 1. The identifier exactly as MusicKit gave it.
        queue.firstOrNull { same(it.id, nowPlaying.id) }?.let { return it }

        // 2. The cross-reference: either side's catalog or library identifier naming the other.
        queue.firstOrNull { song ->
            same(song.catalogId, nowPlaying.catalogId) ||
                same(song.libraryId, nowPlaying.libraryId) ||
                same(song.catalogId, nowPlaying.id) ||
                same(song.libraryId, nowPlaying.id) ||
                same(song.id, nowPlaying.catalogId) ||
                same(song.id, nowPlaying.libraryId)
        }?.let { return it }

        // 3. Title and artist, which is all that is left when the identifiers do not meet at all.
        queue.firstOrNull { song ->
            same(song.title, nowPlaying.title, ignoreCase = true) &&
                (nowPlaying.artistName == null ||
                    same(song.artistName, nowPlaying.artistName, ignoreCase = true))
        }?.let { return it }

        return null
    }

    /** Two blank identifiers are not "the same identifier", so an empty side never matches. */
    private fun same(left: String?, right: String?, ignoreCase: Boolean = false): Boolean =
        !left.isNullOrEmpty() && !right.isNullOrEmpty() && left.equals(right, ignoreCase)
}
