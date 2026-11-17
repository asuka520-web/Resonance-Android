package com.resonance.app

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackModesTest {
    @Test fun manualPreviousAndNextWorkDuringSingleRepeatAndShuffleUsesTheCurrentQueue() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val player=ExoPlayer.Builder(context).build()
        try {
            player.setMediaItems((0..2).map {MediaItem.Builder().setMediaId("song$it").setUri("asset:///test$it.mp3").build()},1,0)
            player.repeatMode=Player.REPEAT_MODE_ONE
            player.seekToNextMediaItem()
            assertEquals("song2",player.currentMediaItem!!.mediaId)
            player.seekToPreviousMediaItem()
            assertEquals("song1",player.currentMediaItem!!.mediaId)
            player.repeatMode=Player.REPEAT_MODE_ALL;player.shuffleModeEnabled=true
            val original=player.currentMediaItem!!.mediaId
            player.seekToNextMediaItem()
            assertNotEquals(original,player.currentMediaItem!!.mediaId)
            player.seekToPreviousMediaItem()
            assertEquals(original,player.currentMediaItem!!.mediaId)
            assertEquals(PlaybackMode.SHUFFLE,PlaybackMode.fromPlayer(player.repeatMode,player.shuffleModeEnabled))
        } finally {player.release()}
    }
}
