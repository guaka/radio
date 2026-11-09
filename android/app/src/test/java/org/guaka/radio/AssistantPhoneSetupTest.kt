package org.guaka.radio

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AssistantPhoneSetupTest {
    private val http = OkHttpClient()
    @Test fun acceptsCredentialsOnceWithoutEchoingThem() {
        val received = LinkedBlockingQueue<AssistantPhoneDetails>()
        AssistantPhoneSetup(InetAddress.getByName("127.0.0.1"), true, "http://ma.local:8095", { received.add(it) }).use { setup ->
            http.newCall(Request.Builder().url(setup.url).build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("http://ma.local:8095"))
                assertEquals("no-store", it.header("Cache-Control"))
            }
            val form = FormBody.Builder().add("target", "http://ma.local:8095").add("token", "secret+&token").build()
            val request = Request.Builder().url(setup.url).post(form).build()
            http.newCall(request).execute().use {
                assertEquals(200, it.code)
                assertFalse(it.body!!.string().contains("secret"))
            }
            assertEquals(AssistantPhoneDetails("http://ma.local:8095", "secret+&token"), received.poll(2, TimeUnit.SECONDS))
            http.newCall(request).execute().use { assertEquals(410, it.code) }
        }
    }
    @Test fun rejectsWrongPathCrossOriginAndMissingToken() {
        val received = LinkedBlockingQueue<AssistantPhoneDetails>()
        AssistantPhoneSetup(InetAddress.getByName("127.0.0.1"), false, "", { received.add(it) }).use { setup ->
            http.newCall(Request.Builder().url(setup.url.substringBefore("/setup/") + "/setup/wrong").build()).execute().use { assertEquals(404, it.code) }
            val form = FormBody.Builder().add("target", "remote-id").add("token", "token").build()
            http.newCall(Request.Builder().url(setup.url).header("Origin", "https://example.com").post(form).build()).execute().use { assertEquals(403, it.code) }
            http.newCall(Request.Builder().url(setup.url).post(FormBody.Builder().add("target", "id").build()).build()).execute().use { assertEquals(400, it.code) }
            assertTrue(received.isEmpty())
            http.newCall(Request.Builder().url(setup.url).build()).execute().use { assertEquals(200, it.code) }
        }
    }
    @Test fun escapesTargetAndStopsWhenClosed() {
        val setup = AssistantPhoneSetup(InetAddress.getByName("127.0.0.1"), true, "\"><script>bad</script>", {})
        http.newCall(Request.Builder().url(setup.url).build()).execute().use {
            val body = it.body!!.string()
            assertFalse(body.contains("<script>"))
            assertTrue(body.contains("&quot;&gt;&lt;script&gt;"))
        }
        setup.close()
        assertThrows(java.io.IOException::class.java) { http.newCall(Request.Builder().url(setup.url).build()).execute().close() }
    }
}
