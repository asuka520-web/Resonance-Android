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
    @Test fun systemPreviousSwitchesTracksEvenFarIntoTheSong() {
        val delegate = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            delegate.setMediaItems(listOf(previousFixture("first"), previousFixture("second")), 1, 45_000)
            var switches = 0
            val player = PreviousTrackPlayer(delegate, { null }, { switches++ })
            player.seekToPrevious()
            assertEquals("first", player.currentMediaItem?.mediaId)
            assertEquals(1, switches)

            delegate.seekTo(23_000)
            player.seekToPrevious()
            assertEquals(23_000L, player.currentPosition)
            assertEquals(1, switches)
        } finally {
            delegate.release()
        }
    }

    @Test fun systemPreviousFallsBackToHistoryAtTheStartOfTheQueue() {
        val delegate = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        try {
            delegate.setMediaItems(listOf(previousFixture("current")), 0, 45_000)
            delegate.repeatMode = Player.REPEAT_MODE_ALL
            val player = PreviousTrackPlayer(delegate, { previousFixture("history") }, {})
            player.seekToPreviousMediaItem()
            assertEquals("history", player.currentMediaItem?.mediaId)
            assertEquals(2, player.mediaItemCount)
        } finally {
            delegate.release()
        }
    }

    private fun previousFixture(id: String): MediaItem =
        MediaItem.Builder().setMediaId(id).setUri("asset:///$id.mp3").build()

    @Test fun weightedOrderUpdateKeepsCurrentPositionAndPreviousWhileUsingEveryNativeQueueItem() {
        val context=ApplicationProvider.getApplicationContext<Context>();val player=ExoPlayer.Builder(context).build()
        val shuffle=PreferenceShuffle(kotlin.random.Random(45));val mids=(0..50).map {"song$it"}
        fun install(current:String,scores:Map<String,Double>) {
            val order=shuffle.reorder(mids,current,scores).map {mids.indexOf(it)}.toIntArray()
            player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order,1L))
        }
        try {
            player.setMediaItems(mids.map {MediaItem.Builder().setMediaId(it).setUri("asset:///$it.mp3").build()},0,12345)
            player.shuffleModeEnabled=true;player.repeatMode=Player.REPEAT_MODE_ALL
            install("song0",emptyMap());assertEquals(12345L,player.currentPosition)
            player.seekToNextMediaItem();val next=player.currentMediaItem!!.mediaId;shuffle.visit(next)
            player.seekTo(45000);install(next,mapOf("song50" to 6.0))
            assertEquals(next,player.currentMediaItem!!.mediaId);assertEquals(45000L,player.currentPosition)
            player.seekToPreviousMediaItem();assertEquals("song0",player.currentMediaItem!!.mediaId)
            player.seekToNextMediaItem();assertEquals(next,player.currentMediaItem!!.mediaId)
            val seen=mutableSetOf("song0",next)
            repeat(49){player.seekToNextMediaItem();val mid=player.currentMediaItem!!.mediaId;assertTrue(seen.add(mid));shuffle.visit(mid);install(mid,mapOf("song50" to 6.0))}
            assertEquals(mids.toSet(),seen)
        }finally{player.release()}
    }
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
            PlaybackMode.SINGLE.applyTo(player)
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
            player.seekToNextMediaItem()
            assertEquals("song2",player.currentMediaItem!!.mediaId)
            player.seekToPreviousMediaItem()
            assertEquals("song1",player.currentMediaItem!!.mediaId)
            PlaybackMode.LOOP.applyTo(player)
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
            assertFalse(player.shuffleModeEnabled)
            PlaybackMode.SHUFFLE.applyTo(player)
            val original=player.currentMediaItem!!.mediaId
            player.seekToNextMediaItem()
            assertNotEquals(original,player.currentMediaItem!!.mediaId)
            player.seekToPreviousMediaItem()
            assertEquals(original,player.currentMediaItem!!.mediaId)
            assertEquals(PlaybackMode.SHUFFLE,PlaybackMode.fromPlayer(player.repeatMode,player.shuffleModeEnabled))
            PlaybackMode.SHUFFLE.next().applyTo(player)
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
            assertFalse(player.shuffleModeEnabled)
            assertEquals(original, player.currentMediaItem!!.mediaId)
        } finally {player.release()}
    }
}
