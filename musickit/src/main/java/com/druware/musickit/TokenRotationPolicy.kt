package com.druware.musickit

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** What prompted a rotation decision. */
internal enum class RotationTrigger {
    /** A new queue is about to be set. */
    SetQueue,

    /** Playback is about to be started or resumed. */
    Play,

    /** The next queued item is about to be started. */
    Skip,

    /** The idle timer ticked, with nobody asking for anything. */
    IdleTimer,
}

/**
 * Decides when the developer token is replaced.
 *
 * **Rotation is destructive, which is the whole problem.** MusicKit JS exposes `developerToken` as
 * a getter only, so the only way to hand it a new one is a second `MusicKit.configure` — and that
 * stops playback and clears the queue while keeping the sign-in. So rotation is done at the seams:
 * before a queue is set, before playback starts from a standstill, and before a skip, where the
 * queue is going to be re-applied anyway. Never under a playing track.
 *
 * The idle timer covers the case the seams miss: a host that sat still for an hour would otherwise
 * begin its next set with a token that expires mid-song.
 *
 * @param leadTime how far ahead of expiry a token counts as due for replacement.
 */
internal class TokenRotationPolicy(private val leadTime: Duration) {

    /**
     * Decides whether to replace the developer token before doing something.
     *
     * @param trigger what is about to happen.
     * @param state what the player is doing right now.
     * @param expiresAt when the held token expires, or null when none is held.
     * @param now the current instant, passed in so the decision is testable.
     * @return true to fetch a fresh token and reconfigure first.
     */
    fun shouldRotate(
        trigger: RotationTrigger,
        state: MusicPlaybackState,
        expiresAt: Instant?,
        now: Instant,
    ): Boolean {
        // The boundary is exclusive: a token exactly leadTime away is not yet due.
        if (expiresAt == null ||
            java.time.Duration.between(now, expiresAt).toKotlinDuration() >= leadTime
        ) {
            return false
        }

        return when (trigger) {
            RotationTrigger.SetQueue -> true
            RotationTrigger.Skip -> true

            // Starting from a standstill is a seam; cutting off a track that is already playing is
            // not, so a play that is really a no-op on a playing instance rotates nothing. Resuming
            // from paused does rotate, and does lose the position — the alternative is a queue that
            // dies partway through the next track.
            RotationTrigger.Play -> state != MusicPlaybackState.Playing

            // The timer only ever acts on a host that is doing nothing.
            RotationTrigger.IdleTimer -> state == MusicPlaybackState.None ||
                state == MusicPlaybackState.Stopped ||
                state == MusicPlaybackState.Completed
        }
    }
}
