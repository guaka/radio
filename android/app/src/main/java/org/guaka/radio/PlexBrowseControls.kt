package org.guaka.radio

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun AlbumShuffleControls(parent: String, active: Boolean, enabled: Boolean, start: (String) -> Unit, stop: () -> Unit) {
    val scope = AlbumShuffle.scope(parent)
    if (scope != null || active) Row {
        if (scope != null) TextButton(enabled = enabled, onClick = { start(scope) }) { Text("Shuffle albums") }
        if (active) TextButton(enabled = enabled, onClick = stop) { Text("Stop album shuffle") }
    }
}

/** A back-stack frame retains loaded pages as well as the exact viewport and filter. */
internal data class BrowseLocation(
    val id: String,
    val title: String,
    val query: String,
    val appliedQuery: String,
    val searchResults: Map<String, List<Entry>>,
    val searchErrors: Map<String, String>,
    val searchingSources: Set<String>,
    val status: String,
    val statusScope: StatusScope,
    val entries: List<Entry>,
    val nextPage: Int,
    val hasMore: Boolean,
    val index: Int,
    val offset: Int,
    val plexScrollIndex: Int,
    val plexScrollOffset: Int,
    val plexGeneration: Long,
)
