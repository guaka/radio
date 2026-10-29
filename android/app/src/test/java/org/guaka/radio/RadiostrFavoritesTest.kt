package org.guaka.radio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiostrFavoritesTest {
    private val author = "a".repeat(64)
    private val now = 2_000_000_000L

    @Test fun templateUsesRoomSchemaAndThirtyDayExpiry() {
        val createdAt = 1_900_000_000L
        val event = RadiostrFavorites.template("doomed", "Doomed", true, createdAt)
        assertEquals(1, event.getInt("kind"))
        assertEquals(createdAt, event.getLong("created_at"))
        assertEquals("⭐ Doomed #radiostr #favorite", event.getString("content"))
        assertTag(event.getJSONArray("tags"), "t", "radiostr")
        assertTag(event.getJSONArray("tags"), "t", "favorite")
        assertTag(event.getJSONArray("tags"), "client", "radio-guaka")
        assertTag(event.getJSONArray("tags"), "radiostr_station", "doomed")
        assertTag(event.getJSONArray("tags"), "radiostr_action", "star")
        assertTag(event.getJSONArray("tags"), "expiration", (createdAt + RadiostrFavorites.EXPIRATION_SECONDS).toString())
        val unstar = RadiostrFavorites.template("doomed", "Doomed", false, createdAt)
        assertTag(unstar.getJSONArray("tags"), "radiostr_action", "unstar")
    }

    @Test fun onlyVerifiedAuthorsRecentRadioFavoriteEventsAffectMerge() {
        val valid = event("dronezone", "star", now - 10)
        val foreign = event("foreign", "star", now - 5, pubkey = "b".repeat(64))
        val wrongKind = event("wrongkind", "star", now - 5).put("kind", 42)
        val expired = event("expired", "star", now - 31 * 24 * 60 * 60L)
        val future = event("future", "star", now + 600)
        val nonFavorite = event("chat", "star", now - 5).also { removeFavoriteTag(it) }
        val plex = event("track:server:123", "star", now - 1)

        assertEquals(listOf("radio:dronezone"), RadiostrFavorites.merge(
            emptyList(), listOf(valid, foreign, wrongKind, expired, future, nonFavorite, plex), author, now
        ))
    }

    @Test fun timestampTiesUseEventIdAndUnstarOverridesLocalRadioOnly() {
        val lowerIdStar = event("fluid", "star", now - 20, id = "1".repeat(64))
        val higherIdUnstar = event("fluid", "unstar", now - 20, id = "f".repeat(64))
        val result = RadiostrFavorites.merge(
            listOf("radio:fluid", "radio:local", "track:server:77"),
            listOf(lowerIdStar, higherIdUnstar), author, now
        )
        assertEquals(listOf("radio:local", "track:server:77"), result)
    }

    @Test fun newestStarredEventsOrderRemoteFavoritesAndMergeCapsAt500() {
        val events = listOf(event("older", "star", now - 100), event("newer", "star", now - 10))
        assertEquals(listOf("radio:newer", "radio:older", "track:server:1"), RadiostrFavorites.merge(
            listOf("track:server:1", "radio:older"), events, author, now
        ))
        val many = (0..600).map { event("station$it", "star", now - it.toLong()) }
        assertEquals(500, RadiostrFavorites.merge(emptyList(), many, author, now).size)
    }

    @Test fun malformedActionsAndInvalidLocalIdsAreIgnored() {
        val invalidAction = event("known", "toggle", now - 1)
        val badExpiration = event("bad-expiry", "star", now - 1).also {
            replaceTag(it, "expiration", "${now + 90 * 24 * 60 * 60L}")
        }
        val result = RadiostrFavorites.merge(
            listOf("radio:valid", "radio:../bad", "https://stream.example", "track:server:9"),
            listOf(invalidAction, badExpiration), author, now
        )
        assertEquals(listOf("radio:valid", "track:server:9"), result)
        assertFalse(result.contains("radio:known"))
        assertTrue(runCatching { RadiostrFavorites.template("../bad", "Bad", true, now) }.isFailure)
    }

    private fun event(
        stationId: String,
        action: String,
        createdAt: Long,
        pubkey: String = author,
        id: String = createdAt.toString().padStart(64, '0'),
    ): JSONObject {
        val safeStationId = stationId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: "invalid"
        val template = RadiostrFavorites.template(safeStationId, "Station", action == "star", createdAt)
        replaceTag(template, "radiostr_action", action)
        if (safeStationId != stationId) replaceTag(template, "radiostr_station", stationId)
        return JSONObject()
            .put("id", id)
            .put("pubkey", pubkey)
            .put("kind", 1)
            .put("created_at", createdAt)
            .put("content", template.getString("content"))
            .put("tags", template.getJSONArray("tags"))
    }

    private fun assertTag(tags: JSONArray, key: String, value: String) {
        assertTrue((0 until tags.length()).any {
            val tag = tags.getJSONArray(it)
            tag.optString(0) == key && tag.optString(1) == value
        })
    }

    private fun removeFavoriteTag(event: JSONObject) {
        val old = event.getJSONArray("tags")
        val tags = JSONArray()
        for (i in 0 until old.length()) {
            val tag = old.getJSONArray(i)
            if (!(tag.optString(0) == "t" && tag.optString(1) == "favorite")) tags.put(tag)
        }
        event.put("tags", tags)
    }

    private fun replaceTag(event: JSONObject, key: String, value: String) {
        val old = event.getJSONArray("tags")
        val tags = JSONArray()
        for (i in 0 until old.length()) {
            val tag = old.getJSONArray(i)
            tags.put(if (tag.optString(0) == key) JSONArray().put(key).put(value) else tag)
        }
        event.put("tags", tags)
    }
}
