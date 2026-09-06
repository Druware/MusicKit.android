package com.druware.musickit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * One message posted by the page, in the single envelope shape `musickit-host.js` uses.
 *
 * `type` is `result` (the answer to one `invoke`, carrying its `id`), `event` (an unsolicited
 * MusicKit or page event) or `log` (a line for the transcript).
 */
internal data class BridgeMessage(
    /** "result", "event" or "log". */
    val type: String? = null,

    /** The correlation id of the call this answers; set only on a result. */
    val id: String? = null,

    /** Whether the call succeeded; meaningful only on a result. */
    val ok: Boolean = false,

    /** The call's return value; meaningful only on a successful result. */
    val value: JsonElement? = null,

    /** Why the call failed; meaningful only on a failed result. */
    val error: String? = null,

    /** The event name; set only on an event. */
    val name: String? = null,

    /** The event payload; set only on an event. */
    val data: JsonElement? = null,

    /** The line to log; set only on a log message. */
    val message: String? = null,
) {
    companion object {
        /**
         * Parses one posted string, returning null when it is not a bridge envelope.
         *
         * @param json the raw string the page handed over.
         * @return the parsed message, or null when the string is not a JSON object or carries no
         *   `type`. Malformed JSON is ignored rather than thrown.
         */
        fun parse(json: String?): BridgeMessage? {
            if (json.isNullOrBlank()) {
                return null
            }

            val root = try {
                BridgePayload.json.parseToJsonElement(json)
            } catch (_: SerializationException) {
                return null
            }

            val envelope = root as? JsonObject ?: return null

            // Any JSON object lacking a type is not an envelope, however well formed it is.
            val type = envelope.stringOrNull("type") ?: return null

            return BridgeMessage(
                type = type,
                id = envelope.stringOrNull("id"),
                ok = envelope.boolOrNull("ok"),
                value = envelope.elementOrNull("value"),
                error = envelope.stringOrNull("error"),
                name = envelope.stringOrNull("name"),
                data = envelope.elementOrNull("data"),
                message = envelope.stringOrNull("message"),
            )
        }

        /** The C# decoder reads property names case-insensitively; this keeps that contract. */
        private fun JsonObject.elementOrNull(name: String): JsonElement? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.takeIf { it !is JsonNull }

        private fun JsonObject.stringOrNull(name: String): String? =
            (elementOrNull(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.boolOrNull(name: String): Boolean =
            (elementOrNull(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
    }
}

/** What the page says about itself and MusicKit. */
@Serializable
internal data class PageStatus(
    @SerialName("musicKitLoaded") val musicKitLoaded: Boolean = false,
    @SerialName("version") val version: String? = null,
    @SerialName("configured") val configured: Boolean = false,
    @SerialName("isAuthorized") val isAuthorized: Boolean = false,
    @SerialName("storefrontId") val storefrontId: String? = null,
)

/**
 * What a `configure` or `reconfigure` answered with.
 *
 * [developerTokenMatches] is null after a first `configure`: only `reconfigure` reports it, because
 * only a rotation has an old token to compare against.
 */
@Serializable
internal data class ConfigureResult(
    @SerialName("version") val version: String? = null,
    @SerialName("storefrontId") val storefrontId: String? = null,
    @SerialName("isAuthorized") val isAuthorized: Boolean = false,
    @SerialName("developerTokenMatches") val developerTokenMatches: Boolean? = null,
)

/**
 * One Apple Music API answer, as the page reports it.
 *
 * An API failure is never a bridge failure: the status and the body come back so the host can tell
 * a 404 — which is an answer — from a 403, which is not.
 */
@Serializable
internal data class ApiResult(
    @SerialName("status") val status: Int? = null,
    @SerialName("body") val body: JsonElement? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("via") val via: String? = null,
) {
    val isSuccess: Boolean
        get() = error == null && status != null && status in 200..299

    /**
     * Turns a JSON `null` body into an absent one, so `body == null` means "nothing came back"
     * rather than "a JsonNull came back".
     */
    fun normalised(): ApiResult = if (body is JsonNull) copy(body = null) else this
}

/** What a `skipToNext` answered with. */
@Serializable
internal data class SkipResult(
    @SerialName("playbackState") val playbackState: String? = null,
    @SerialName("item") val item: NowPlayingPayload? = null,
)

/** The page's `summarise()` shape: what MusicKit says is playing, before it becomes a model. */
@Serializable
internal data class NowPlayingPayload(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("catalogId") val catalogId: String? = null,
    @SerialName("libraryId") val libraryId: String? = null,
) {
    /**
     * @return the model, or null when the item carries no identifier at all — that is not
     *   "something is playing".
     */
    fun toModel(): NowPlayingItem? =
        id?.takeIf { it.isNotEmpty() }?.let { NowPlayingItem(it, title, artistName, catalogId, libraryId) }
}

/**
 * Reads values out of an event's `data` object.
 *
 * Every reader is null-safe against a payload that is not an object, or a property that is absent
 * or of the wrong type: the page is allowed to change shape without this side throwing.
 */
internal object BridgePayload {

    val json = Json { ignoreUnknownKeys = true }

    /** Deserializes the named property, or null when it is absent or JSON `null`. */
    inline fun <reified T> readPayload(data: JsonElement?, property: String): T? {
        val element = property(data, property) ?: return null
        return try {
            json.decodeFromJsonElement<T>(element)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The property's string value, or null unless it really is a JSON string. */
    fun readString(data: JsonElement?, property: String): String? =
        (property(data, property) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * The property's int value, or the sentinel `-1` when it is absent or is not a number. The
     * sentinel is deliberate: a count that could not be read is reported as `-1 item(s)` rather
     * than silently as none.
     */
    fun readInt(data: JsonElement?, property: String): Int =
        (property(data, property) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: -1

    /** True only for a real JSON `true`; absent, false, and every other shape all read false. */
    fun readBool(data: JsonElement?, property: String): Boolean =
        (property(data, property) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    /** Exposed only so the inline [readPayload] can reach it. */
    fun property(data: JsonElement?, name: String): JsonElement? =
        (data as? JsonObject)?.get(name)?.takeIf { it !is JsonNull }
}
