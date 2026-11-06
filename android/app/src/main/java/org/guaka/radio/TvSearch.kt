package org.guaka.radio

/** TV consumes the same independently arriving source results as the phone. */
internal fun tvSearchEntries(query: String, browse: List<Entry>, results: Map<String, List<Entry>>): List<Entry> =
    if (query.isBlank()) browse
    else listOf("radio", "plex", "youtube").flatMap { results[it].orEmpty() }.distinctBy { it.id }
