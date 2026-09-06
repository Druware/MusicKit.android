package com.druware.musickit

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Transport control and now-playing tracking, mirroring what a native app does with
 * `ApplicationMusicPlayer.shared`.
 *
 * Obtained from [MusicKitHost.player]; never constructed directly.
 *
 * The queue this holds is the one that was asked for, not the one MusicKit holds. They come apart
 * after a token rotation, which clears MusicKit's queue and has the remainder re-applied to it —
 * and after any skip, because MusicKit is then a track further along than a naive index would say.
 * [currentSong] is resolved from the now-playing item rather than from an index, which is what keeps
 * the two honest.
 *
 * @param host the host whose rotation policy the transport defers to.
 * @param bridge the page to send transport calls to.
 * @param audioManager used to tell the rest of the system that music is playing.
 * @param diagnose takes a diagnostic line.
 */
class MusicKitPlayer internal constructor(
    private val host: MusicKitHost,
    private val bridge: MusicKitBridge,
    private val audioManager: AudioManager?,
    private val diagnose: (String) -> Unit,
) {

    /** Guards the compound updates; each flow is individually safe, the set of them is not. */
    private val sync = Any()

    private val queueState = MutableStateFlow<List<MusicSong>>(emptyList())
    private val currentIndexState = MutableStateFlow(-1)
    private val playbackState = MutableStateFlow(MusicPlaybackState.None)
    private val nowPlayingState = MutableStateFlow<NowPlayingItem?>(null)
    private val currentSongState = MutableStateFlow<MusicSong?>(null)

    private val playbackErrors = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    /** The songs that were queued, in order. */
    val queue: StateFlow<List<MusicSong>> = queueState.asStateFlow()

    /**
     * The index into [queue] of the song believed to be playing, or `-1`.
     *
     * Observable rather than a plain property, and not only for convenience: [currentSong] is a
     * [StateFlow] and so compares by value, while the queue may hold two songs that compare equal.
     * Moving from one of those to the other changes this and not that, so a host that needs to know
     * *which* entry is playing watches this one.
     */
    val currentIndex: StateFlow<Int> = currentIndexState.asStateFlow()

    /** What the player is doing. */
    val state: StateFlow<MusicPlaybackState> = playbackState.asStateFlow()

    /** The item MusicKit says is playing, as MusicKit named it. */
    val nowPlaying: StateFlow<NowPlayingItem?> = nowPlayingState.asStateFlow()

    /** The queued song [nowPlaying] resolves to, or null when nothing matched. */
    val currentSong: StateFlow<MusicSong?> = currentSongState.asStateFlow()

    /** Emits whatever MusicKit says when playback fails. Has no replay: a stale error helps nobody. */
    val playbackError: SharedFlow<String> = playbackErrors.asSharedFlow()

    /**
     * Replaces the queue and rewinds to its first item.
     *
     * @param songs the songs to queue, in order.
     * @return how many of them MusicKit put in its queue, which a host can compare with how many it
     *   asked for.
     * @throws MusicKitException MusicKit accepted none of the songs.
     */
    suspend fun setQueue(songs: List<MusicSong>): Int {
        host.rotateIfNeeded(RotationTrigger.SetQueue)

        val queued = songs.toList()
        synchronized(sync) {
            queueState.value = queued
            currentIndexState.value = if (queued.isEmpty()) -1 else 0
        }

        return applyQueue(queued)
    }

    /** Starts or resumes playback. */
    suspend fun play() {
        // A rotation wipes MusicKit's queue, so what was asked for has to go back in before the
        // play can mean anything.
        if (host.rotateIfNeeded(RotationTrigger.Play)) {
            reapplyFrom(maxOf(currentIndexState.value, 0))
        }

        requestAudioFocus()
        bridge.play()
    }

    /** Holds playback at the current position. */
    suspend fun pause() {
        // No rotation check: pausing is not a seam, and rotating here would lose the position.
        bridge.pause()
        abandonAudioFocus()
    }

    /** Stops playback and forgets the position. */
    suspend fun stop() {
        bridge.stop()
        abandonAudioFocus()
    }

    /**
     * Skips to the next queued song.
     *
     * When the token is due for rotation this is not a skip at all: the token is replaced, the queue
     * from the next song onwards is re-applied, and playback started. The audible result is the same.
     */
    suspend fun skipToNext() {
        if (!host.rotateIfNeeded(RotationTrigger.Skip)) {
            bridge.skipToNext()
            return
        }

        val next = currentIndexState.value + 1
        if (next >= queueState.value.size) {
            bridge.stop()
            abandonAudioFocus()
            return
        }

        reapplyFrom(next)
        requestAudioFocus()
        bridge.play()
    }

    /**
     * Records a state change reported by the page.
     *
     * @param state the new state.
     */
    internal fun onStateChanged(state: MusicPlaybackState) {
        playbackState.value = state
    }

    /**
     * Records a now-playing change reported by the page and re-resolves the current song.
     *
     * @param item what MusicKit says is playing, or null when nothing is.
     */
    internal fun onNowPlayingChanged(item: NowPlayingItem?) {
        synchronized(sync) {
            nowPlayingState.value = item

            val resolved = NowPlayingResolver.resolve(queueState.value, item)
            currentSongState.value = resolved

            if (resolved != null) {
                val index = indexOf(queueState.value, resolved)
                if (index >= 0) {
                    currentIndexState.value = index
                }
            }
        }
    }

    /**
     * Reports a playback error from the page.
     *
     * @param message what MusicKit said.
     */
    internal fun onPlaybackError(message: String) {
        playbackErrors.tryEmit(message)
    }

    /** Re-applies the queue from one index onwards, after a rotation cleared MusicKit's own. */
    private suspend fun reapplyFrom(index: Int) {
        val remaining = synchronized(sync) {
            if (queueState.value.isEmpty()) {
                return
            }

            currentIndexState.value = index
            queueState.value.drop(index)
        }

        applyQueue(remaining)
    }

    private suspend fun applyQueue(songs: List<MusicSong>): Int {
        if (songs.isEmpty()) {
            bridge.stop()
            return 0
        }

        // A library song goes in by its own "i." identifier, not by its catalog one. The plain
        // setQueue({songs}) form resolves the whole list against Apple's catalog in one fan-out of
        // batched requests, and that is all-or-nothing: one identifier the catalog will not resolve,
        // or one batch Apple rate-limits, rejects every track in the queue. The item descriptor
        // carries each track's play parameters instead, so MusicKit needs no request at all.
        val ids = songs.map { it.libraryId ?: it.catalogId ?: it.id }

        val count = bridge.setQueue(ids)
        if (count == 0) {
            throw MusicKitException(
                "Apple Music would not queue any of those tracks. They may not be available in " +
                    "this storefront.",
                "EMPTY_QUEUE",
            )
        }

        return count
    }

    /**
     * Tells the rest of the system that music is starting.
     *
     * The audio itself belongs to the WebView's renderer, not to this process's audio session, so
     * this request is advisory: it is what makes other apps duck or pause. **Focus loss is reported
     * and not acted on** — deciding to pause and when to resume belongs with the media session and
     * foreground service this library deliberately does not yet have, and guessing at it here would
     * be worse than saying so.
     */
    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (focusRequest != null) {
            return
        }

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> diagnose("audio focus changed: $change") }
            .build()

        focusRequest = request

        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            diagnose("audio focus was refused; another app holds it")
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        focusRequest = null
        manager.abandonAudioFocusRequest(request)
    }

    internal companion object {
        /**
         * Identity, not equality: [NowPlayingResolver] hands back one of the instances already in
         * the queue, and a queue is allowed to hold two songs that compare equal. A structural
         * search would answer with the first of them every time.
         */
        @VisibleForTesting
        fun indexOf(queue: List<MusicSong>, song: MusicSong): Int {
            for (index in queue.indices) {
                if (queue[index] === song) {
                    return index
                }
            }

            return -1
        }
    }
}
