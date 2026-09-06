package com.druware.musickit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Hands one JSON envelope to the page. Implementations must be safe to call from any thread. */
internal fun interface PageChannel {
    suspend fun post(json: String)
}

/**
 * The correlation table: every `invoke` gets an id, and the result carrying that id completes the
 * call the caller is awaiting.
 *
 * Split out from the bridge so the matching can be tested without a WebView.
 */
internal class PendingCalls {

    private val calls = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val next = AtomicInteger(0)

    /** How many calls are still waiting for an answer. */
    val count: Int
        get() = calls.size

    /**
     * @return an id unique within this table. A counter, not a UUID: the page echoes it back
     *   verbatim and nothing outside this process ever sees it.
     */
    fun nextId(): String = next.incrementAndGet().toString()

    /**
     * Reserves an id, so a result that arrives before the call has finished going out is not lost.
     *
     * The reservation is handed back rather than looked up again by [await], because by the time a
     * caller gets round to waiting the answer may already have arrived and taken the entry out of
     * the table with it.
     *
     * @param id the correlation id, from [nextId].
     * @return what the result will complete, to be handed to [await].
     * @throws IllegalStateException the id is already registered, which monotonic ids make
     *   unreachable — it is checked because a silent overwrite would strand the first caller.
     */
    fun register(id: String): CompletableDeferred<JsonElement> {
        val waiting = CompletableDeferred<JsonElement>()
        val existing = calls.putIfAbsent(id, waiting)
        check(existing == null) { "Duplicate bridge call id '$id'." }
        return waiting
    }

    /**
     * Waits for the page to answer a registered call.
     *
     * @param id the correlation id [register] was given.
     * @param waiting what [register] handed back for that id.
     * @param timeout how long to wait before giving up on the page.
     * @return the value the page returned.
     * @throws TimeoutException the page did not answer in time. Deliberately not a
     *   [kotlinx.coroutines.CancellationException]: a caller that cancels its own coroutine gets
     *   one of those instead, and the two mean different things.
     * @throws MusicKitException the page answered with a failure, or the page went away.
     */
    suspend fun await(
        id: String,
        waiting: CompletableDeferred<JsonElement>,
        timeout: Duration,
    ): JsonElement {
        try {
            // withTimeoutOrNull swallows only its own timeout; a cancellation coming from the
            // caller's scope still propagates, which is exactly the distinction the C# draws
            // between TimeoutException and OperationCanceledException.
            return withTimeoutOrNull(timeout) { waiting.await() }
                ?: throw TimeoutException(
                    "The MusicKit page did not answer call '$id' within " +
                        "${timeout.inWholeSeconds} s.",
                )
        } finally {
            calls.remove(id)
        }
    }

    /** Drops a reserved id whose call never got as far as being awaited. */
    fun forget(id: String) {
        calls.remove(id)
    }

    /**
     * Completes the call a result message names, if it is still waiting.
     *
     * @param message a message whose [BridgeMessage.type] is `result`.
     * @return true when a waiting call was completed; false when the id is unknown or the call has
     *   already timed out — which is the case the bridge logs as having no waiting caller.
     */
    fun complete(message: BridgeMessage): Boolean {
        val id = message.id ?: return false
        val waiting = calls.remove(id) ?: return false

        if (message.ok) {
            waiting.complete(message.value ?: JsonNull)
        } else {
            waiting.completeExceptionally(
                MusicKitException(
                    message.error ?: "The MusicKit page reported an unspecified error.",
                    "PAGE_ERROR",
                ),
            )
        }

        return true
    }

    /**
     * Fails every waiting call, for when the page or its renderer has gone away.
     *
     * @param reason what happened, as a sentence fit to show a user.
     */
    fun failAll(reason: String) {
        for (id in calls.keys.toList()) {
            calls.remove(id)?.completeExceptionally(MusicKitException(reason, "PAGE_GONE"))
        }
    }
}

/** What the page knows about MusicKit right now. */
@Serializable
internal data class WidevineProbeResult(
    @SerialName("supported") val supported: Boolean = false,
    @SerialName("keySystem") val keySystem: String? = null,
    @SerialName("error") val error: String? = null,
)

/** The shape `authorize` and `unauthorize` answer with. */
@Serializable
private data class AuthorizationState(
    @SerialName("isAuthorized") val isAuthorized: Boolean = false,
)

/** The shape `setQueue` answers with. */
@Serializable
private data class QueueState(
    @SerialName("count") val count: Int = 0,
)

/**
 * The typed Kotlin half of the bridge to `musickit-host.js`.
 *
 * Calls go out as one `{type:'invoke', id, method, args}` envelope over the channel
 * `WebViewCompat.addWebMessageListener` injects, and come back as a `result` message carrying the
 * same id; MusicKit's own events arrive unsolicited as `event` messages.
 *
 * Nothing here touches the WebView: [page] does that, and marshals to the main thread while doing
 * it. This class is safe to call from any thread.
 *
 * @param page how one envelope reaches the page.
 * @param callTimeout how long an ordinary call may take.
 * @param authorizeTimeout how long the sign-in call may take, which is as long as a human takes.
 */
internal class MusicKitBridge(
    private val page: PageChannel,
    private val callTimeout: Duration,
    private val authorizeTimeout: Duration,
) {

    private val pending = PendingCalls()

    /**
     * Called with a MusicKit or page event's name and payload.
     *
     * A plain callback rather than a flow: the host wires it before the page can speak, and a line
     * emitted into a flow nobody has collected yet would be dropped.
     */
    var onPageEvent: ((String, JsonElement?) -> Unit)? = null

    /** Called with a line worth putting in a host's diagnostic log. */
    var onLog: ((String) -> Unit)? = null

    /** How many calls are waiting for the page. Exposed for tests and diagnostics. */
    val pendingCount: Int
        get() = pending.count

    /**
     * Takes one message the page posted.
     *
     * @param raw the JSON string exactly as the page posted it.
     */
    fun onMessage(raw: String?) {
        val message = BridgeMessage.parse(raw)
        if (message == null) {
            onLog?.invoke("bridge: unparseable web message ignored")
            return
        }

        when (message.type) {
            "result" ->
                if (!pending.complete(message)) {
                    onLog?.invoke("bridge: result #${message.id} had no waiting caller (timed out?)")
                }

            "event" -> message.name?.let { onPageEvent?.invoke(it, message.data) }

            "log" -> onLog?.invoke("page: ${message.message}")

            else -> onLog?.invoke("bridge: unknown message type '${message.type}'")
        }
    }

    /**
     * Fails everything still waiting, for when the renderer died or the host was closed.
     *
     * @param reason what happened, as a sentence.
     */
    fun failAll(reason: String) {
        pending.failAll(reason)
    }

    /** Asks the page what it knows, without touching MusicKit. */
    suspend fun status(): PageStatus =
        decode(invoke("status", null, callTimeout), "PageStatus")

    /**
     * Calls `MusicKit.configure` for the first time and attaches the event listeners.
     *
     * @param developerToken the Apple Media Services JWT. Never logged.
     * @param appName the application name Apple's sign-in popup shows.
     * @param appBuild the application build reported alongside the name.
     */
    suspend fun configure(developerToken: String, appName: String, appBuild: String): ConfigureResult =
        decode(
            invoke(
                "configure",
                buildJsonObject {
                    put("token", developerToken)
                    put("appName", appName)
                    put("appBuild", appBuild)
                },
                callTimeout,
            ),
            "ConfigureResult",
        )

    /**
     * Calls `MusicKit.configure` again with a replacement token.
     *
     * This stops playback and clears the queue; the caller re-applies both.
     */
    suspend fun reconfigure(developerToken: String): ConfigureResult =
        decode(
            invoke("reconfigure", buildJsonObject { put("token", developerToken) }, callTimeout),
            "ConfigureResult",
        )

    /**
     * Opens Apple's sign-in popup and waits for the user to finish.
     *
     * The Music User Token never crosses the bridge; only the boolean does.
     *
     * @return true when a Music User Token is now held.
     */
    suspend fun authorize(): Boolean =
        decode<AuthorizationState>(invoke("authorize", null, authorizeTimeout), "AuthorizationState")
            .isAuthorized

    /** Drops the Music User Token. */
    suspend fun unauthorize(): Boolean =
        decode<AuthorizationState>(invoke("unauthorize", null, callTimeout), "AuthorizationState")
            .isAuthorized

    /**
     * Makes one Apple Music API GET through `music.api.music`.
     *
     * @param path the API path, without a query string.
     * @param parameters the query parameters, which MusicKit turns into the query string.
     * @return Apple's status and body, successful or not — an API failure is never a bridge failure.
     */
    suspend fun apiGet(path: String, parameters: Map<String, String>?): ApiResult =
        decode<ApiResult>(
            invoke(
                "apiGet",
                buildJsonObject {
                    put("path", path)
                    if (parameters == null) {
                        put("params", JsonNull)
                    } else {
                        put(
                            "params",
                            buildJsonObject { parameters.forEach { (key, value) -> put(key, value) } },
                        )
                    }
                },
                callTimeout,
                // A path is never a credential, so it is safe in the transcript; an argument might
                // be, so nothing else from this call goes in it.
                detail = path,
            ),
            "ApiResult",
        ).normalised()

    /**
     * Makes one Apple Music API POST from inside the page.
     *
     * @param path the API path.
     * @param body the JSON request body, already serialised.
     */
    suspend fun apiPost(path: String, body: String): ApiResult =
        decode<ApiResult>(
            invoke(
                "apiPost",
                buildJsonObject {
                    put("path", path)
                    put("body", body)
                },
                callTimeout,
            ),
            "ApiResult",
        ).normalised()

    /**
     * Replaces the playback queue.
     *
     * @param ids the identifiers to queue, in order.
     * @return how many items MusicKit actually put in its queue.
     */
    suspend fun setQueue(ids: List<String>): Int =
        decode<QueueState>(
            invoke(
                "setQueue",
                buildJsonObject {
                    put("ids", buildJsonArray { ids.forEach { add(it) } })
                },
                callTimeout,
                detail = "${ids.size} id(s)",
            ),
            "QueueState",
        ).count

    /** Starts or resumes playback. */
    suspend fun play() {
        invoke("play", null, callTimeout)
    }

    /** Holds playback at the current position. */
    suspend fun pause() {
        invoke("pause", null, callTimeout)
    }

    /** Stops playback and clears the now-playing item. */
    suspend fun stop() {
        invoke("stop", null, callTimeout)
    }

    /**
     * Skips to the next queued item.
     *
     * @return the state and the item the skip landed on.
     */
    suspend fun skipToNext(): SkipResult =
        decode(invoke("skipToNext", null, callTimeout), "SkipResult")

    /** Asks the page whether this WebView build can start Widevine EME at all. */
    suspend fun probeWidevine(): WidevineProbeResult =
        decode(invoke("probeWidevine", null, callTimeout), "WidevineProbeResult")

    /**
     * Sends one call to the page and waits for the matching result message.
     *
     * @param method the bridge method to call.
     * @param args the arguments for it.
     * @param timeout how long to wait for the answer.
     * @param detail what is worth putting in the trace beside the method name — never an argument
     *   that could carry a credential, so a path or a count and nothing else.
     */
    private suspend fun invoke(
        method: String,
        args: JsonObject?,
        timeout: Duration,
        detail: String? = null,
    ): JsonElement {
        val id = pending.nextId()

        // Registered before the envelope goes out, so a result that somehow arrives first is not
        // dropped on the floor.
        val waiting = pending.register(id)

        onLog?.invoke("-> $method #$id${if (detail == null) "" else " $detail"}")
        val started = TimeSource.Monotonic.markNow()

        val envelope = buildJsonObject {
            put("type", "invoke")
            put("id", id)
            put("method", method)
            put("args", args ?: JsonNull)
        }

        try {
            page.post(envelope.toString())
            val value = pending.await(id, waiting, timeout)
            onLog?.invoke("<- #$id $method ok ${started.elapsedNow().inWholeMilliseconds} ms")
            return value
        } catch (error: TimeoutException) {
            onLog?.invoke("<- #$id $method TIMEOUT after ${timeout.inWholeSeconds} s")
            throw error
        } catch (error: Exception) {
            onLog?.invoke("<- #$id $method failed: ${error.message}")
            throw error
        } finally {
            // await removes the id on every one of its own exits; this covers the one path that
            // never reaches it, a post that threw.
            pending.forget(id)
        }
    }

    private inline fun <reified T> decode(element: JsonElement, what: String): T {
        if (element is JsonNull) {
            throw MusicKitException(
                "The MusicKit page returned nothing where a $what was expected.",
                "PAGE_ERROR",
            )
        }

        return try {
            BridgePayload.json.decodeFromJsonElement<T>(element)
        } catch (error: SerializationException) {
            throw MusicKitException(
                "The MusicKit page returned a $what this library could not read.",
                "PAGE_ERROR",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw MusicKitException(
                "The MusicKit page returned a $what this library could not read.",
                "PAGE_ERROR",
                error,
            )
        }
    }
}
