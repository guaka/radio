package org.guaka.radio

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class SpeakerVolume(val speaker: Speaker, val level: Int? = null, val group: Boolean = false)

/** Accessed on the main dispatcher; generations prevent late responses from restoring an old route. */
internal class SpeakerVolumeControl(
    private val command: suspend (String, JSONObject) -> Any,
    private val publish: (List<SpeakerVolume>) -> Unit,
) {
    private var generation = 0L
    private var updating = false
    private var volumes = emptyList<SpeakerVolume>()

    fun reset(speakers: List<Speaker>) {
        generation++
        updating = false
        volumes = speakers.distinctBy { it.id }.map { SpeakerVolume(it) }
        publish(volumes)
    }

    suspend fun refresh() {
        if (volumes.isEmpty() || updating) return
        val request = generation
        val updated = try {
            val rows = command("players/all", JSONObject()) as JSONArray
            val players = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .associateBy { it.optString("player_id") }
            volumes.map { state ->
                val player = players[state.speaker.id]
                val group = player?.optString("type") == "group"
                val field = if (group) "group_volume" else "volume_level"
                val features = player?.optJSONArray("supported_features") ?: JSONArray()
                val supported = player?.optString("volume_control")?.let { it.isNotBlank() && it != "none" } == true ||
                    (0 until features.length()).any { features.optString(it) == "volume_set" }
                val level = (player?.opt(field) as? Number)?.toInt()?.takeIf { it in 0..100 }
                state.copy(level = level.takeIf { player?.optBoolean("available", false) == true && (group || supported) }, group = group)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { volumes.map { it.copy(level = null) } }
        if (request == generation) {
            volumes = updated
            publish(volumes)
        }
    }

    suspend fun setVolume(id: String, level: Int) {
        val state = volumes.firstOrNull { it.speaker.id == id && it.level != null } ?: return
        if (updating) return
        val request = ++generation
        val bounded = level.coerceIn(0, 100)
        updating = true
        try {
            command(if (state.group) "players/cmd/group_volume" else "players/cmd/volume_set",
                JSONObject().put("player_id", id).put("volume_level", bounded))
            if (request == generation) {
                volumes = volumes.map { if (it.speaker.id == id) it.copy(level = bounded) else it }
                publish(volumes)
            }
        } finally {
            if (request == generation) updating = false
        }
    }
}
