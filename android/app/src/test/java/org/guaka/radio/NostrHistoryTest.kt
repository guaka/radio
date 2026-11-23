package org.guaka.radio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NostrHistoryTest {
    @Test fun concurrentHistoriesConvergeAndAnOfflineDeviceCannotUndoAClear() {
        val fixture = JSONObject(requireNotNull(javaClass.getResource("/listening-history.json")).readText())
        val now = fixture.getLong("now")
        val snapshots = fixture.getJSONArray("snapshots")
        val decoded = List(snapshots.length()) { NostrHistory.decode(snapshots.getJSONObject(it).toString(), now) }
        val expected = NostrHistory.decode(fixture.getJSONObject("merged").toString(), now)
        assertEquals(expected, NostrHistory.merge(decoded))
        assertEquals(expected, NostrHistory.merge(decoded.reversed()))
        assertEquals(expected, NostrHistory.merge(listOf(expected, decoded.first(), decoded.last())))
        assertEquals(expected, NostrHistory.decode(NostrHistory.encode(expected), now))
    }

    @Test fun ignoresPlexHistoryAndRejectsUnknownVersionsAndFutureCutoffs() {
        val now = 1_750_000_000_000L
        assertFalse(NostrHistory.eligible("track:server:123"))
        val unknown = """{"v":2,"clearBefore":0,"entries":[]}"""
        val future = """{"v":1,"clearBefore":${now + 300_001},"entries":[]}"""
        val plex = """{"v":1,"clearBefore":0,"entries":[["track:server:123",$now]]}"""
        listOf(unknown, future, plex).forEach { assertTrue(runCatching { NostrHistory.decode(it, now) }.isFailure) }
        assertFalse(NostrHistory.encrypted(unknown))
    }
}
