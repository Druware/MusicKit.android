package com.druware.musickit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds

/**
 * The correlation table. Every call is answered by exactly the result that names it, and every exit
 * from a wait — answer, failure, timeout, cancellation, a dead page — leaves nothing behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingCallsTest {

    private val calls = PendingCalls()

    private fun result(id: String, ok: Boolean, value: String? = null, error: String? = null) =
        BridgeMessage(
            type = "result",
            id = id,
            ok = ok,
            value = value?.let { JsonPrimitive(it) },
            error = error,
        )

    @Test
    fun `ids are unique and monotonic`() {
        val ids = List(100) { calls.nextId() }

        assertEquals("ids repeated", ids.size, ids.toSet().size)
        assertEquals("ids are not the counter the page echoes back", "1", ids.first())
        assertEquals("100", ids.last())
    }

    @Test
    fun `a result completes the call it names and no other`() = runTest {
        val first = calls.nextId()
        val second = calls.nextId()
        val firstCall = calls.register(first)
        val secondCall = calls.register(second)

        val waitingFirst = async { calls.await(first, firstCall, 30.seconds) }
        val waitingSecond = async { calls.await(second, secondCall, 30.seconds) }
        yield()

        assertTrue(calls.complete(result(second, ok = true, value = "second")))
        assertTrue(calls.complete(result(first, ok = true, value = "first")))

        assertEquals(JsonPrimitive("first"), waitingFirst.await())
        assertEquals(JsonPrimitive("second"), waitingSecond.await())
        assertEquals("the table did not empty", 0, calls.count)
    }

    @Test
    fun `a result for a call nobody is waiting on is reported as unmatched`() {
        assertFalse("an unknown id claimed a caller", calls.complete(result("404", ok = true)))
    }

    @Test
    fun `a result carrying no id matches nothing`() {
        assertFalse(calls.complete(BridgeMessage(type = "result", ok = true)))
    }

    @Test
    fun `a successful result with no value comes back as JSON null rather than nothing`() = runTest {
        val id = calls.nextId()
        val call = calls.register(id)
        val waiting = async { calls.await(id, call, 30.seconds) }
        yield()

        calls.complete(BridgeMessage(type = "result", id = id, ok = true, value = null))

        assertEquals(JsonNull, waiting.await())
    }

    @Test
    fun `a failed result becomes a page error carrying what the page said`() = runTest {
        val id = calls.nextId()
        val call = calls.register(id)
        // The failure is caught inside the coroutine: an async that fails takes the whole test
        // scope down with it, whereas the point here is the exception itself.
        val waiting = async { runCatching { calls.await(id, call, 30.seconds) } }
        yield()

        calls.complete(result(id, ok = false, error = "MusicKit is not configured yet."))

        val error = waiting.await().exceptionOrNull()
        assertTrue("wrong exception: $error", error is MusicKitException)
        assertEquals("MusicKit is not configured yet.", (error as MusicKitException).message)
        assertEquals("PAGE_ERROR", error.code)
    }

    @Test
    fun `a failed result that says nothing still explains itself`() = runTest {
        val id = calls.nextId()
        val call = calls.register(id)
        val waiting = async { runCatching { calls.await(id, call, 30.seconds) } }
        yield()

        calls.complete(result(id, ok = false, error = null))

        val error = waiting.await().exceptionOrNull() as MusicKitException
        assertEquals("The MusicKit page reported an unspecified error.", error.message)
        assertEquals("PAGE_ERROR", error.code)
    }

    @Test
    fun `a page that never answers times out, and a timeout is not a cancellation`() = runTest {
        val id = calls.nextId()
        val call = calls.register(id)

        val waiting = async { runCatching { calls.await(id, call, 30.seconds) } }
        advanceTimeBy(31.seconds)

        val error = waiting.await().exceptionOrNull()
        assertTrue("wrong exception: $error", error is TimeoutException)
        assertFalse(
            "a timeout must not read as a cancellation, or a caller cannot tell them apart",
            error is CancellationException,
        )
        assertTrue("the id names the call", error!!.message!!.contains("'$id'"))
        assertTrue("the message names the timeout", error.message!!.contains("30 s"))
        assertEquals("a timed-out call was left in the table", 0, calls.count)
    }

    @Test
    fun `a cancelled caller is cancelled, not timed out`() = runTest {
        val id = calls.nextId()
        val call = calls.register(id)

        var seen: Throwable? = null
        val job = launch {
            try {
                calls.await(id, call, 30.seconds)
            } catch (error: Throwable) {
                seen = error
                throw error
            }
        }

        yield()
        job.cancel()
        job.join()

        assertTrue("wrong exception: $seen", seen is CancellationException)
        assertFalse("a cancellation must not read as a timeout", seen is TimeoutException)
        assertEquals("a cancelled call was left in the table", 0, calls.count)
    }

    @Test
    fun `a dead page fails everything still waiting`() = runTest {
        val registered = List(3) { calls.nextId().let { id -> id to calls.register(id) } }
        val waiting = registered.map { (id, call) ->
            async { runCatching { calls.await(id, call, 30.seconds) } }
        }
        yield()

        calls.failAll("The MusicKit host was disposed.")

        for (call in waiting) {
            val error = call.await().exceptionOrNull()
            assertTrue("wrong exception: $error", error is MusicKitException)
            assertEquals("PAGE_GONE", (error as MusicKitException).code)
            assertEquals("The MusicKit host was disposed.", error.message)
        }

        assertEquals(0, calls.count)
    }

    @Test
    fun `registering the same id twice is refused rather than stranding the first caller`() {
        val id = calls.nextId()
        calls.register(id)

        try {
            calls.register(id)
            fail("a duplicate id was accepted")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains(id))
        }
    }

    @Test
    fun `a registered call that is never awaited can be forgotten`() {
        val id = calls.nextId()
        calls.register(id)
        assertEquals(1, calls.count)

        calls.forget(id)

        assertEquals(0, calls.count)
        assertFalse("a forgotten call still claimed its result", calls.complete(result(id, ok = true)))
    }

    @Test
    fun `a result that arrives before the caller starts waiting is not lost`() = runTest {
        val id = calls.nextId()
        val call = calls.register(id)

        // The page answered while the call was still on its way out — which is why registering
        // hands the reservation back rather than leaving it to be looked up again later.
        assertTrue(calls.complete(result(id, ok = true, value = "early")))
        assertEquals(0, calls.count)

        assertEquals(JsonPrimitive("early"), calls.await(id, call, 30.seconds))
    }

    @Test
    fun `an object result survives the round trip whole`() = runTest {
        val id = calls.nextId()
        val call = calls.register(id)
        val waiting = async { calls.await(id, call, 30.seconds) }
        yield()

        val value = buildJsonObject {
            put("storefrontId", "us")
            put("isAuthorized", true)
        }

        calls.complete(BridgeMessage(type = "result", id = id, ok = true, value = value))

        assertEquals(value, waiting.await())
        assertNotEquals(JsonNull, waiting.await())
    }
}
