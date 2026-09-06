package com.druware.musickit

/**
 * Whether the user has let this application use their Apple Music account.
 *
 * Mirrors MusicKit's own `MusicAuthorization.Status`.
 */
enum class MusicAuthorizationStatus {
    /** Nobody has been asked yet. */
    NotDetermined,

    /** A Music User Token is held, so library and playback calls will work. */
    Authorized,

    /** The user has been asked and there is no Music User Token. */
    NotAuthorized,
}
