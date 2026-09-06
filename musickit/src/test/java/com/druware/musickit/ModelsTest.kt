package com.druware.musickit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The model helpers. Apple's artwork `url` is a template, so rendering it at a size is the only way
 * a caller gets something an image loader can fetch.
 */
class ModelsTest {

    private fun song(artworkUrlTemplate: String?) = MusicSong(
        id = "1441164589",
        title = "Here Comes the Sun",
        artistName = "The Beatles",
        albumName = "Abbey Road (Remastered)",
        duration = null,
        catalogId = "1441164589",
        libraryId = null,
        artworkUrlTemplate = artworkUrlTemplate,
    )

    @Test
    fun `renders the artwork url at the requested size`() {
        val rendered = song(
            "https://is1-ssl.mzstatic.com/image/thumb/Music112/v4/x.jpg/{w}x{h}bb.jpg",
        ).artworkUrl(300, 200)

        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music112/v4/x.jpg/300x200bb.jpg",
            rendered,
        )
    }

    @Test
    fun `renders square artwork when only a width is given`() {
        assertEquals(
            "https://example.test/600x600bb.jpg",
            song("https://example.test/{w}x{h}bb.jpg").artworkUrl(600),
        )
    }

    @Test
    fun `an artwork url with no placeholders is left alone`() {
        assertEquals(
            "https://example.test/cover.jpg",
            song("https://example.test/cover.jpg").artworkUrl(600),
        )
    }

    @Test
    fun `a song with no artwork renders no url`() {
        assertNull(song(null).artworkUrl(600))
    }
}
