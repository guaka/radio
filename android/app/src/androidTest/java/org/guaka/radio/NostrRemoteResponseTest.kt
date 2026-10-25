package org.guaka.radio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NostrRemoteResponseTest {
    @Test fun preservesNip46AuthUrlMarkerAndUrlTogether() {
        val response = parseBunkerResponse(
            """{"id":"request-7","result":"auth_url","error":"https://signer.example/approve"}""",
        )

        assertEquals("request-7", response.id)
        assertEquals("auth_url", response.result)
        assertEquals("https://signer.example/approve", response.error)
    }

    @Test fun rejectsOversizedRemoteResponseBeforeProcessing() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBunkerResponse("x".repeat(64 * 1024 + 1))
        }
    }
}
