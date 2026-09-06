package com.druware.musickit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The typed half of the bridge, driven against a page that answers on command.
 *
 * Nothing here needs a WebView: the channel is the only thing that does, and it is one method.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicKitBridgeTest {

    /** Stands in for the page: records what went out and answers whatever the test says to. */
    private class FakePage : PageChannel {
        val sent = mutableListOf<JsonObject>()
        var answer: ((JsonObject) -> String?)? = null
        var replies: ((String) -> Unit)? = null
        var failWith: Exception? = null

        override suspend fun post(json: String) {
            failWith?.let { throw it }

            val envelope = Json.parseToJsonElement(json).jsonObject
            sent += envelope
            answer?.invoke(envelope)?.let { replies?.invoke(it) }
        }
    }

    private val page = FakePage()
    private val logged = mutableListOf<String>()
    private val events = mutableListOf<Pair<String, JsonElement?>>()

    private val bridge = MusicKitBridge(page, callTimeout = 30.seconds, authorizeTimeout = 3.minutes)
        .also { made ->
            made.onLog = { logged += it }
            made.onPageEvent = { name, data -> events += name to data }
            page.replies = made::onMessage
        }

    /** Answers every call with `ok: true` and the given value. */
    private fun answerWith(value: String) {
        page.answer = { envelope ->
            """{"type":"result","id":${envelope["id"]},"ok":true,"value":$value,"error":null}"""
        }
    }

    /** Answers every call with `ok: false` and the given message. */
    private fun failWith(message: String) {
        page.answer = { envelope ->
            """{"type":"result","id":${envelope["id"]},"ok":false,"value":null,"error":"$message"}"""
        }
    }

    private val lastSent: JsonObject
        get() = page.sent.last()

    @Test
    fun `a call goes out as one invoke envelope naming its own id`() = runTest {
        answerWith("""{"version":"3","storefrontId":"us","isAuthorized":false}""")

        bridge.configure("a-developer-token", "MusicBingo", "1.2.3")

        assertEquals("one envelope per call", 1, page.sent.size)
        assertEquals("invoke", lastSent["type"]!!.jsonPrimitive.content)
        assertEquals("configure", lastSent["method"]!!.jsonPrimitive.content)
        assertEquals("1", lastSent["id"]!!.jsonPrimitive.content)

        val args = lastSent["args"]!!.jsonObject
        assertEquals("a-developer-token", args["token"]!!.jsonPrimitive.content)
        assertEquals("MusicBingo", args["appName"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", args["appBuild"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ids advance so two calls can be in flight at once`() = runTest {
        answerWith("""{"playbackState":"playing"}""")

        bridge.play()
        bridge.pause()

        assertEquals("1", page.sent[0]["id"]!!.jsonPrimitive.content)
        assertEquals("2", page.sent[1]["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a call with no arguments sends a JSON null rather than omitting them`() = runTest {
        answerWith("""{"isAuthorized":true}""")

        bridge.authorize()

        assertEquals(JsonNull, lastSent["args"])
    }

    @Test
    fun `a configure answer is decoded whole`() = runTest {
        answerWith(
            """{"version":"3.2526","storefrontId":"gb","isAuthorized":true,"developerTokenMatches":true}""",
        )

        val configured = bridge.reconfigure("a-replacement-token")

        assertEquals("3.2526", configured.version)
        assertEquals("gb", configured.storefrontId)
        assertTrue(configured.isAuthorized)
        assertEquals(true, configured.developerTokenMatches)
    }

    @Test
    fun `a first configure reports nothing about whether the token matched`() = runTest {
        answerWith("""{"version":"3","storefrontId":"us","isAuthorized":false}""")

        assertNull(bridge.configure("a-token", "app", "1").developerTokenMatches)
    }

    @Test
    fun `authorize hands back only the boolean`() = runTest {
        answerWith("""{"isAuthorized":true}""")

        assertTrue(bridge.authorize())
    }

    @Test
    fun `setQueue reports how many MusicKit took`() = runTest {
        answerWith("""{"count":3}""")

        assertEquals(3, bridge.setQueue(listOf("i.a", "i.b", "1234")))
        assertEquals("the count is traced, the identifiers are not", "-> setQueue #1 3 id(s)", logged.first())
    }

    @Test
    fun `an API failure is an answer, not an exception`() = runTest {
        answerWith("""{"status":404,"body":null,"error":"NOT_FOUND: 404"}""")

        val answer = bridge.apiGet("/v1/me/library/playlists/p.nope", null)

        assertEquals(404, answer.status)
        assertFalse(answer.isSuccess)
        assertEquals("NOT_FOUND: 404", answer.error)
    }

    @Test
    fun `a JSON null body is normalised to no body at all`() = runTest {
        answerWith("""{"status":200,"body":null,"error":null}""")

        assertNull(
            "a JsonNull body would pattern-match as though something came back",
            bridge.apiGet("/v1/catalog/us/search", mapOf("term" to "hello")).body,
        )
    }

    @Test
    fun `apiGet sends its parameters as an object and its path in the trace`() = runTest {
        answerWith("""{"status":200,"body":{"data":[]},"error":null}""")

        bridge.apiGet("/v1/catalog/us/search", mapOf("term" to "hello", "limit" to "5"))

        val args = lastSent["args"]!!.jsonObject
        assertEquals("/v1/catalog/us/search", args["path"]!!.jsonPrimitive.content)
        assertEquals("hello", args["params"]!!.jsonObject["term"]!!.jsonPrimitive.content)
        assertTrue(
            "a path is never a credential and belongs in the trace",
            logged.first().endsWith("/v1/catalog/us/search"),
        )
    }

    @Test
    fun `apiGet with no parameters sends an explicit null`() = runTest {
        answerWith("""{"status":200,"body":{},"error":null}""")

        bridge.apiGet("/v1/me/library/playlists/p.1", null)

        assertEquals(JsonNull, lastSent["args"]!!.jsonObject["params"])
    }

    @Test
    fun `a page error becomes a MusicKitException the host can show`() = runTest {
        failWith("MusicKit is not configured yet.")

        val error = runCatching { bridge.play() }.exceptionOrNull()

        assertTrue("wrong exception: $error", error is MusicKitException)
        assertEquals("PAGE_ERROR", (error as MusicKitException).code)
        assertEquals("MusicKit is not configured yet.", error.message)
        assertTrue(logged.any { it.startsWith("<- #1 play failed:") })
    }

    @Test
    fun `a result that arrives empty where a shape was expected is a page error`() = runTest {
        answerWith("null")

        val error = runCatching { bridge.status() }.exceptionOrNull()

        assertTrue("wrong exception: $error", error is MusicKitException)
        assertEquals("PAGE_ERROR", (error as MusicKitException).code)
        assertTrue(error.message.contains("PageStatus"))
    }

    @Test
    fun `a result of the wrong shape is a page error rather than a leaked parser failure`() = runTest {
        answerWith("""{"count":"three"}""")

        val error = runCatching { bridge.setQueue(listOf("1")) }.exceptionOrNull()

        assertTrue("wrong exception: $error", error is MusicKitException)
        assertEquals("PAGE_ERROR", (error as MusicKitException).code)
    }

    @Test
    fun `a page that never answers times out and says which call`() = runTest {
        page.answer = null

        val waiting = async { runCatching { bridge.play() } }
        advanceTimeBy(31.seconds)

        val error = waiting.await().exceptionOrNull()
        assertTrue("wrong exception: $error", error is TimeoutException)
        assertTrue(logged.any { it == "<- #1 play TIMEOUT after 30 s" })
        assertEquals("a timed-out call was left pending", 0, bridge.pendingCount)
    }

    @Test
    fun `the sign-in call is given the longer timeout a human needs`() = runTest {
        page.answer = null

        val waiting = async { runCatching { bridge.authorize() } }

        // Well past an ordinary call's 30 s, nowhere near the sign-in's three minutes.
        advanceTimeBy(60.seconds)
        yield()
        assertFalse("the sign-in was cut short at the ordinary timeout", waiting.isCompleted)
        assertEquals(1, bridge.pendingCount)

        advanceTimeBy(2.minutes + 30.seconds)
        assertTrue(waiting.await().exceptionOrNull() is TimeoutException)
    }

    @Test
    fun `a channel that cannot deliver leaves no call behind`() = runTest {
        page.failWith = MusicKitException("The Apple Music page is not listening yet.", "PAGE_NOT_READY")

        val error = runCatching { bridge.play() }.exceptionOrNull()

        assertTrue("wrong exception: $error", error is MusicKitException)
        assertEquals("PAGE_NOT_READY", (error as MusicKitException).code)
        assertEquals("a call that never went out was left pending", 0, bridge.pendingCount)
    }

    @Test
    fun `a dead renderer fails everything in flight`() = runTest {
        page.answer = null

        val waiting = async { runCatching { bridge.play() } }
        yield()
        assertEquals(1, bridge.pendingCount)

        bridge.failAll("The Apple Music page's renderer stopped because it crashed.")

        val error = waiting.await().exceptionOrNull()
        assertTrue("wrong exception: $error", error is MusicKitException)
        assertEquals("PAGE_GONE", (error as MusicKitException).code)
    }

    @Test
    fun `an event reaches the host with its name and payload`() {
        bridge.onMessage("""{"type":"event","name":"playbackStateDidChange","data":{"state":"playing"}}""")

        assertEquals(1, events.size)
        assertEquals("playbackStateDidChange", events.single().first)
        assertEquals("playing", BridgePayload.readString(events.single().second, "state"))
    }

    @Test
    fun `an event with no name is not raised`() {
        bridge.onMessage("""{"type":"event","data":{"state":"playing"}}""")

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a page log line is marked as the page's`() {
        bridge.onMessage("""{"type":"log","message":"setQueue exit: 3 item(s)"}""")

        assertEquals("page: setQueue exit: 3 item(s)", logged.single())
    }

    @Test
    fun `an unparseable message is ignored rather than thrown`() {
        bridge.onMessage("not json at all")
        bridge.onMessage("""{"noType":true}""")
        bridge.onMessage(null)

        assertEquals(3, logged.size)
        assertTrue(logged.all { it == "bridge: unparseable web message ignored" })
    }

    @Test
    fun `a message of an unknown type is logged and dropped`() {
        bridge.onMessage("""{"type":"telemetry","message":"hello"}""")

        assertEquals("bridge: unknown message type 'telemetry'", logged.single())
    }

    @Test
    fun `a result nobody is waiting for is logged as such`() {
        bridge.onMessage("""{"type":"result","id":"99","ok":true,"value":null}""")

        assertEquals("bridge: result #99 had no waiting caller (timed out?)", logged.single())
    }

    @Test
    fun `a successful call is traced in and out without its arguments`() = runTest {
        answerWith("""{"version":"3","storefrontId":"us","isAuthorized":false}""")

        bridge.configure("a-developer-token", "MusicBingo", "1.0")

        assertEquals("-> configure #1", logged.first())
        assertTrue(logged[1].startsWith("<- #1 configure ok "))
        assertTrue(
            "a token reached the transcript",
            logged.none { it.contains("a-developer-token") },
        )
    }
}
