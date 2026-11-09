package org.guaka.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkMediaIdTest {
    @Test fun artworkLookupIsLimitedToRepositoryMediaIds() {
        listOf("track:server:12", "album:server:12", "artist:server:12", "youtube:abcdefghijk", "radio:groovesalad").forEach {
            assertTrue(it, isArtworkMediaId(it))
        }
        listOf("file:/private/path", "https://example.test/cover.jpg", "unknown:1").forEach {
            assertFalse(it, isArtworkMediaId(it))
        }
    }
}
