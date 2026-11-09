package org.guaka.radio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantDiscoveryDeviceTest {
    @Test fun cancelledScanCanBeRestartedAndRemainsBounded() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cancelled = async { discoverLocalAssistants(context) }
        delay(100)
        cancelled.cancelAndJoin()
        val results = withTimeout(12_000) { discoverLocalAssistants(context) }
        assertTrue(results.size <= 32)
        results.forEach { assertTrue(it.url.isNotBlank() && it.serverId.isNotBlank()) }
    }
}
