package com.resonance.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

@UnstableApi
object CatalogueMediaItems {
    fun item(song: QqCatalogueTrack): MediaItem = MediaItem.Builder().setMediaId("qq:${song.mid}")
        .setUri(Uri.parse("qqtrack://${song.mid}"))
        .setCustomCacheKey("${QqLibrary.state.accountScope}:${song.mid}:128")
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.name).setArtist(song.artist).setAlbumTitle(song.album)
            .setArtworkUri(CatalogueStore.artwork(song)?.let(Uri::parse)).build()).build()
}
