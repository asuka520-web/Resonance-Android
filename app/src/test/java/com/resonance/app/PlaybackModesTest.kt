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
    @Test fun fullCatalogueShuffleVisitsEveryTrackBeforeRepeatingAndReturnsToPrevious() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val player=ExoPlayer.Builder(context).build()
        try {
            val songs=(0 until 278).map {QqCatalogueTrack("s$it","s$it","歌曲$it","歌手","专辑",200000)}
            val pool=PlaybackQueuePolicy.pool(LibraryState(favorites=songs),PlaybackRange.LIBRARY,listOf("s0"),"s0")
            player.setMediaItems(pool.map {MediaItem.Builder().setMediaId(it.mid).setUri("asset:///${it.mid}.mp3").build()},0,0)
            player.repeatMode=Player.REPEAT_MODE_ALL;player.shuffleModeEnabled=true
            val seen=mutableSetOf(player.currentMediaItem!!.mediaId)
            repeat(277){player.seekToNextMediaItem();assertTrue(seen.add(player.currentMediaItem!!.mediaId))}
            assertEquals(songs.map {it.mid}.toSet(),seen)
            val previous=player.currentMediaItem!!.mediaId
            player.seekToNextMediaItem();assertEquals("s0",player.currentMediaItem!!.mediaId)
            player.seekToPreviousMediaItem();assertEquals(previous,player.currentMediaItem!!.mediaId)
        }finally {player.release()}
    }
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
