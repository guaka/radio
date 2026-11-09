package org.guaka.radio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PlexArtworkFallbackTest {
    private fun entry(json: String): Entry = IntegrationParser.mediaEntry(JSONObject(json), "server", "music")!!

    @Test fun keepsItemArtworkAheadOfParentAndGrandparentArtwork() {
        assertEquals("/track.jpg", entry("""{"type":"track","ratingKey":"1","thumb":"/track.jpg","parentThumb":"/album.jpg","grandparentThumb":"/artist.jpg"}""").artwork)
    }

    @Test fun usesAlbumArtworkWhenTrackArtworkIsMissing() {
        assertEquals("/album.jpg", entry("""{"type":"track","ratingKey":"1","parentThumb":"/album.jpg","grandparentThumb":"/artist.jpg"}""").artwork)
    }

    @Test fun usesArtistArtworkWhenTrackAndAlbumArtworkAreMissing() {
        assertEquals("/artist.jpg", entry("""{"type":"track","ratingKey":"1","grandparentThumb":"/artist.jpg"}""").artwork)
    }

    @Test fun skipsUnsafeArtworkCandidatesAndContinuesToSafeFallback() {
        listOf("https://attacker.example/image.jpg", "//attacker.example/image.jpg", "/image.jpg?token=secret", "/image.jpg#fragment", "/image.jpg?X-Plex-Token=secret")
            .forEach { unsafe ->
                val escaped = JSONObject.quote(unsafe)
                assertEquals("/album.jpg", entry("""{"type":"track","ratingKey":"1","thumb":$escaped,"parentThumb":"/album.jpg"}""").artwork)
            }
    }
}
