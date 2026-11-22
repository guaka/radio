package org.guaka.radio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal object NostrHistory {
    const val KIND = 30078
    const val TOPIC = "radio-guaka-history-v1"
    const val LIMIT = 100
    const val MAX_CONTENT = 48 * 1024
    private val idPattern = Regex("(radio:[A-Za-z0-9_-]{1,80}|youtube:[A-Za-z0-9_-]{11})")
    val devicePattern = Regex("$TOPIC:[a-f0-9-]{36}")

    data class Play(val id: String, val at: Long)
    data class Snapshot(val clearBefore: Long = 0, val entries: List<Play> = emptyList())

    fun eligible(id: String): Boolean = idPattern.matches(id)

    fun encode(state: Snapshot): String = JSONObject().put("v", 1).put("clearBefore", state.clearBefore)
        .put("entries", JSONArray().apply { state.entries.forEach { put(JSONArray().put(it.id).put(it.at)) } })
        .toString()

    fun decode(plaintext: String, now: Long = System.currentTimeMillis()): Snapshot {
        require(plaintext.length <= MAX_CONTENT)
        val root = JSONObject(plaintext)
        require(root.opt("v") == 1)
        val clear = readTime(root.opt("clearBefore"), now)
        val rows = root.getJSONArray("entries")
        require(rows.length() <= LIMIT)
        val entries = (0 until rows.length()).map { index ->
            val row = rows.getJSONArray(index)
            require(row.length() == 2)
            val id = row.getString(0)
            require(eligible(id))
            Play(id, readTime(row.opt(1), now).also { require(it > 0) })
        }
        return merge(listOf(Snapshot(clear, entries)))
    }

    fun encrypted(value: String): Boolean = value.length in 132..MAX_CONTENT &&
        runCatching { Base64.getDecoder().decode(value.take(4)).first() == 2.toByte() }.getOrDefault(false)

    private fun readTime(value: Any?, now: Long): Long {
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 0..(now + 300_000)) }
    }

    fun merge(states: Iterable<Snapshot>): Snapshot {
        val cutoff = states.maxOfOrNull { it.clearBefore } ?: 0L
        val entries = states.asSequence().flatMap { it.entries.asSequence() }.filter { it.at > cutoff }
            .sortedWith(compareByDescending<Play> { it.at }.thenBy { it.id })
            .distinctBy { it.id }.take(LIMIT).toList()
        return Snapshot(cutoff, entries)
    }
}
