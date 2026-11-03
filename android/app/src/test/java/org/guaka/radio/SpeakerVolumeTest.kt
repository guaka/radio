package org.guaka.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SpeakerVolumeTest {
    private val living = Speaker("living", "Living room")
    private val kitchen = Speaker("kitchen", "Kitchen")
    private fun player(id: String = living.id, level: Any = 35, extra: String = "") =
        JSONObject("""{"player_id":"$id","available":true,"volume_level":$level,"supported_features":["volume_set"]$extra}""")

    @Test fun readsOnlyActiveSpeakersAndSendsBoundedPlayerVolume() = runBlocking {
        var state = emptyList<SpeakerVolume>()
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val control = SpeakerVolumeControl({ command, args ->
            calls += command to args
            JSONArray().put(player()).put(player(kitchen.id, 60))
        }) { state = it }
        control.reset(listOf(living, kitchen, living))
        control.refresh()
        assertEquals(listOf(35, 60), state.map { it.level })
        control.setVolume(kitchen.id, 150)
        assertEquals("players/cmd/volume_set", calls.last().first)
        assertEquals(kitchen.id, calls.last().second.getString("player_id"))
        assertEquals(100, calls.last().second.getInt("volume_level"))
        assertFalse(calls.last().second.has("queue_id"))
        assertEquals(listOf(35, 100), state.map { it.level })
        control.setVolume(living.id, -10)
        assertEquals(0, calls.last().second.getInt("volume_level"))
    }

    @Test fun unavailableUnsupportedMissingAndInvalidVolumesCannotBeChanged() = runBlocking {
        val rows = JSONArray().put(player().put("available", false))
            .put(player(kitchen.id).put("supported_features", JSONArray()))
            .put(player("null", JSONObject.NULL)).put(player("invalid", 101))
        var state = emptyList<SpeakerVolume>()
        var writes = 0
        val control = SpeakerVolumeControl({ command, _ -> if (command != "players/all") writes++; rows }) { state = it }
        val targets = listOf(living, kitchen, Speaker("null", "Null"), Speaker("invalid", "Invalid"), Speaker("missing", "Missing"))
        control.reset(targets)
        control.refresh()
        assertTrue(state.all { it.level == null })
        targets.forEach { control.setVolume(it.id, 50) }
        assertEquals(0, writes)
    }

    @Test fun externalVolumeControlsAndGroupVolumesAreSupported() = runBlocking {
        var state = emptyList<SpeakerVolume>()
        var sent = ""
        val rows = JSONArray().put(player().put("supported_features", JSONArray()).put("volume_control", "ha-control"))
            .put(player(kitchen.id).put("type", "group").put("group_volume", 42).put("supported_features", JSONArray()))
        val control = SpeakerVolumeControl({ command, _ -> sent = command; rows }) { state = it }
        control.reset(listOf(living, kitchen))
        control.refresh()
        assertEquals(listOf(35, 42), state.map { it.level })
        control.setVolume(kitchen.id, 25)
        assertEquals("players/cmd/group_volume", sent)
        assertEquals(25, state.last().level)
    }

    @Test fun failedWriteKeepsReportedLevelAndRefreshRecoversFromConnectionFailure() = runBlocking {
        var fail = false
        var state = emptyList<SpeakerVolume>()
        val control = SpeakerVolumeControl({ _, _ -> if (fail) throw IOException("offline"); JSONArray().put(player()) }) { state = it }
        control.reset(listOf(living))
        control.refresh()
        fail = true
        assertTrue(runCatching { control.setVolume(living.id, 80) }.exceptionOrNull() is IOException)
        assertEquals(35, state.single().level)
        control.refresh()
        assertNull(state.single().level)
        fail = false
        control.refresh()
        assertEquals(35, state.single().level)
    }

    @Test fun staleRefreshCannotOverwriteAcknowledgedVolume() = runBlocking {
        val response = CompletableDeferred<JSONArray>()
        var slow = false
        var state = emptyList<SpeakerVolume>()
        val control = SpeakerVolumeControl({ command, _ ->
            if (command == "players/all" && slow) response.await() else JSONArray().put(player())
        }) { state = it }
        control.reset(listOf(living))
        control.refresh()
        slow = true
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { control.refresh() }
        control.setVolume(living.id, 70)
        response.complete(JSONArray().put(player()))
        refresh.await()
        assertEquals(70, state.single().level)
    }

    @Test fun lateReadAndWriteCannotRestoreSpeakersAfterReturningToPhone() = runBlocking {
        for (write in listOf(false, true)) {
            val response = CompletableDeferred<JSONArray>()
            var slow = false
            var state = emptyList<SpeakerVolume>()
            val control = SpeakerVolumeControl({ _, _ -> if (slow) response.await() else JSONArray().put(player()) }) { state = it }
            control.reset(listOf(living))
            control.refresh()
            slow = true
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                if (write) control.setVolume(living.id, 70) else control.refresh()
            }
            control.reset(emptyList())
            response.complete(JSONArray().put(player()))
            request.await()
            assertTrue(state.isEmpty())
        }
    }

    @Test fun pollingSkipsPendingWriteAndCancellationPropagates() = runBlocking {
        val response = CompletableDeferred<JSONArray>()
        var state = emptyList<SpeakerVolume>()
        var reads = 0
        var cancel = false
        val control = SpeakerVolumeControl({ command, _ ->
            if (cancel) throw CancellationException()
            if (command == "players/all") { reads++; JSONArray().put(player()) } else response.await()
        }) { state = it }
        control.reset(listOf(living))
        control.refresh()
        val write = async(start = CoroutineStart.UNDISPATCHED) { control.setVolume(living.id, 70) }
        control.refresh()
        assertEquals(1, reads)
        response.complete(JSONArray())
        write.await()
        cancel = true
        assertTrue(runCatching { control.refresh() }.exceptionOrNull() is CancellationException)
        assertEquals(70, state.single().level)
    }
}
