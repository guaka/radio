package org.guaka.radio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow

/** Separate lines keep a long artist name from crowding out the station. */
@Composable
internal fun NowPlayingDetails(artist: String?, station: String?, title: String) {
    if (!artist.isNullOrBlank()) Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall)
    if (!station.isNullOrBlank() && station != title) Text(station, maxLines = 1,
        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
}
