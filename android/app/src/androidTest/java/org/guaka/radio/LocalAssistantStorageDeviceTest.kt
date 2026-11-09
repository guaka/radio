package org.guaka.radio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAssistantStorageDeviceTest {
    @Test fun directServerInfoIsReachableWhenExplicitlyConfigured() = runBlocking {
        val url = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("localAssistantUrl")
        org.junit.Assume.assumeTrue(!url.isNullOrBlank())
        val info = LocalAssistant(ApiClient("local-connection-test")).info(requireNotNull(url))
        assertTrue(info.serverId.isNotBlank())
        assertTrue(info.version.isNotBlank())
    }

    @Test fun localConnectionUsesProtectedStorageAndSurvivesRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = "local-assistant-storage-test"
        val credentials = CredentialStore(context)
        try {
            val store = LocalAssistantConnectionStore({ credentials.get(key) }, { credentials.set(key, it) })
            store.save("http://192.168.1.20:8095", "test-local-secret") { _, _, _ -> LocalAssistantInfo("test-server", "2.10.9") }
            val raw = context.getSharedPreferences("credentials", Context.MODE_PRIVATE).getString(key, "").orEmpty()
            assertFalse(raw.contains("test-local-secret"))
            assertFalse(raw.contains("test-server"))
            val recreatedCredentials = CredentialStore(context)
            val recreated = LocalAssistantConnectionStore({ recreatedCredentials.get(key) }, { recreatedCredentials.set(key, it) })
            assertEquals("test-server", recreated.current()?.serverId)
            assertEquals("test-local-secret", recreated.current()?.token)
            recreated.forget()
            assertNull(store.current())
        } finally { credentials.set(key, "") }
    }
}
