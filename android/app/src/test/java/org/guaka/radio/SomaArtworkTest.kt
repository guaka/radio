package org.guaka.radio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SomaArtworkTest {
    @Test fun prefersLargeArtworkAndFallsBackToSmallImage() {
        val result = SomaArtwork.parse(JSONObject("""{"channels":[
            {"id":"doomed","largeimage":"https://api.somafm.com/logos/256/doomed256.png?v=1","image":"https://api.somafm.com/logos/120/doomed120.png"},
            {"id":"fluid","image":"https://api.somafm.com/logos/120/fluid120.jpg"}
        ]}"""))
        assertEquals("https://api.somafm.com/logos/256/doomed256.png?v=1", result["doomed"])
        assertEquals("https://api.somafm.com/logos/120/fluid120.jpg", result["fluid"])
    }

    @Test fun rejectsUnsafeIdsAndArtworkOrigins() {
        val result = SomaArtwork.parse(JSONObject("""{"channels":[
            {"id":"bad/id","largeimage":"https://api.somafm.com/logos/256/no.png"},
            {"id":"insecure","largeimage":"http://api.somafm.com/logos/256/no.png"},
            {"id":"foreign","largeimage":"https://evil.example/logos/256/no.png"},
            {"id":"userinfo","largeimage":"https://user@api.somafm.com/logos/256/no.png"}
        ]}"""))
        assertTrue(result.isEmpty())
        assertFalse(result.values.any { it.contains("evil.example") })
    }
}
