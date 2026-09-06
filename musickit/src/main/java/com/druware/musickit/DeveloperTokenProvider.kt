package com.druware.musickit

/**
 * Supplies the Apple Media Services developer token MusicKit is configured with.
 *
 * A token lives about an hour, so a long-running host asks for one repeatedly rather than once.
 * Implementations are expected to cache and to be safe to call from several coroutines at once.
 */
interface DeveloperTokenProvider {

    /**
     * Gets a usable developer token, minting a fresh one when the held one is near expiry.
     *
     * Cancelling the calling coroutine cancels the fetch, if one is needed.
     *
     * @param forceRefresh true to mint a new token even when the held one is still good.
     * @return a usable developer token and its expiry.
     * @throws MusicKitException no token could be obtained.
     */
    suspend fun getToken(forceRefresh: Boolean = false): DeveloperToken

    /** Drops the held token, so the next [getToken] mints a fresh one. */
    fun invalidate()
}
