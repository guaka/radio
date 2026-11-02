package org.guaka.radio

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AssistantRemoteDeviceTest {
    /** Opt-in test: proves signaling, identity verification, DTLS and API auth without real credentials. */
    @Test fun remoteServerRejectsMissingTokenAfterVerifiedConnection() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("assistantRemoteId").orEmpty()
        assumeTrue(id.isNotBlank())
        val remote = AssistantRemote(InstrumentationRegistry.getInstrumentation().targetContext)
        val error = runCatching { remote.command(id, "", "players/all") }.exceptionOrNull()
        assertTrue("Expected an authentication rejection, got ${error?.javaClass?.simpleName}: ${error?.message}",
            error?.message == "Music Assistant access denied. Check the access token.")
    }
}
