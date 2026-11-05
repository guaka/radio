package org.guaka.radio

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlexPinTest {
    @Test fun tvRequestsALinkCodeWhilePhoneKeepsStrongBrowserAuthentication() = runBlocking {
        val strengths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://plex.tv/api/v2/pins", request.url.toString())
            assertEquals("POST", request.method)
            assertEquals("test-client", request.header("X-Plex-Client-Identifier"))
            assertNull(request.header("X-Plex-Token"))
            val body = request.body as FormBody
            assertEquals("strong", body.name(0))
            strengths += body.value(0)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":42,"code":"ABCD"}""".toResponseBody()).build()
        }.build()
        val api = ApiClient("test-client", client)
        assertEquals(Pin(42, "ABCD"), api.createPin(strong = false))
        assertEquals(Pin(42, "ABCD"), api.createPin())
        assertEquals(listOf("false", "true"), strengths)
    }
}
