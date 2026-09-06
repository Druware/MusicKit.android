package com.druware.musickit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge envelope and the payload readers, exercised without a WebView: what the page and the
 * host must agree on before either one is worth running.
 *
 * The correlation table (`PendingCalls`) is the transport half and lands with the bridge itself.
 */
class BridgeMessageTest {

    @Test
    fun `parses a successful result`() {
        val message = BridgeMessage.parse(
            """{"type":"result","id":"7","ok":true,"value":{"storefrontId":"us"},"error":null}""",
        )

        assertNotNull(message)
        assertEquals("result", message!!.type)
        assertEquals("7", message.id)
        assertTrue(message.ok)
        assertEquals(
            "us",
            (message.value!!.jsonObject["storefrontId"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `parses a failed result`() {
        val message = BridgeMessage.parse(
            """{"type":"result","id":"7","ok":false,"value":null,"error":"boom"}""",
        )

        assertNotNull(message)
        assertFalse(message!!.ok)
        assertEquals("boom", message.error)
    }

    @Test
    fun `parses an event and a log line`() {
        val raised = BridgeMessage.parse(
            """{"type":"event","name":"playbackStateDidChange","data":{"state":"playing"}}""",
        )
        val logged = BridgeMessage.parse("""{"type":"log","message":"musickit-host.js ready"}""")

        assertEquals("playbackStateDidChange", raised?.name)
        assertEquals("playing", BridgePayload.readString(raised?.data, "state"))
        assertEquals("musickit-host.js ready", logged?.message)
    }

    @Test
    fun `returns null for anything that is not an envelope`() {
        val notEnvelopes = listOf(
            "",
            "   ",
            "not json at all",
            // Well-formed JSON, but no type: not an envelope.
            """{"id":"7","ok":true}""",
        )

        for (raw in notEnvelopes) {
            assertNull("'$raw' should not have parsed", BridgeMessage.parse(raw))
        }
    }

    @Test
    fun `reads typed payloads out of an event`() {
        val data = Json.parseToJsonElement(
            """
            {"item":{"id":"i.abc","title":"Yesterday","artistName":"The Beatles",
             "catalogId":"1441164805","libraryId":"i.abc"}}
            """.trimIndent(),
        )

        val item = BridgePayload.readPayload<NowPlayingPayload>(data, "item")?.toModel()

        assertNotNull(item)
        assertEquals("i.abc", item!!.id)
        assertEquals("Yesterday", item.title)
        assertEquals("1441164805", item.catalogId)
        assertEquals("i.abc", item.libraryId)
    }

    @Test
    fun `a null now playing payload reads as nothing playing`() {
        val data = Json.parseToJsonElement("""{"item":null}""")

        assertNull(BridgePayload.readPayload<NowPlayingPayload>(data, "item"))
    }

    @Test
    fun `an item with no identifier is not a now playing item`() {
        val payload = NowPlayingPayload(null, "Yesterday", "The Beatles", null, null)

        assertNull(payload.toModel())
    }

    @Test
    fun `event payload readers are null safe about shape`() {
        val data = Json.parseToJsonElement("""{"count":3,"isAuthorized":true,"state":"playing"}""")

        assertEquals(3, BridgePayload.readInt(data, "count"))
        assertTrue(BridgePayload.readBool(data, "isAuthorized"))
        assertEquals("playing", BridgePayload.readString(data, "state"))

        // A missing count is the sentinel -1, not zero: "could not be read" is not "none".
        assertEquals(-1, BridgePayload.readInt(data, "absent"))
        assertEquals(-1, BridgePayload.readInt(data, "state"))
        assertFalse(BridgePayload.readBool(data, "absent"))
        assertNull(BridgePayload.readString(data, "count"))
        assertNull(BridgePayload.readString(null, "state"))
    }
}
