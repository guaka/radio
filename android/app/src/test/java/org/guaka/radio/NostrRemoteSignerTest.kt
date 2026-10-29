package org.guaka.radio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrRemoteSignerTest {
    private val remotePubkey = "11".repeat(32)
    private val clientSecret = "22".repeat(32)

    @Test fun rejectsInsecureRelayBeforeCreatingTransport() = runBlocking {
        val bunker = "bunker://$remotePubkey?relay=ws%3A%2F%2Frelay.example"
        val error = runCatching { NostrRemoteSigner.connect(bunker, clientSecret) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test fun rejectsBunkerWithoutRelayBeforeCreatingTransport() = runBlocking {
        val bunker = "bunker://$remotePubkey?secret=one-time-secret"
        val error = runCatching { NostrRemoteSigner.connect(bunker, clientSecret) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
