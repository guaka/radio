package org.guaka.radio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class SpeakerRequestEpochTest {
    @Test
    fun staleSuspendedLookupCannotPublishAfterNewOperation() = runBlocking {
        val epoch = SpeakerRequestEpoch()
        val first = epoch.next()
        val lookup = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            val result = lookup.await()
            epoch.publishIfCurrent(first) { published += result }
        }
        val second = epoch.next()
        lookup.complete("old speaker list")

        assertFalse(pending.await())
        assertTrue(published.isEmpty())
        assertTrue(epoch.publishIfCurrent(second) { published += "new speaker list" })
        org.junit.Assert.assertEquals(listOf("new speaker list"), published)
    }

    @Test
    fun dismissalPreventsPendingLookupFromPublishing() = runBlocking {
        val epoch = SpeakerRequestEpoch()
        val pending = epoch.next()
        val lookup = CompletableDeferred<String>()
        val published = mutableListOf<String>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            val result = lookup.await()
            epoch.publishIfCurrent(pending) { published += result }
        }

        epoch.next() // Dismissal advances the epoch even if the request ignores cancellation.
        lookup.complete("late result")

        assertFalse(request.await())
        assertTrue(published.isEmpty())
    }
}
