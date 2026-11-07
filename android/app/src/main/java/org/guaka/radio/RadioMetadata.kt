@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo

internal data class RadioTrack(val title: String, val artist: String?) {
    companion object {
        fun parse(value: String?): RadioTrack? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parts = text.split(" - ", limit = 2)
            return if (parts.size == 2 && parts.all { it.isNotBlank() }) {
                RadioTrack(parts[1].trim(), parts[0].trim())
            } else RadioTrack(text, null)
        }
    }
}

/** Listen on each player, including while a new station is preparing before handoff. */
internal class RadioMetadataListener(private val player: Player) : Player.Listener {
    override fun onMetadata(metadata: Metadata) {
        val item = player.currentMediaItem ?: return
        if (!item.mediaId.startsWith("radio:")) return
        val entry = item.localConfiguration?.tag as? Entry ?: return
        for (index in 0 until metadata.length()) {
            val info = metadata[index] as? IcyInfo ?: continue
            val track = RadioTrack.parse(info.title) ?: continue
            val updated = item.mediaMetadata.buildUpon()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setStation(entry.title)
                // Auto/legacy controllers read media descriptions rather than station.
                .setDisplayTitle(track.title)
                .setSubtitle(listOfNotNull(track.artist, entry.title).joinToString(" · "))
                .setDescription(entry.title)
                .setAlbumTitle(entry.title)
                .build()
            if (updated != item.mediaMetadata) {
                // Metadata-only replacement preserves the prepared stream, tag, URI and artwork.
                player.replaceMediaItem(player.currentMediaItemIndex,
                    item.buildUpon().setMediaMetadata(updated).build())
            }
            return
        }
    }
}
