package org.guaka.radio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** One encrypted record keeps server identity and its credential together across restarts. */
internal data class LocalAssistantConnection(val url: String, val serverId: String, val token: String)

internal class LocalAssistantConnectionStore(
    private val read: () -> String,
    private val write: (String) -> Unit,
) {
    private var generation = 0L

    @Synchronized fun current(): LocalAssistantConnection? = runCatching {
        val json = JSONObject(read())
        LocalAssistantConnection(json.getString("url"), json.getString("serverId"), json.getString("token"))
            .takeIf { it.url.isNotBlank() && it.serverId.isNotBlank() && it.token.isNotBlank() }
    }.getOrNull()

    @Synchronized fun forget() { generation++; write("") }
    @Synchronized fun invalidatePending() { generation++ }

    suspend fun save(
        url: String, enteredToken: String,
        validate: suspend (String, String, String?) -> LocalAssistantInfo,
    ): LocalAssistantConnection {
        val normalized = localAssistantUrl(url)
        val (epoch, previous) = synchronized(this) { ++generation to current() }
        val sameServer = previous?.takeIf { it.url == normalized }
        val token = assistantTokenForServer(normalized, enteredToken, previous?.url.orEmpty(), previous?.token.orEmpty())
        // Even a replacement token must not silently accept a different server at the saved address.
        val info = validate(normalized, token, sameServer?.serverId)
        currentCoroutineContext().ensureActive()
        return synchronized(this) {
            check(epoch == generation) { "Connection changed while checking. Try connecting again." }
            LocalAssistantConnection(normalized, info.serverId, token).also {
                write(JSONObject().put("url", it.url).put("serverId", it.serverId).put("token", it.token).toString())
            }
        }
    }
}
