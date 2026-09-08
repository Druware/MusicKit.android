package com.druware.musickit

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches and caches a developer token from a Card Server.
 *
 * **The contract is one unauthenticated POST.** `POST {baseUrl}/api/v1/shazam/token`, no body and
 * no credential, answering either `200` with `{ "token": "<jwt>", "expiresAt": "<ISO-8601 UTC>" }`
 * or the server's uniform error envelope, of which `503` with code
 * [SHAZAM_NOT_CONFIGURED_CODE] — no Apple key configured — is the one worth its own message.
 *
 * **The token is held in memory only.** It lives about an hour and is minted on demand from a
 * public endpoint, so losing it costs one HTTP request. It is never written to a log, a message, or
 * an exception.
 *
 * **The address is read per request.** A host that lets a user edit the server address takes the
 * new one on the next fetch without rebuilding the provider.
 *
 * This type is safe to use from several coroutines at once.
 *
 * @param http the client to fetch with, supplied so a host can share its pooled client and a test
 *   can point it at a local server.
 * @param baseUrl returns the server's base URL, scheme included; evaluated once per token request.
 * @param retryBaseDelay the first backoff step for a `429`, defaulting to 500 ms. Present so a test
 *   need not spend real seconds in the retry path.
 */
class CardServerDeveloperTokenProvider(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    retryBaseDelay: Duration? = null,
) : DeveloperTokenProvider {

    private val retryBaseDelay: Duration = retryBaseDelay ?: DEFAULT_RETRY_BASE_DELAY

    /**
     * What stops several callers arriving together from each opening their own token round trip:
     * the check-then-fetch is one critical section, so a caller that arrives while a fetch is in
     * flight waits for it and then finds the cache populated.
     */
    private val gate = Mutex()

    @Volatile
    private var cached: DeveloperToken? = null

    override suspend fun getToken(forceRefresh: Boolean): DeveloperToken = gate.withLock {
        val held = cached
        if (!forceRefresh &&
            held != null &&
            Instant.now().isBefore(held.expiresAt.minusMillis(REFRESH_MARGIN.inWholeMilliseconds))
        ) {
            return@withLock held
        }

        val (token, expiresAt) = fetch()

        // Assigned only after a successful fetch: a failed one leaves whatever was cached alone, so
        // a transient outage does not also throw away a token that is still valid.
        DeveloperToken(token, expiryOf(expiresAt)).also { cached = it }
    }

    /** Takes no lock: clearing a single reference is one atomic write. */
    override fun invalidate() {
        cached = null
    }

    /** One token request, retrying a `429`. */
    private suspend fun fetch(): Pair<String, String?> {
        var attempt = 0
        while (true) {
            val (status, body) = perform()
            if (status == 200) {
                return decode(body)
            }

            if (status != 429 || attempt >= MAX_RETRIES) {
                throw failure(status, body)
            }

            delay(backoffFor(attempt, retryBaseDelay))
            attempt++
        }
    }

    private suspend fun perform(): Pair<Int, String> {
        val url = tokenUri(baseUrl())

        // The endpoint takes no body, but it is a POST and a JSON API, so it gets an empty
        // JSON-typed one rather than none at all.
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            send(request)
        } catch (error: IOException) {
            // A .NET exception always has a Message; a Throwable's can be null, and "Couldn't
            // reach host: null" is not a sentence worth showing anyone.
            throw MusicKitException(
                "Couldn't reach ${url.host}: ${error.message ?: error.javaClass.simpleName}",
                "TOKEN_PROVIDER_FAILED",
                error,
            )
        }
    }

    /**
     * Sends one request, cancelling it if the calling coroutine is cancelled.
     *
     * A cancellation propagates as itself rather than as a [MusicKitException]: the caller giving
     * up is not the server failing.
     */
    private suspend fun send(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { continuation.resume(it.code to (it.body?.string() ?: "")) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    companion object {
        /** The error code a server with no Apple key configured answers `503` with. */
        const val SHAZAM_NOT_CONFIGURED_CODE = "SHAZAM_NOT_CONFIGURED"

        /** Attempts after the first before a `429` becomes a caller-visible failure. */
        internal const val MAX_RETRIES = 3

        /** First backoff step for a `429`; each retry doubles it and adds jitter. */
        internal val DEFAULT_RETRY_BASE_DELAY = 500.milliseconds

        private const val TOKEN_PATH = "/api/v1/shazam/token"

        /** Renew this far ahead of expiry rather than at it. */
        private val REFRESH_MARGIN = 60.seconds

        /**
         * How long an unparseable `expiresAt` is trusted for: short enough that a server sending a
         * format that cannot be read is re-asked promptly, long enough not to fetch per call.
         */
        private val UNKNOWN_EXPIRY = 5.minutes

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val json = Json

        /**
         * The backoff before retrying a `429`: the doubling step, plus up to another whole step of
         * jitter, so the delay lands uniformly in `[step, 2 * step)`.
         *
         * **The jitter is the load-bearing half.** The endpoint sits behind a per-IP rate limit and
         * a venue is one public IP, so devices starting together collide legitimately. Devices that
         * collided once would, without jitter, back off in lockstep and collide again.
         *
         * @param attempt zero for the first retry.
         * @param baseDelay the first step, doubled once per attempt.
         */
        internal fun backoffFor(attempt: Int, baseDelay: Duration): Duration {
            val step = baseDelay.inWholeNanoseconds shl attempt
            if (step <= 0L) {
                return Duration.ZERO
            }

            return (step + Random.nextLong(step)).nanoseconds
        }

        /**
         * Builds the token endpoint's URL from a base URL.
         *
         * @param url the base URL as the host supplied it.
         * @return the absolute URL of the token endpoint.
         * @throws MusicKitException [url] is not an absolute HTTP or HTTPS URL.
         */
        internal fun tokenUri(url: String?): HttpUrl {
            val parsed = url?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
                ?: throw MusicKitException(
                    "The server address isn't a valid URL. Include the scheme, " +
                        "e.g. https://cards.example.com.",
                    "TOKEN_PROVIDER_FAILED",
                )

            return parsed.newBuilder()
                .encodedPath(parsed.encodedPath.trimEnd('/') + TOKEN_PATH)
                .query(null)
                .fragment(null)
                .build()
        }

        /**
         * Converts the server's `expiresAt` to an instant, trusting an unreadable one for five
         * minutes.
         *
         * Internal rather than private only so the five-minute fallback can be asserted: from
         * outside, an unreadable expiry is indistinguishable from a long one until five minutes
         * have passed.
         *
         * @param expiresAt the value the server sent, or null when it sent none.
         * @return when the token stops being usable.
         */
        internal fun expiryOf(expiresAt: String?): Instant =
            parseInstant(expiresAt) ?: Instant.now().plusMillis(UNKNOWN_EXPIRY.inWholeMilliseconds)

        /**
         * Reads an instant the way .NET's `TryParse` with `AssumeUniversal | AdjustToUniversal`
         * does: an explicit offset is honoured, and a timestamp carrying none is taken as UTC.
         */
        private fun parseInstant(text: String?): Instant? {
            val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            return runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(trimmed) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC) }.getOrNull()
        }

        private fun decode(body: String): Pair<String, String?> {
            val element = try {
                json.parseToJsonElement(body)
            } catch (error: SerializationException) {
                throw MusicKitException(
                    "The server's token response couldn't be read. " +
                        "It may be running a different version.",
                    "TOKEN_PROVIDER_FAILED",
                    error,
                )
            }

            if (element !is JsonObject && element !is JsonNull) {
                throw MusicKitException(
                    "The server's token response couldn't be read. " +
                        "It may be running a different version.",
                    "TOKEN_PROVIDER_FAILED",
                )
            }

            val decoded = element as? JsonObject
            val token = decoded?.stringIgnoringCase("token")
            if (token.isNullOrBlank()) {
                throw MusicKitException(
                    "The server returned an empty developer token.",
                    "TOKEN_PROVIDER_FAILED",
                )
            }

            return token to decoded.stringIgnoringCase("expiresAt")
        }

        /**
         * Reads the server's uniform error envelope, falling back to the bare status.
         *
         * Every message here is a whole sentence a host can put on screen. None of them quotes the
         * response body, so a server that echoes something it should not cannot leak it through
         * here.
         */
        private fun failure(status: Int, body: String): MusicKitException {
            if (status == 429) {
                return MusicKitException(
                    "The server is busy right now. Try again in a few seconds.",
                    "429",
                )
            }

            val error = runCatching {
                (json.parseToJsonElement(body) as? JsonObject)
                    ?.entryIgnoringCase("error") as? JsonObject
            }.getOrNull()

            if (error == null) {
                return MusicKitException(
                    "The server returned an unexpected response to the token request " +
                        "(HTTP $status).",
                    status.toString(),
                )
            }

            val code = error.stringIgnoringCase("code")
            if (code == SHAZAM_NOT_CONFIGURED_CODE) {
                return MusicKitException(
                    "This Card Server has no Apple key configured " +
                        "($SHAZAM_NOT_CONFIGURED_CODE), so it cannot mint developer tokens.",
                    SHAZAM_NOT_CONFIGURED_CODE,
                )
            }

            val message = error.stringIgnoringCase("message")
            return MusicKitException(
                if (message.isNullOrBlank()) {
                    "The server returned an unexpected response to the token request (HTTP $status)."
                } else {
                    message
                },
                code ?: status.toString(),
            )
        }

        /** The C# decoder reads property names case-insensitively; this keeps that contract. */
        private fun JsonObject.entryIgnoringCase(name: String) =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        private fun JsonObject.stringIgnoringCase(name: String): String? =
            (entryIgnoringCase(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
