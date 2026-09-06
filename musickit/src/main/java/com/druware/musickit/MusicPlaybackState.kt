package com.druware.musickit

/**
 * What the player is doing, as MusicKit reports it.
 *
 * One member per name in MusicKit JS's `MusicKit.PlaybackStates`. A run of
 * `playing -> waiting -> loading -> playing` at the start of a track is normal and not a fault.
 *
 * The declaration order matches the C# enum, but nothing may depend on the ordinals: MusicKit's own
 * `PlaybackStates` numbering is not contiguous, and this library never sees a number anyway — the
 * page turns the ordinal into a name with `MusicKit.PlaybackStates[value]` before it posts it.
 */
enum class MusicPlaybackState {
    /** Nothing is loaded, or MusicKit sent a state name this library does not know. */
    None,

    /** The item is being fetched. */
    Loading,

    /** Audio is coming out. */
    Playing,

    /** Playback is held at the current position. */
    Paused,

    /** Playback was stopped and the position discarded. */
    Stopped,

    /** The item ran to its end. */
    Ended,

    /** The position is being moved. */
    Seeking,

    /** Waiting for the next item to become playable. */
    Waiting,

    /** Playback stalled, usually on the network. */
    Stalled,

    /** The whole queue ran to its end. */
    Completed,
}

/** Turns MusicKit's playback state names into [MusicPlaybackState]. */
internal object PlaybackStateNames {
    /**
     * Maps one name as MusicKit spells it.
     *
     * @param name the value of `MusicKit.PlaybackStates[n]`, e.g. "playing".
     * @return the matching state, or null when the name was not recognised, so the caller can log it
     *   and fall back to [MusicPlaybackState.None].
     */
    fun tryParse(name: String?): MusicPlaybackState? {
        if (name.isNullOrBlank()) {
            return null
        }

        // A generic enum parse would also accept "3" and every other numeric string, which would
        // turn a MusicKit change of shape into a plausible-looking wrong answer rather than a
        // logged one. So the ten names are matched literally.
        return when (name.trim().lowercase()) {
            "none" -> MusicPlaybackState.None
            "loading" -> MusicPlaybackState.Loading
            "playing" -> MusicPlaybackState.Playing
            "paused" -> MusicPlaybackState.Paused
            "stopped" -> MusicPlaybackState.Stopped
            "ended" -> MusicPlaybackState.Ended
            "seeking" -> MusicPlaybackState.Seeking
            "waiting" -> MusicPlaybackState.Waiting
            "stalled" -> MusicPlaybackState.Stalled
            "completed" -> MusicPlaybackState.Completed
            else -> null
        }
    }
}
