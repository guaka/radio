package org.guaka.radio

import org.junit.Assert.*
import org.junit.Test

class YouTubeIdsTest {
    private val id = "aqz-KE-bpKQ"
    @Test fun acceptsVideoLinksWithoutSavingTrackingParameters() {
        listOf(id, "https://youtu.be/$id?si=tracking", "https://www.youtube.com/watch?v=$id&t=15",
            "https://music.youtube.com/watch?v=$id", "https://youtube.com/shorts/$id").forEach {
            assertEquals(id, YouTubeIds.fromInput(it))
        }
    }
    @Test fun rejectsOtherHostsAndMalformedIds() {
        listOf("https://youtube.com.evil.test/watch?v=$id", "https://evil.test/$id",
            "https://user:pass@youtube.com/watch?v=$id", "http://youtu.be/$id",
            "https://youtube.com/playlist?list=$id", "too-short").forEach {
            assertNull(YouTubeIds.fromInput(it))
        }
    }
    @Test fun savesOnlyStableVideoIds() {
        assertTrue(ListeningStore.isStableMediaId("youtube:$id"))
        assertFalse(ListeningStore.isStableMediaId("youtube:$id?token=secret"))
        assertFalse(ListeningStore.isStableMediaId("youtube:https://youtu.be/$id"))
        assertEquals(listOf("youtube:$id"), ListeningStore.decodeIds(ListeningStore.encodeIds(listOf("youtube:$id"))))
    }
    @Test fun audioUrlsMustUseTheGoogleVideoHttpsHost() {
        assertTrue(YouTubeIds.isAudioHost("https://rr1---sn.example.googlevideo.com/videoplayback?expire=1"))
        assertFalse(YouTubeIds.isAudioHost("https://googlevideo.com.evil.test/audio"))
        assertFalse(YouTubeIds.isAudioHost("http://rr1.googlevideo.com/audio"))
        assertFalse(YouTubeIds.isAudioHost("https://user:pass@rr1.googlevideo.com/audio"))
    }
}
