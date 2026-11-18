package com.resonance.app

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Routes both system previous commands through the same track selection as the app. */
@UnstableApi
internal class PreviousTrackPlayer(
    private val delegate: Player,
    private val previousFromHistory: (String) -> MediaItem?,
    private val onTrackSwitch: () -> Unit
) : ForwardingPlayer(delegate) {
    override fun seekToPrevious() = previousTrack()

    override fun seekToPreviousMediaItem() = previousTrack()

    private fun previousTrack() {
        val current = delegate.currentMediaItem ?: return
        if (delegate.hasPreviousMediaItem() && delegate.previousMediaItemIndex != delegate.currentMediaItemIndex) {
            onTrackSwitch()
            delegate.seekToPreviousMediaItem()
            return
        }

        val previous = previousFromHistory(current.mediaId) ?: return
        if (previous.mediaId == current.mediaId) return
        val existing = (0 until delegate.mediaItemCount).firstOrNull {
            delegate.getMediaItemAt(it).mediaId == previous.mediaId
        }
        if (existing == null) delegate.addMediaItem(0, previous)
        onTrackSwitch()
        delegate.seekToDefaultPosition(existing ?: 0)
    }
}
