package com.druware.musickit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The Card Server token contract: the 200 body, the cache, the single-flight gate, the 429 retry,
 * and each failure worth its own sentence.
 */
class CardServerDeveloperTokenProviderTest {

    private companion object {
        const val GOOD_BODY =
            """{"token":"header.payload.signature","expiresAt":"2030-01-02T03:04:05Z"}"""
        const val ERROR_BODY = """{"error":{"code":"BOOM","message":"no"}}"""
    }

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `parses token and expiry from a 200`() = runTest {
        val provider = providerFor(always(200, GOOD_BODY))

        val token = provider.getToken()

        assertEquals("header.payload.signature", token.token)
        assertEquals(Instant.parse("2030-01-02T03:04:05Z"), token.expiresAt)
    }

    @Test
    fun `the request is a post to the shazam token path`() = runTest {
        providerFor(always(200, GOOD_BODY)).getToken()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/shazam/token", recorded.path)
        assertEquals(server.url("/api/v1/shazam/token"), recorded.requestUrl)
    }

    @Test
    fun `reuses the cached token until a refresh is forced`() = runTest {
        val provider = providerFor(always(200, GOOD_BODY))

        provider.getToken()
        provider.getToken()
        assertEquals(1, server.requestCount)

        provider.getToken(forceRefresh = true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `invalidate makes the next get fetch again`() = runTest {
        val provider = providerFor(always(200, GOOD_BODY))

        provider.getToken()
        provider.invalidate()
        provider.getToken()

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a token inside the sixty second margin is replaced`() = runTest {
        val expiry = Instant.now().plusSeconds(30).toString()
        val provider = providerFor(
            always(200, """{"token":"aaa.bbb.ccc","expiresAt":"$expiry"}"""),
        )

        provider.getToken()
        provider.getToken()

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `ten concurrent callers make one request`() = runTest {
        val provider = providerFor(
            always(200, GOOD_BODY, bodyDelayMillis = 50),
        )

        val tokens = (0 until 10).map { async { provider.getToken() } }.awaitAll()

        assertEquals(1, server.requestCount)
        for (token in tokens) {
            assertEquals("header.payload.signature", token.token)
        }
    }

    @Test
    fun `a 429 is retried and then succeeds`() = runTest {
        val provider = providerFor(scripted(429, 429, 200), retryBaseDelay = 1.milliseconds)

        val token = provider.getToken()

        assertEquals("header.payload.signature", token.token)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `four consecutive 429s surface as an error`() = runTest {
        val provider = providerFor(always(429, "rate limited"), retryBaseDelay = 1.milliseconds)

        val error = assertThrowsMusicKit { provider.getToken() }

        // Three retries after the first attempt, and no fourth delay before giving up.
        assertEquals(4, server.requestCount)
        assertEquals("429", error.code)
        assertTrue(error.message, error.message.contains("busy", ignoreCase = true))
    }

    @Test
    fun `backoff doubles and stays inside its jitter window`() {
        repeat(50) {
            val first = CardServerDeveloperTokenProvider.backoffFor(0, 500.milliseconds)
            assertTrue("$first", first >= 500.milliseconds && first < 1000.milliseconds)

            val third = CardServerDeveloperTokenProvider.backoffFor(2, 500.milliseconds)
            assertTrue("$third", third >= 2000.milliseconds && third < 4000.milliseconds)
        }
    }

    @Test
    fun `a 503 shazam not configured says the server has no apple key`() = runTest {
        val provider = providerFor(
            always(503, """{"error":{"code":"SHAZAM_NOT_CONFIGURED","message":"not configured"}}"""),
        )

        val error = assertThrowsMusicKit { provider.getToken() }

        assertEquals(
            CardServerDeveloperTokenProvider.SHAZAM_NOT_CONFIGURED_CODE,
            error.code,
        )
        assertTrue(error.message, error.message.contains("no Apple key"))
    }

    @Test
    fun `another error code surfaces the servers own message`() = runTest {
        val provider = providerFor(
            always(500, """{"error":{"code":"INTERNAL","message":"Something broke."}}"""),
        )

        val error = assertThrowsMusicKit { provider.getToken() }

        assertEquals("Something broke.", error.message)
        assertEquals("INTERNAL", error.code)
    }

    @Test
    fun `malformed json in a 200 is reported not swallowed`() = runTest {
        val provider = providerFor(always(200, "{ this is not json"))

        val error = assertThrowsMusicKit { provider.getToken() }

        assertTrue(error.message, error.message.contains("couldn't be read"))
    }

    @Test
    fun `a 200 with no token is reported`() = runTest {
        val provider = providerFor(always(200, """{"expiresAt":"2030-01-02T03:04:05Z"}"""))

        val error = assertThrowsMusicKit { provider.getToken() }

        assertTrue(error.message, error.message.contains("empty developer token"))
    }

    @Test
    fun `a failed fetch leaves the previous token cached`() = runTest {
        val provider = providerFor(scripted(200, 500, 200))

        val first = provider.getToken()
        assertThrowsMusicKit { provider.getToken(forceRefresh = true) }
        val again = provider.getToken()

        assertEquals(first.token, again.token)
    }

    @Test
    fun `a network failure with no message falls back to the exception class name`() = runTest {
        val error = IOException(null as String?)
        val throwingHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw error })
            .build()

        val provider = CardServerDeveloperTokenProvider(
            throwingHttp,
            { server.url("/").toString() },
        )

        val thrown = assertThrowsMusicKit { provider.getToken() }

        assertFalse(thrown.message, thrown.message.contains("null"))
        assertTrue(thrown.message, thrown.message.contains("IOException"))
        assertEquals("TOKEN_PROVIDER_FAILED", thrown.code)
    }

    @Test
    fun `an unreadable expiry is trusted for five minutes`() {
        val expiry = CardServerDeveloperTokenProvider.expiryOf("whenever")
        val remaining = (expiry.toEpochMilli() - Instant.now().toEpochMilli()).milliseconds

        assertTrue("$remaining", remaining > 4.minutes && remaining <= 5.minutes)
    }

    @Test
    fun `an address without a scheme is refused before the request is made`() {
        val error = assertThrowsMusicKit { CardServerDeveloperTokenProvider.tokenUri("example.test") }

        assertTrue(error.message, error.message.contains("valid URL"))
        assertEquals(0, server.requestCount)
    }

    // ---- helpers ----------------------------------------------------------

    private fun providerFor(
        dispatcher: Dispatcher,
        retryBaseDelay: Duration? = null,
    ): CardServerDeveloperTokenProvider {
        server.dispatcher = dispatcher

        return CardServerDeveloperTokenProvider(
            http,
            { server.url("/").toString() },
            retryBaseDelay,
        )
    }

    /** Answers every request the same way. */
    private fun always(status: Int, body: String, bodyDelayMillis: Long = 0): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(status)
                    .setBody(body)
                    .addHeader("Content-Type", "application/json")
                    .apply {
                        if (bodyDelayMillis > 0) {
                            setBodyDelay(bodyDelayMillis, TimeUnit.MILLISECONDS)
                        }
                    }
        }

    /** Answers each request with the next status in a script, repeating the last one. */
    private fun scripted(vararg statuses: Int): Dispatcher = object : Dispatcher() {
        private var calls = 0

        override fun dispatch(request: RecordedRequest): MockResponse {
            val status = statuses[minOf(calls, statuses.size - 1)]
            calls++

            return MockResponse()
                .setResponseCode(status)
                .setBody(if (status == 200) GOOD_BODY else ERROR_BODY)
                .addHeader("Content-Type", "application/json")
        }
    }

    private inline fun assertThrowsMusicKit(block: () -> Unit): MusicKitException =
        try {
            block()
            throw AssertionError("Expected a MusicKitException, but nothing was thrown.")
        } catch (error: MusicKitException) {
            error
        }
}
