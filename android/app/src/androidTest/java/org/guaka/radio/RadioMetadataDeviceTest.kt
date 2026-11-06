@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.guaka.radio

import androidx.media3.common.Metadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioMetadataDeviceTest {
    @Test fun icyUpdatesOverrideStationTitleAndPreservePlaybackIdentity() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
            try {
                val station = Entry("radio:groovesalad", "Groove Salad", "soma,ambient", true,
                    "https://ice.somafm.com/groovesalad")
                val artwork = byteArrayOf(1, 2, 3)
                val item = station.asMediaItem().buildUpon().setUri(station.url).setTag(station)
                    .setMediaMetadata(station.asMediaItem().mediaMetadata.buildUpon()
                        .setArtworkData(artwork, 3).build()).build()
                player.setMediaItem(item)
                val listener = RadioMetadataListener(player)
                fun update(title: String?) = listener.onMetadata(Metadata(IcyInfo(byteArrayOf(), title, null)))
                update("Artist - Song - Live")
                val updated = player.currentMediaItem!!
                assertEquals("Song - Live", updated.mediaMetadata.title.toString())
                assertEquals("Artist", updated.mediaMetadata.artist.toString())
                assertEquals("Groove Salad", updated.mediaMetadata.station.toString())
                assertEquals(item.mediaId, updated.mediaId)
                assertEquals(item.localConfiguration, updated.localConfiguration)
                assertArrayEquals(artwork, updated.mediaMetadata.artworkData)
                assertEquals("Song - Live", player.mediaMetadata.title.toString())
                update(" ")
                assertEquals(updated, player.currentMediaItem)
                update("Next song")
                assertEquals("Next song", player.mediaMetadata.title.toString())
                assertNull(player.mediaMetadata.artist)
                // A station switch starts with the station name, never the previous song.
                player.setMediaItem(Entry("radio:other", "Other station", playable = true)
                    .asMediaItem().buildUpon().setUri("https://example.test/other").build())
                assertEquals("Other station", player.mediaMetadata.title.toString())
                assertNull(player.mediaMetadata.artist)
                val plex = Entry("track:test", "Plex song", "Plex artist", true).asMediaItem()
                player.setMediaItem(plex)
                update("Unrelated - Metadata")
                assertEquals(plex, player.currentMediaItem)
            } finally { player.release() }
        }
    }
}
