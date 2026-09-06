package com.druware.musickit

import android.media.AudioManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds

/**
 * Apple Music for an Android application: MusicKit JS driven inside a WebView nobody ever sees.
 *
 * **What this is.** The surface a native MusicKit app uses — authorization, library playlists and
 * their tracks, catalog search, playlist creation, and a player — over MusicKit for the Web.
 * Everything Apple insists happens in a browser (sign-in, DRM, playback) happens in the hidden page;
 * everything an Android application wants to hold (models, state, transport) is here.
 *
 * **Lifetime.** The page is a 1x1 child of the activity's content view, so a host lives no longer
 * than the activity it was created against. Call [close] from the activity's own teardown.
 *
 * **Threading.** Every member may be called from any coroutine; WebView work is marshalled to the
 * main thread internally.
 *
 * **Credentials.** The developer token is held in memory and sent to the page and nowhere else. The
 * Music User Token never crosses the bridge at all: `authorize()` resolves to it inside the page,
 * and only a boolean comes back. Neither is ever put in [diagnostic].
 */
class MusicKitHost private constructor(
    private val options: MusicKitOptions,
    private val webView: HostedWebView,
    private val bridge: MusicKitBridge,
    private val diagnostics: MutableSharedFlow<String>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = TokenRotationPolicy(options.rotationLeadTime)

    /** Serialises rotations, so two callers arriving together do not each replace the token. */
    private val rotationGate = Mutex()

    private val authorization = MutableStateFlow(MusicAuthorizationStatus.NotDetermined)

    @Volatile
    private var closed = false

    /**
     * Human-readable diagnostic lines.
     *
     * Never carries a token, and never a URI beyond its scheme and host. The most recent
     * [MAX_BUFFERED_LINES] lines are replayed to each new collector, because nobody can collect
     * before [create] returns and startup is where the interesting failures are.
     */
    val diagnostic: SharedFlow<String> = diagnostics.asSharedFlow()

    /** Whether the user has let this application use their Apple Music account. */
    val authorizationStatus: StateFlow<MusicAuthorizationStatus> = authorization.asStateFlow()

    /** The storefront catalog requests are made against, e.g. `us`. */
    @Volatile
    var storefrontId: String? = null
        private set

    /** When the developer token in use expires, or null before the first one is fetched. */
    @Volatile
    var developerTokenExpiresAt: Instant? = null
        private set

    /** The transport. */
    val player: MusicKitPlayer = MusicKitPlayer(
        this,
        bridge,
        options.activity.getSystemService(AudioManager::class.java),
        ::diagnose,
    )

    /**
     * Opens Apple's sign-in popup and waits for the user to finish with it.
     *
     * @return the authorization status the user left behind.
     */
    suspend fun requestAuthorization(): MusicAuthorizationStatus {
        try {
            setAuthorization(bridge.authorize(), initial = false)
        } finally {
            // Whichever way the call went, the popup's work is done. onCloseWindow is the popup's
            // own signal and is not guaranteed to fire, so this is the one that always does.
            webView.dismissAuthPopup()
        }

        return authorization.value
    }

    /** Drops the Music User Token, signing the user out. */
    suspend fun unauthorize() {
        setAuthorization(bridge.unauthorize(), initial = false)

        // Belt and braces. MusicKit has just dropped its own token; clearing the origin's storage
        // as well is what guarantees nothing of this sign-in survives to be picked up when a
        // different Apple ID signs in on the same device.
        webView.clearOriginStorage()
    }

    /**
     * Lists every playlist in the user's library.
     *
     * @return the playlists, in Apple's order.
     * @throws MusicKitException Apple refused the request.
     */
    suspend fun getLibraryPlaylists(): List<MusicPlaylist> {
        val playlists = mutableListOf<MusicPlaylist>()
        forEachPage("/v1/me/library/playlists", "load your library playlists") { page ->
            playlists.addAll(AppleJson.readPlaylists(page))
        }

        return playlists
    }

    /**
     * Fetches one library playlist by its identifier.
     *
     * @param id the library playlist identifier, which starts with `p.`.
     * @return the playlist, or null when the library does not have it.
     * @throws MusicKitException Apple refused the request for a reason other than "not found".
     */
    suspend fun getLibraryPlaylist(id: String): MusicPlaylist? {
        require(id.isNotBlank()) { "A playlist identifier is required." }

        val answer = bridge.apiGet("/v1/me/library/playlists/${Uri.encode(id)}", null)
        if (answer.status == 404) {
            return null
        }

        return AppleJson.readPlaylists(requireBody(answer, "find that playlist")).firstOrNull()
    }

    /**
     * Lists the tracks of a library playlist.
     *
     * @param playlistId the library playlist identifier.
     * @return the tracks, in playlist order.
     * @throws MusicKitException Apple refused the request.
     */
    suspend fun getLibraryPlaylistTracks(playlistId: String): List<MusicSong> {
        require(playlistId.isNotBlank()) { "A playlist identifier is required." }

        val songs = mutableListOf<MusicSong>()
        forEachPage(
            "/v1/me/library/playlists/${Uri.encode(playlistId)}/tracks",
            "load that playlist's tracks",
        ) { page ->
            songs.addAll(AppleJson.readSongs(page))
        }

        return songs
    }

    /**
     * Searches the Apple Music catalog for songs.
     *
     * Needs the developer token only, so it works before the user has signed in.
     *
     * @param term what to search for.
     * @param limit how many songs to ask for; Apple's own maximum is 25.
     * @return the songs found, in Apple's order.
     * @throws MusicKitException Apple refused the request.
     */
    suspend fun searchCatalogSongs(term: String, limit: Int): List<MusicSong> {
        require(term.isNotBlank()) { "A search term is required." }
        require(limit > 0) { "A limit of at least one song is required." }

        val answer = bridge.apiGet(
            "/v1/catalog/${storefrontId ?: "us"}/search",
            mapOf("term" to term, "types" to "songs", "limit" to limit.toString()),
        )

        return AppleJson.readSearchSongs(requireBody(answer, "search the catalogue"))
    }

    /**
     * Creates a playlist in the user's library from catalog songs.
     *
     * `music.api.music` offers no POST of its own, so the page makes this one with a plain `fetch`
     * from inside itself — where the Music User Token already is, and so where it stays.
     *
     * @param name the name to give the playlist.
     * @param catalogSongIds the catalog identifiers to put in it, in order.
     * @return the new playlist's library identifier.
     * @throws MusicKitException Apple refused the request, or answered without an identifier.
     */
    suspend fun createLibraryPlaylist(name: String, catalogSongIds: List<String>): String {
        require(name.isNotBlank()) { "A playlist name is required." }

        val request = buildJsonObject {
            put("attributes", buildJsonObject { put("name", name) })
            put(
                "relationships",
                buildJsonObject {
                    put(
                        "tracks",
                        buildJsonObject {
                            put(
                                "data",
                                buildJsonArray {
                                    catalogSongIds.forEach { id ->
                                        add(
                                            buildJsonObject {
                                                put("id", id)
                                                put("type", "songs")
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }

        val answer = bridge.apiPost("/v1/me/library/playlists", request.toString())
        diagnose("create playlist: HTTP ${answer.status ?: "?"} via ${answer.via ?: "?"}")

        val created = AppleJson.readPlaylists(requireBody(answer, "create that playlist")).firstOrNull()
        return created?.id
            ?: throw MusicKitException(
                "The playlist was created but Apple Music did not say what its identifier is.",
                "NO_PLAYLIST_ID",
            )
    }

    /**
     * Asks the page whether this device's WebView can start Widevine EME at all.
     *
     * Not in the desktop library, and here because Android needs it: some OEM WebView builds carry
     * no Widevine CDM, and without this the only symptom is that playback never starts. A host can
     * use it to say so plainly rather than appearing to hang.
     *
     * @return true when the page could obtain Widevine key-system access.
     */
    suspend fun probeWidevine(): Boolean {
        val probe = bridge.probeWidevine()
        diagnose(
            if (probe.supported) {
                "Widevine is available (${probe.keySystem ?: "unnamed key system"})"
            } else {
                "Widevine is not available: ${probe.error ?: "no reason given"}"
            },
        )

        return probe.supported
    }

    /** Closes the page and the WebView behind it. Idempotent. */
    suspend fun close() {
        if (closed) {
            return
        }

        closed = true
        scope.cancel()

        bridge.onPageEvent = null
        bridge.onLog = null
        bridge.failAll("The MusicKit host was disposed.")

        webView.onMessage = null
        webView.onRendererGone = null
        webView.onAuthPopupFailed = null
        webView.close()
    }

    /**
     * Runs one expression in the page and returns its result as JSON.
     *
     * Internal, and there for one reason: MusicKit JS is Apple's moving target, so the instrumented
     * test has to be able to ask the live page what it actually does rather than assume. A host has
     * no business here; every supported operation has a typed method above.
     *
     * @param script the expression to evaluate.
     */
    internal suspend fun evaluate(script: String): String = webView.evaluate(script)

    /**
     * Replaces the developer token, whether or not one is due.
     *
     * Internal because a host has no reason to ask for this: rotation is decided by
     * [TokenRotationPolicy]. Exposed at all so a test can exercise the path without waiting an hour.
     */
    internal suspend fun rotateNow() {
        rotationGate.withLock { rotateCore() }
    }

    /**
     * Replaces the developer token when the policy says the moment has come.
     *
     * @param trigger what is about to happen.
     * @return true when a rotation happened, so the caller knows MusicKit's queue is now empty.
     */
    internal suspend fun rotateIfNeeded(trigger: RotationTrigger): Boolean {
        if (!policy.shouldRotate(trigger, player.state.value, developerTokenExpiresAt, Instant.now())) {
            return false
        }

        return rotationGate.withLock {
            // Re-checked under the gate: another caller may have rotated while this one waited.
            if (!policy.shouldRotate(trigger, player.state.value, developerTokenExpiresAt, Instant.now())) {
                return@withLock false
            }

            rotateCore()
            true
        }
    }

    /**
     * Puts one line on [diagnostic].
     *
     * @param line the line to report. Must never carry a token or a URI beyond scheme and host.
     */
    internal fun diagnose(line: String) {
        diagnostics.tryEmit(line)
    }

    private suspend fun rotateCore() {
        val token = options.developerTokenProvider.getToken(forceRefresh = true)
        val configured = bridge.reconfigure(token.token)

        developerTokenExpiresAt = token.expiresAt
        storefrontId = configured.storefrontId ?: storefrontId
        setAuthorization(configured.isAuthorized, initial = false)

        diagnose(
            "developer token rotated; expires ${utc(token.expiresAt)}, " +
                "authorized ${configured.isAuthorized}, " +
                "instance took the new token: ${configured.developerTokenMatches}",
        )
    }

    /** Walks a paged Apple collection, handing over one response body per page. */
    private suspend fun forEachPage(path: String, what: String, onPage: (JsonElement) -> Unit) {
        var nextPath = path
        var parameters = mapOf("limit" to PAGE_SIZE.toString())

        repeat(MAX_PAGES) {
            val body = requireBody(bridge.apiGet(nextPath, parameters), what)
            onPage(body)

            val next = AppleJson.readNext(body)
            if (next.isNullOrEmpty()) {
                return
            }

            val split = AppleJson.splitNext(next)
            nextPath = split.path
            parameters = split.parameters
        }

        throw MusicKitException(
            "Apple Music kept asking for another page while trying to $what, so the request was " +
                "abandoned.",
            "TOO_MANY_PAGES",
        )
    }

    private fun setAuthorization(authorized: Boolean, initial: Boolean) {
        // Before anyone has been asked, "not authorized" and "not determined" are the same
        // observation; only an explicit authorize or unauthorize can tell them apart.
        authorization.value = when {
            authorized -> MusicAuthorizationStatus.Authorized
            initial -> MusicAuthorizationStatus.NotDetermined
            else -> MusicAuthorizationStatus.NotAuthorized
        }
    }

    private fun onPageEvent(name: String, data: JsonElement?) {
        when (name) {
            "playbackStateDidChange" -> {
                val stateName = BridgePayload.readString(data, "state")
                val state = PlaybackStateNames.tryParse(stateName)
                if (state == null) {
                    diagnose("MusicKit reported an unknown playback state '$stateName'")
                }

                diagnose("event playbackStateDidChange: ${stateName ?: "(none)"}")
                player.onStateChanged(state ?: MusicPlaybackState.None)
            }

            "nowPlayingItemDidChange" -> {
                val item = BridgePayload.readPayload<NowPlayingPayload>(data, "item")?.toModel()
                diagnose(
                    "event nowPlayingItemDidChange: ${item?.id ?: "(none)"} " +
                        "\"${item?.title ?: "(none)"}\"",
                )
                player.onNowPlayingChanged(item)
            }

            "queueItemsDidChange" ->
                diagnose("event queueItemsDidChange: ${BridgePayload.readInt(data, "count")} item(s)")

            "authorizationStatusDidChange" ->
                setAuthorization(BridgePayload.readBool(data, "isAuthorized"), initial = false)

            "mediaPlaybackError" -> {
                val message = BridgePayload.readString(data, "message")
                    ?: "Apple Music reported a playback error."
                diagnose("playback error: $message")
                player.onPlaybackError(message)
            }

            "error" -> diagnose("page error: ${BridgePayload.readString(data, "message")}")

            "musickitloaded" ->
                diagnose("MusicKit JS ${BridgePayload.readString(data, "version")} loaded")

            // Anything MusicKit adds later needs no typed handling.
            else -> Unit
        }
    }

    /**
     * The idle rotation. The seams — a queue being set, a play from a standstill, a skip — cover
     * every rotation a busy host needs; this covers the host that sat still for an hour and would
     * otherwise start its next set with a token that expires mid-song.
     */
    private fun startRotationTimer() {
        scope.launch {
            while (isActive) {
                delay(ROTATION_TICK)

                if (closed ||
                    !policy.shouldRotate(
                        RotationTrigger.IdleTimer,
                        player.state.value,
                        developerTokenExpiresAt,
                        Instant.now(),
                    )
                ) {
                    continue
                }

                try {
                    rotateNow()
                } catch (error: MusicKitException) {
                    diagnose("idle token rotation failed: ${error.message}")
                } catch (error: TimeoutException) {
                    diagnose("idle token rotation failed: ${error.message}")
                }
            }
        }
    }

    companion object {

        /** How often the idle timer considers rotating the developer token. */
        private val ROTATION_TICK = 60.seconds

        /** Apple's page limit for the library endpoints this uses. */
        private const val PAGE_SIZE = 100

        /** Enough pages for any real library; a cursor that never ends is a fault, not a big library. */
        private const val MAX_PAGES = 100

        /** How many lines are replayed to a new collector; a bounded buffer, not a transcript. */
        internal const val MAX_BUFFERED_LINES = 50

        private val UTC_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

        /**
         * Stands a host up: hidden WebView, bundled page, first developer token, configure.
         *
         * @param options where tokens come from, which activity to park the page in, and the timeouts.
         * @return a configured host, ready for catalog calls and — if the user is already signed in
         *   — playback.
         * @throws MusicKitException the WebView, the page, or the token could not be brought up.
         */
        suspend fun create(options: MusicKitOptions): MusicKitHost {
            // Built before anything that logs into it, so the lines startup produces are held by
            // the flow's own replay rather than dropped for want of a collector.
            val diagnostics = MutableSharedFlow<String>(
                replay = MAX_BUFFERED_LINES,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

            val webView = HostedWebView.create(options.activity) { diagnostics.tryEmit(it) }

            var host: MusicKitHost? = null
            try {
                val bridge = MusicKitBridge(webView, options.callTimeout, options.authorizeTimeout)
                val built = MusicKitHost(options, webView, bridge, diagnostics)
                host = built

                bridge.onLog = built::diagnose
                bridge.onPageEvent = built::onPageEvent
                webView.onMessage = bridge::onMessage
                webView.onRendererGone = bridge::failAll

                // A sign-in window that never appeared is as final for a waiting call as a dead
                // renderer, and is reported the same way: a retryable PAGE_GONE, now, rather than
                // an authorize() that hangs for three minutes over a popup nobody will ever see.
                webView.onAuthPopupFailed = bridge::failAll

                if (!webView.waitForPage(options.callTimeout)) {
                    throw MusicKitException(
                        "The Apple Music page did not finish loading. Check that the device can " +
                            "reach js-cdn.music.apple.com.",
                        "PAGE_NOT_READY",
                    )
                }

                val token = options.developerTokenProvider.getToken(forceRefresh = false)
                val configured = bridge.configure(token.token, options.appName, options.appBuild)

                built.developerTokenExpiresAt = token.expiresAt
                built.storefrontId = configured.storefrontId
                built.setAuthorization(configured.isAuthorized, initial = true)
                built.diagnose(
                    "MusicKit ${configured.version} configured; " +
                        "storefront ${configured.storefrontId ?: "(none)"}, " +
                        "authorized ${configured.isAuthorized}, " +
                        "token expires ${utc(token.expiresAt)}",
                )

                built.startRotationTimer()
                return built
            } catch (error: Throwable) {
                // Whatever went wrong is the answer; a failure while unwinding must not replace it.
                runCatching { host?.close() ?: webView.close() }
                throw error
            }
        }

        private fun utc(instant: Instant): String = "${UTC_TIME.format(instant)}Z"
    }
}

/** Unwraps a successful API answer, or turns an unsuccessful one into an exception. */
private fun requireBody(answer: ApiResult, what: String): JsonElement {
    val body = answer.body
    if (answer.isSuccess && body != null) {
        return body
    }

    val (message, code) = AppleJson.describeFailure(what, answer.status, answer.body, answer.error)
    throw MusicKitException(message, code)
}
