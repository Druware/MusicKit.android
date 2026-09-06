package com.druware.musickit

import kotlin.time.Duration

/**
 * One playlist, from the user's library or from the catalog.
 *
 * @param id Apple's identifier. Library playlists start with `p.`.
 * @param name the playlist's name; empty rather than null when Apple gave none.
 * @param trackCount how many tracks it holds, when Apple said.
 * @param isLibrary true when this is the user's own copy rather than a catalog playlist.
 */
data class MusicPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val isLibrary: Boolean,
)

/**
 * One song, from the catalog or from the user's library.
 *
 * `MusicKitPlayer` tracks the playing track by **identity**, not by value: [NowPlayingResolver]
 * hands back one of the instances already in the queue, and the queue is allowed to hold two songs
 * that compare equal. Compare these with `===` wherever the identity is what matters.
 *
 * @param id Apple's identifier for this representation. Library songs start with `i.`.
 * @param title the track title; empty rather than null when Apple gave none.
 * @param artistName the performing artist; empty rather than null when Apple gave none.
 * @param albumName the album, when Apple gave one.
 * @param duration track length, when Apple gave one.
 * @param catalogId the same song's catalog identifier, from `attributes.playParams.catalogId`. A
 *   library song that also exists in the catalog carries both, which is what lets the now-playing
 *   item be matched back to a queued track when Apple names it by the other identifier.
 * @param libraryId the same song's library identifier, when it is in the user's library.
 * @param artworkUrlTemplate the album artwork URL exactly as Apple wrote it, from
 *   `attributes.artwork.url`, when the item has any. It is a **template**, not a URL: it carries
 *   literal `{w}` and `{h}` placeholders that the client replaces with the pixel size it wants, and
 *   fetching it unsubstituted is a 404. Keep it in the model because it is what Apple returned, and
 *   render it with [artworkUrl] at the point of use.
 */
data class MusicSong(
    val id: String,
    val title: String,
    val artistName: String,
    val albumName: String?,
    val duration: Duration?,
    val catalogId: String?,
    val libraryId: String?,
    val artworkUrlTemplate: String? = null,
)

/**
 * This song's artwork URL, rendered at a concrete pixel size and so safe to hand an image loader.
 *
 * @param width the width in pixels to ask Apple for.
 * @param height the height in pixels; square, matching the width, unless one is given.
 * @return the URL with `{w}` and `{h}` substituted, or null when the song has no artwork. A
 *   template that carries no placeholders comes back unchanged.
 */
fun MusicSong.artworkUrl(width: Int, height: Int = width): String? =
    artworkUrlTemplate
        ?.replace("{w}", width.toString())
        ?.replace("{h}", height.toString())

/**
 * What MusicKit says is playing right now.
 *
 * @param id the identifier MusicKit reports, which need not be the one the track was queued by.
 * @param title the track title, when MusicKit gave one.
 * @param artistName the performing artist, when MusicKit gave one.
 * @param catalogId the catalog identifier from the item's play parameters, when it has one.
 * @param libraryId the library identifier from the item's play parameters, when it has one.
 */
data class NowPlayingItem(
    val id: String,
    val title: String?,
    val artistName: String?,
    val catalogId: String?,
    val libraryId: String?,
)
