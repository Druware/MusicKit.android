package com.druware.musickit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A `next` cursor split into the path and the query parameters to send alongside it. */
internal data class NextPage(val path: String, val parameters: Map<String, String>)

/** An unsuccessful API answer, turned into something worth showing a user. */
internal data class FailureDescription(val message: String, val code: String?)

/**
 * Turns Apple Music API bodies into this library's models, and reads the paging cursor.
 *
 * Kept apart from the bridge so the shapes can be asserted against canned bodies without a WebView.
 * Everything here is forgiving: Apple omits attributes freely, and a missing album is not a fault.
 */
internal object AppleJson {

    /**
     * Reads the `data` array of a playlists response.
     *
     * @param body the whole response body.
     * @return every playlist in the array, in Apple's order.
     */
    fun readPlaylists(body: JsonElement): List<MusicPlaylist> =
        items(body).mapNotNull(::readPlaylist)

    /**
     * Reads one playlist resource.
     *
     * @param item one element of a `data` array.
     * @return the playlist, or null when it carries no id.
     */
    fun readPlaylist(item: JsonElement): MusicPlaylist? {
        val id = stringOf(item, "id")?.takeIf { it.isNotEmpty() } ?: return null

        val attributes = propertyOf(item, "attributes")
        val playParams = attributes?.let { propertyOf(it, "playParams") }
        val isLibrary = stringOf(item, "type")?.startsWith("library-") == true ||
            (playParams != null && boolOf(playParams, "isLibrary"))

        return MusicPlaylist(
            id = id,
            name = if (attributes == null) "" else stringOf(attributes, "name") ?: "",
            trackCount = if (attributes == null) null else intOf(attributes, "trackCount"),
            isLibrary = isLibrary,
        )
    }

    /**
     * Reads the `data` array of a songs or playlist-tracks response.
     *
     * @param body the whole response body.
     * @return every song in the array, in Apple's order.
     */
    fun readSongs(body: JsonElement): List<MusicSong> = items(body).mapNotNull(::readSong)

    /**
     * Reads the songs out of a catalog search response.
     *
     * @param body the whole response body, whose songs sit under `results.songs.data`.
     * @return every song found, in Apple's order.
     */
    fun readSearchSongs(body: JsonElement): List<MusicSong> {
        val results = propertyOf(body, "results") ?: return emptyList()
        val songs = propertyOf(results, "songs") ?: return emptyList()
        return readSongs(songs)
    }

    /**
     * Reads one song resource.
     *
     * @param item one element of a `data` array.
     * @return the song, or null when it carries no id.
     */
    fun readSong(item: JsonElement): MusicSong? {
        val id = stringOf(item, "id")?.takeIf { it.isNotEmpty() } ?: return null

        // Falls back to the item itself when there is no attributes object: defensive, though real
        // Apple responses always nest them.
        val attributes = propertyOf(item, "attributes") ?: item
        val playParams = propertyOf(attributes, "playParams")
        val (catalogId, libraryId) = readIdentifiers(playParams)
        // Kept as Apple wrote it, {w} and {h} placeholders and all; MusicSong.artworkUrl renders it.
        val artwork = propertyOf(attributes, "artwork")

        return MusicSong(
            id = id,
            title = stringOf(attributes, "name") ?: stringOf(attributes, "title") ?: "",
            artistName = stringOf(attributes, "artistName") ?: "",
            albumName = stringOf(attributes, "albumName"),
            duration = intOf(attributes, "durationInMillis")?.milliseconds,
            catalogId = catalogId,
            libraryId = libraryId,
            artworkUrlTemplate = artwork?.let { stringOf(it, "url") },
        )
    }

    /**
     * Reads the catalog and library identifiers out of an item's `playParams`.
     *
     * A library song carries `{ id: "i.…", kind: "song", isLibrary: true, catalogId: "144…" }`; a
     * catalog song carries `{ id: "144…", kind: "song" }`. Both halves matter: the now-playing item
     * can name a track by whichever one MusicKit felt like using.
     *
     * This function is mirrored inline by `summarise()` in `musickit-host.js`. The two must never
     * diverge.
     *
     * @param playParams the `playParams` object, or null when the item has none.
     * @return the catalog identifier and the library identifier, either of which may be null.
     */
    fun readIdentifiers(playParams: JsonElement?): Pair<String?, String?> {
        if (playParams !is JsonObject) {
            return null to null
        }

        val id = stringOf(playParams, "id")
        val kind = stringOf(playParams, "kind")
        val isSong = kind == null || kind == "song"
        val isLibrary = boolOf(playParams, "isLibrary")

        val catalogId = stringOf(playParams, "catalogId")
            ?: if (isSong && !isLibrary) id else null
        val libraryId = if (isSong && isLibrary) id else null

        return catalogId to libraryId
    }

    /**
     * Reads the `next` cursor of a paged response.
     *
     * @param body the whole response body.
     * @return Apple's next path, e.g. `/v1/me/library/playlists?offset=100`, or null on the last page.
     */
    fun readNext(body: JsonElement): String? = stringOf(body, "next")

    /**
     * Splits a `next` cursor into the path and the query parameters to send alongside it.
     *
     * `music.api.music` builds its own query string from the parameters argument, so handing it a
     * path that already carries one is asking for two question marks. Splitting here keeps the page
     * side dumb and makes the paging assertable in a unit test.
     *
     * @param next the cursor exactly as Apple wrote it.
     * @return the path, and the decoded query parameters.
     */
    fun splitNext(next: String): NextPage {
        val mark = next.indexOf('?')
        if (mark < 0) {
            return NextPage(next, emptyMap())
        }

        val parameters = LinkedHashMap<String, String>()
        for (pair in next.substring(mark + 1).split('&')) {
            if (pair.isEmpty()) {
                continue
            }

            val equals = pair.indexOf('=')
            if (equals < 0) {
                parameters[unescapeDataString(pair)] = ""
            } else {
                parameters[unescapeDataString(pair.substring(0, equals))] =
                    unescapeDataString(pair.substring(equals + 1))
            }
        }

        return NextPage(next.substring(0, mark), parameters)
    }

    /**
     * Turns an unsuccessful API answer into a sentence worth showing a user.
     *
     * @param what what was being attempted, e.g. "load your library playlists".
     * @param status the HTTP status Apple answered with, when the page could read one.
     * @param body Apple's response body, which carries an `errors` array when it is an API error.
     * @param pageError whatever the page could say about the failure.
     * @return the message and the code to put on a [MusicKitException].
     */
    fun describeFailure(
        what: String,
        status: Int?,
        body: JsonElement?,
        pageError: String?,
    ): FailureDescription {
        val code = status?.toString()

        if (body != null) {
            val errors = propertyOf(body, "errors")
            if (errors is JsonArray && errors.isNotEmpty()) {
                val first = errors[0]
                val title = stringOf(first, "title")
                val detail = stringOf(first, "detail")
                val appleCode = stringOf(first, "code")
                // C# interpolates a null title as "", not as the four letters "null".
                val sentence =
                    if (detail.isNullOrBlank()) title else "${title.orEmpty()}: $detail"
                if (!sentence.isNullOrBlank()) {
                    return FailureDescription(
                        "Apple Music couldn't $what (HTTP ${code ?: "?"}). $sentence",
                        appleCode ?: code,
                    )
                }
            }
        }

        return if (pageError.isNullOrBlank()) {
            FailureDescription("Apple Music couldn't $what (HTTP ${code ?: "?"}).", code)
        } else {
            FailureDescription("Apple Music couldn't $what: $pageError", code)
        }
    }

    private fun items(body: JsonElement): List<JsonElement> =
        (propertyOf(body, "data") as? JsonArray) ?: emptyList()

    /**
     * The named property, or null when the element is not an object, the property is absent, or its
     * value is JSON `null`. JSON null and "absent" mean the same thing everywhere in this file.
     */
    private fun propertyOf(element: JsonElement, name: String): JsonElement? =
        (element as? JsonObject)?.get(name)?.takeIf { it !is JsonNull }

    private fun stringOf(element: JsonElement, name: String): String? =
        (propertyOf(element, name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun intOf(element: JsonElement, name: String): Int? =
        (propertyOf(element, name) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    /** True only for a real JSON `true`; a missing, false, string or numeric value all read false. */
    private fun boolOf(element: JsonElement, name: String): Boolean =
        (propertyOf(element, name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    /**
     * Percent-decodes one query token, the way .NET's `Uri.UnescapeDataString` does.
     *
     * Deliberately not `java.net.URLDecoder`, which also turns `+` into a space. Apple's cursors are
     * RFC 3986 URI components, where a literal `+` means a plus sign and a space is always `%20`.
     */
    private fun unescapeDataString(value: String): String {
        if (!value.contains('%')) {
            return value
        }

        val out = StringBuilder(value.length)
        val pending = ByteArrayOutputStream()

        fun flush() {
            if (pending.size() > 0) {
                out.append(String(pending.toByteArray(), Charsets.UTF_8))
                pending.reset()
            }
        }

        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%' && index + 2 < value.length) {
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    pending.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }

            flush()
            out.append(character)
            index++
        }

        flush()
        return out.toString()
    }
}
