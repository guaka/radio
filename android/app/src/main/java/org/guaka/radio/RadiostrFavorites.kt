package org.guaka.radio

import org.json.JSONArray
import org.json.JSONObject

/** Nostr kind-1 codec for the public Radio Guaka station-favorites convention. */
object RadiostrFavorites {
    const val ROOM_TAG = "radiostr"
    const val CLIENT_TAG = "radio-guaka"
    const val MAX_FAVORITES = 500
    const val EXPIRATION_SECONDS = 30L * 24 * 60 * 60
    private const val MAX_FUTURE_SKEW_SECONDS = 5L * 60
    private val STATION_ID = Regex("[A-Za-z0-9_-]{1,80}")

    /** Creates an unsigned kind-1 template. Signing and signature verification are caller-owned. */
    fun template(stationId: String, title: String, starred: Boolean, createdAt: Long): JSONObject {
        require(STATION_ID.matches(stationId)) { "Invalid radio station ID." }
        require(createdAt > 0 && createdAt <= Long.MAX_VALUE - EXPIRATION_SECONDS) { "Invalid event time." }
        val safeTitle = title.trim().take(MAX_TITLE_LENGTH)
        val tags = JSONArray()
            .put(JSONArray().put("t").put(ROOM_TAG))
            .put(JSONArray().put("t").put("favorite"))
            .put(JSONArray().put("client").put(CLIENT_TAG))
            .put(JSONArray().put("radiostr_station").put(stationId))
            .put(JSONArray().put("radiostr_action").put(if (starred) "star" else "unstar"))
            .put(JSONArray().put("expiration").put((createdAt + EXPIRATION_SECONDS).toString()))
        if (safeTitle.isNotBlank()) tags.put(JSONArray().put("radiostr_title").put(safeTitle))
        val visibleTitle = safeTitle.ifBlank { "Station" }
        return JSONObject()
            .put("kind", 1)
            .put("created_at", createdAt)
            .put("content", (if (starred) "⭐ " else "☆ ") + visibleTitle + " #radiostr #favorite")
            .put("tags", tags)
    }

    /**
     * Merges the verified author's recent public station actions with local stable IDs.
     * When timestamp ties occur, the lexically larger event ID is the later action.
     */
    fun merge(localIds: List<String>, events: List<JSONObject>, pubkey: String, nowSeconds: Long): List<String> {
        if (!pubkey.matches(Regex("[0-9a-fA-F]{64}")) || nowSeconds <= 0) {
            return localStableIds(localIds).take(MAX_FAVORITES)
        }
        val actions = events.mapNotNull { parse(it, pubkey, nowSeconds) }
        val lastAction = actions.groupBy { it.stationId }.mapValues { (_, stationActions) ->
            stationActions.maxWithOrNull(compareBy<Action> { it.createdAt }.thenBy { it.eventId })!!
        }
        val remoteStarred = lastAction.values.filter { it.starred }
            .sortedWith(compareByDescending<Action> { it.createdAt }.thenByDescending { it.eventId }.thenBy { it.stationId })
        val remoteKnown = lastAction.keys
        val result = LinkedHashSet<String>(MAX_FAVORITES)
        for (action in remoteStarred) {
            result.add("radio:${action.stationId}")
            if (result.size == MAX_FAVORITES) return result.toList()
        }
        for (id in localStableIds(localIds)) {
            val stationId = id.removePrefix("radio:")
            if (id.startsWith("radio:") && stationId in remoteKnown) continue
            result.add(id)
            if (result.size == MAX_FAVORITES) break
        }
        return result.toList()
    }

    private data class Action(val stationId: String, val starred: Boolean, val createdAt: Long, val eventId: String)

    private fun parse(event: JSONObject, pubkey: String, nowSeconds: Long): Action? {
        if (integer(event.opt("kind")) != 1L) return null
        if ((event.opt("pubkey") as? String) != pubkey) return null
        val eventId = event.opt("id") as? String ?: return null
        if (eventId.isBlank()) return null
        val createdAt = integer(event.opt("created_at")) ?: return null
        if (createdAt <= 0 || createdAt > Long.MAX_VALUE - EXPIRATION_SECONDS ||
            nowSeconds > Long.MAX_VALUE - MAX_FUTURE_SKEW_SECONDS ||
            createdAt > nowSeconds + MAX_FUTURE_SKEW_SECONDS) return null
        val tags = event.optJSONArray("tags") ?: return null
        if (!hasTag(tags, "t", ROOM_TAG) || !hasTag(tags, "t", "favorite")) return null
        val stationId = singleTagValue(tags, "radiostr_station")?.takeIf(STATION_ID::matches) ?: return null
        val action = singleTagValue(tags, "radiostr_action") ?: return null
        val starred = when (action) { "star" -> true; "unstar" -> false; else -> return null }
        val expiration = singleTagValue(tags, "expiration")?.toLongOrNull() ?: return null
        if (expiration != createdAt + EXPIRATION_SECONDS || expiration <= nowSeconds) return null
        return Action(stationId, starred, createdAt, eventId)
    }

    private fun hasTag(tags: JSONArray, key: String, value: String): Boolean =
        (0 until tags.length()).any { index ->
            val tag = tags.optJSONArray(index)
            tag != null && tag.length() >= 2 && tag.opt(0) == key && tag.opt(1) == value
        }

    private fun singleTagValue(tags: JSONArray, key: String): String? {
        val values = (0 until tags.length()).mapNotNull { index ->
            tags.optJSONArray(index)?.takeIf { it.length() >= 2 && it.opt(0) == key }
                ?.opt(1)?.let { it as? String }
        }
        return values.singleOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun integer(value: Any?): Long? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < 0.0 || double > Long.MAX_VALUE.toDouble()) return null
        return number.toLong()
    }

    private fun localStableIds(ids: List<String>): List<String> = ids.asSequence()
        .filter(ListeningStore::isStableMediaId).distinct().toList()

    private const val MAX_TITLE_LENGTH = 160
}
