package org.guaka.radio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocalAssistantConnectionTest {
    private var encryptedValue = ""
    private fun store() = LocalAssistantConnectionStore({ encryptedValue }, { encryptedValue = it })
    private val url = "http://192.168.1.20:8095"
    private val info = LocalAssistantInfo("server-one", "2.10.9")

    @Test fun persistsIdentityAndTokenTogetherAndReusesOnlyForSameServer() = runBlocking {
        store().save(url, " token ") { _, token, expected ->
            assertEquals("token", token); assertNull(expected); info
        }
        val reloaded = store()
        assertEquals(LocalAssistantConnection(url, "server-one", "token"), reloaded.current())
        reloaded.save("$url/", "") { _, token, expected ->
            assertEquals("token", token); assertEquals("server-one", expected); info
        }
        val result = runCatching { reloaded.save("http://192.168.1.21:8095", "") { _, _, _ -> fail("must not reuse token"); info } }
        assertTrue(result.isFailure)
        assertEquals(url, reloaded.current()?.url)
    }

    @Test fun authFailureDoesNotReplaceWorkingConnection() = runBlocking {
        val store = store()
        store.save(url, "good") { _, _, _ -> info }
        val original = encryptedValue
        assertTrue(runCatching { store.save("http://192.168.1.21:8095", "bad") { _, _, _ -> throw IOException("denied") } }.isFailure)
        assertEquals(original, encryptedValue)
    }

    @Test fun switchingServersStoresNewIdentityAfterValidation() = runBlocking {
        val store = store()
        store.save(url, "old") { _, _, _ -> info }
        store.save("http://192.168.1.21:8095", "new") { _, token, expected ->
            assertNull(expected); assertEquals("new", token); LocalAssistantInfo("server-two", "2.10.9")
        }
        assertEquals("server-two", store.current()?.serverId)
        assertEquals("new", store.current()?.token)
    }

    @Test fun forgetPreventsInFlightConnectionFromBeingSaved() = runBlocking {
        val store = store()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val pending = async { runCatching { store.save(url, "token") { _, _, _ -> started.complete(Unit); finish.await(); info } } }
        started.await()
        store.forget()
        finish.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertNull(store.current())
    }

    @Test fun cancellationNeverPersistsCredentials() = runBlocking {
        val store = store()
        val started = CompletableDeferred<Unit>()
        val pending = async { store.save(url, "token") { _, _, _ -> started.complete(Unit); CompletableDeferred<Unit>().await(); info } }
        started.await(); pending.cancelAndJoin()
        assertNull(store.current())
    }
}
