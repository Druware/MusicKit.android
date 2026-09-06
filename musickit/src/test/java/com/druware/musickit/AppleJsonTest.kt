package com.druware.musickit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Apple's JSON to this library's models. The fixtures are shaped like the real bodies — the search
 * one is the response the spike logged for "Beatles" on the `us` storefront.
 */
class AppleJsonTest {

    private val searchBody = """
        {
          "results": {
            "songs": {
              "href": "/v1/catalog/us/search?limit=2&term=Beatles&types=songs",
              "data": [
                {
                  "id": "1441164589",
                  "type": "songs",
                  "attributes": {
                    "albumName": "Abbey Road (Remastered)",
                    "artistName": "The Beatles",
                    "name": "Here Comes the Sun",
                    "durationInMillis": 185733,
                    "playParams": { "id": "1441164589", "kind": "song" }
                  }
                },
                {
                  "id": "1441164829",
                  "type": "songs",
                  "attributes": {
                    "albumName": "Rubber Soul",
                    "artistName": "The Beatles",
                    "name": "In My Life",
                    "durationInMillis": 146333,
                    "playParams": { "id": "1441164829", "kind": "song" }
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    private val libraryPlaylistsPage = """
        {
          "data": [
            {
              "id": "p.abcdefg",
              "type": "library-playlists",
              "attributes": {
                "name": "Bingo Night",
                "canEdit": true,
                "hasCatalog": false,
                "playParams": { "id": "p.abcdefg", "kind": "playlist", "isLibrary": true }
              }
            },
            {
              "id": "p.hijklmn",
              "type": "library-playlists",
              "attributes": {
                "name": "Party",
                "trackCount": 42,
                "playParams": { "id": "p.hijklmn", "kind": "playlist", "isLibrary": true }
              }
            }
          ],
          "meta": { "total": 102 },
          "next": "/v1/me/library/playlists?offset=100&limit=100"
        }
    """.trimIndent()

    private val libraryTracksBody = """
        {
          "data": [
            {
              "id": "i.gaXXXXXpo",
              "type": "library-songs",
              "attributes": {
                "albumName": "Abbey Road",
                "artistName": "The Beatles",
                "name": "Here Comes the Sun",
                "durationInMillis": 185733,
                "playParams": {
                  "id": "i.gaXXXXXpo",
                  "kind": "song",
                  "isLibrary": true,
                  "catalogId": "1441164589"
                }
              }
            },
            {
              "id": "i.no-catalog",
              "type": "library-songs",
              "attributes": {
                "artistName": "A Friend",
                "name": "Home Recording",
                "playParams": { "id": "i.no-catalog", "kind": "song", "isLibrary": true }
              }
            }
          ]
        }
    """.trimIndent()

    /**
     * One song of the live `us` search for "Beatles", verbatim, for the artwork the trimmed
     * [searchBody] above predates. The `url` is a template: the `{w}` and `{h}` are literally what
     * Apple sends, and a client that does not substitute them gets a 404.
     */
    private val artworkSearchBody = """
        {
          "results": {
            "songs": {
              "href": "/v1/catalog/us/search?limit=3&term=Beatles&types=songs",
              "data": [
                {
                  "id": "1441164589",
                  "type": "songs",
                  "href": "/v1/catalog/us/songs/1441164589",
                  "attributes": {
                    "albumName": "Abbey Road (Remastered)",
                    "artistName": "The Beatles",
                    "artwork": {
                      "bgColor": "121c25",
                      "height": 3000,
                      "textColor1": "f5f2dc",
                      "textColor2": "a6defd",
                      "textColor3": "c8c7b7",
                      "textColor4": "88b7d1",
                      "url": "https://is1-ssl.mzstatic.com/image/thumb/Music112/v4/df/db/61/dfdb615d-47f8-06e9-9533-b96daccc029f/18UMGIM31076.rgb.jpg/{w}x{h}bb.jpg",
                      "width": 3000
                    },
                    "composerName": "George Harrison",
                    "discNumber": 1,
                    "durationInMillis": 185733,
                    "genreNames": [
                      "Rock",
                      "Music"
                    ],
                    "hasLyrics": true,
                    "isAppleDigitalMaster": true,
                    "isrc": "GBAYE0601696",
                    "name": "Here Comes the Sun",
                    "playParams": {
                      "id": "1441164589",
                      "kind": "song"
                    },
                    "previews": [
                      {
                        "url": "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview211/v4/cd/7d/83/cd7d834f-34bf-7be0-1647-0fb0920d25a7/mzaf_5281747653026802926.plus.aac.p.m4a"
                      }
                    ],
                    "releaseDate": "1969-09-26",
                    "trackNumber": 7,
                    "url": "https://music.apple.com/us/album/here-comes-the-sun/1441164426?i=1441164589"
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `reads the artwork url template verbatim`() {
        val songs = AppleJson.readSearchSongs(parse(artworkSearchBody))

        // Byte for byte what Apple sent, placeholders intact: substituting is the caller's job.
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music112/v4/df/db/61/" +
                "dfdb615d-47f8-06e9-9533-b96daccc029f/18UMGIM31076.rgb.jpg/{w}x{h}bb.jpg",
            songs[0].artworkUrlTemplate,
        )
    }

    @Test
    fun `an item with no artwork object has no artwork url template`() {
        val songs = AppleJson.readSongs(parse(libraryTracksBody))

        assertNull(songs[0].artworkUrlTemplate)
    }

    @Test
    fun `an artwork object with no url has no artwork url template`() {
        val songs = AppleJson.readSongs(
            parse(
                """{"data":[{"id":"1","type":"songs","attributes":""" +
                    """{"name":"Untitled","artwork":{"width":3000,"height":3000}}}]}""",
            ),
        )

        assertNull(songs[0].artworkUrlTemplate)
    }

    @Test
    fun `a json null artwork url does not read as the word null`() {
        val songs = AppleJson.readSongs(
            parse("""{"data":[{"id":"1","type":"songs","attributes":{"artwork":{"url":null}}}]}"""),
        )

        assertNull(songs[0].artworkUrlTemplate)
    }

    @Test
    fun `reads catalog search songs`() {
        val songs = AppleJson.readSearchSongs(parse(searchBody))

        assertEquals(2, songs.size)
        assertEquals("1441164589", songs[0].id)
        assertEquals("Here Comes the Sun", songs[0].title)
        assertEquals("The Beatles", songs[0].artistName)
        assertEquals("Abbey Road (Remastered)", songs[0].albumName)
        assertEquals(185733.milliseconds, songs[0].duration)

        // A catalog song's playParams name only itself, and never a library identifier.
        assertEquals("1441164589", songs[0].catalogId)
        assertNull(songs[0].libraryId)
    }

    @Test
    fun `a search that found no songs reads as an empty list`() {
        assertTrue(AppleJson.readSearchSongs(parse("""{"results":{}}""")).isEmpty())
    }

    @Test
    fun `reads library playlists and their track counts`() {
        val playlists = AppleJson.readPlaylists(parse(libraryPlaylistsPage))

        assertEquals(2, playlists.size)
        assertEquals("p.abcdefg", playlists[0].id)
        assertEquals("Bingo Night", playlists[0].name)
        assertTrue(playlists[0].isLibrary)
        assertNull(playlists[0].trackCount)
        assertEquals(42, playlists[1].trackCount)
    }

    @Test
    fun `a catalog playlist is not a library one`() {
        val playlists = AppleJson.readPlaylists(
            parse("""{"data":[{"id":"pl.123","type":"playlists","attributes":{"name":"Today's Hits"}}]}"""),
        )

        assertFalse(playlists[0].isLibrary)
    }

    @Test
    fun `reads library tracks with both identifiers`() {
        val songs = AppleJson.readSongs(parse(libraryTracksBody))

        assertEquals(2, songs.size)

        // The pair that makes the now-playing resolver possible.
        assertEquals("i.gaXXXXXpo", songs[0].id)
        assertEquals("1441164589", songs[0].catalogId)
        assertEquals("i.gaXXXXXpo", songs[0].libraryId)

        // A library track with no catalog counterpart has no catalog identifier to offer.
        assertEquals("i.no-catalog", songs[1].id)
        assertNull(songs[1].catalogId)
        assertEquals("i.no-catalog", songs[1].libraryId)
        assertNull(songs[1].albumName)
        assertNull(songs[1].duration)
    }

    @Test
    fun `an item with no play parameters still reads`() {
        val songs = AppleJson.readSongs(
            parse("""{"data":[{"id":"123","type":"songs","attributes":{"name":"Untitled"}}]}"""),
        )

        assertEquals("123", songs[0].id)
        assertEquals("", songs[0].artistName)
        assertNull(songs[0].catalogId)
        assertNull(songs[0].libraryId)
    }

    @Test
    fun `an item with no id is dropped rather than faked`() {
        assertTrue(
            AppleJson.readSongs(
                parse("""{"data":[{"type":"songs","attributes":{"name":"Nameless"}}]}"""),
            ).isEmpty(),
        )
    }

    @Test
    fun `reads the paging cursor and its absence`() {
        assertEquals(
            "/v1/me/library/playlists?offset=100&limit=100",
            AppleJson.readNext(parse(libraryPlaylistsPage)),
        )
        assertNull(AppleJson.readNext(parse(libraryTracksBody)))
    }

    @Test
    fun `splits a cursor into a path and its parameters`() {
        val (path, parameters) = AppleJson.splitNext("/v1/me/library/playlists?offset=100&limit=100")

        assertEquals("/v1/me/library/playlists", path)
        assertEquals("100", parameters["offset"])
        assertEquals("100", parameters["limit"])
    }

    @Test
    fun `splits a cursor that carries no query`() {
        val (path, parameters) = AppleJson.splitNext("/v1/me/library/playlists")

        assertEquals("/v1/me/library/playlists", path)
        assertTrue(parameters.isEmpty())
    }

    @Test
    fun `decodes escaped cursor parameters`() {
        val (_, parameters) = AppleJson.splitNext("/v1/catalog/us/search?term=The%20Beatles&types=songs")

        assertEquals("The Beatles", parameters["term"])
    }

    @Test
    fun `an apple error envelope becomes a sentence`() {
        val body = parse(
            """
            {"errors":[{"id":"X","title":"Forbidden","detail":"No active subscription.","status":"403","code":"40300"}]}
            """.trimIndent(),
        )

        val (message, code) = AppleJson.describeFailure("play that track", 403, body, "MKError: forbidden")

        assertTrue(message, message.contains("play that track"))
        assertTrue(message, message.contains("HTTP 403"))
        assertTrue(message, message.contains("Forbidden: No active subscription."))

        // Apple's own error code wins over the HTTP status when both exist.
        assertEquals("40300", code)
    }

    @Test
    fun `an apple error envelope with a detail but no title does not say null`() {
        val body = parse(
            """
            {"errors":[{"id":"X","detail":"No active subscription.","status":"403","code":"40300"}]}
            """.trimIndent(),
        )

        val (message, code) = AppleJson.describeFailure("play that track", 403, body, "MKError: forbidden")

        assertFalse(message, message.contains("null"))
        assertEquals(
            "Apple Music couldn't play that track (HTTP 403). : No active subscription.",
            message,
        )
        assertEquals("40300", code)
    }

    @Test
    fun `a failure with no envelope falls back to what the page said`() {
        val (message, code) = AppleJson.describeFailure(
            "load your library playlists",
            500,
            null,
            "TypeError: boom",
        )

        assertTrue(message, message.contains("TypeError: boom"))
        assertEquals("500", code)
    }

    @Test
    fun `a failure with nothing at all still names what was attempted`() {
        val (message, code) = AppleJson.describeFailure("search the catalogue", null, null, null)

        assertTrue(message, message.contains("search the catalogue"))
        assertTrue(message, message.contains("HTTP ?"))
        assertNull(code)
    }

    private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)
}
