package com.resonance.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="zh-rCN-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResonanceScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun show(library:LibraryState,player:PlayerStatus=PlayerStatus(),play:(QqCatalogueTrack)->Unit={}) {
        compose.setContent {ResonanceScreen(library,player,{}, {}, play, {}, {}, {}, {}, {}, {}, {}, {})}
    }
    private fun capture(name:String) {
        val file=File("build/outputs/ui-review/$name.png");file.parentFile?.mkdirs()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use {output -> bitmap.compress(Bitmap.CompressFormat.PNG,100,output)}
        }
    }
    @Test fun disconnectedHomeOffersConnectionWithoutDebugFieldsOrHiddenProviderNames() {
        show(LibraryState())
        compose.onNodeWithText("连接我的 QQ 音乐").assertIsDisplayed()
        compose.onAllNodesWithText("MusicBrainz",substring=true).assertCountEquals(0)
        compose.onAllNodesWithText("MEDIA_ID",substring=true).assertCountEquals(0)
        capture("home-disconnected")
    }
    @Test fun librarySelectionKeepsExactTrackIdentityAndRendersWithoutNetworkArtwork() {
        val song=QqCatalogueTrack("testA","testA","测试歌曲","测试歌手","测试专辑",200000)
        var selected=""
        show(LibraryState(accountScope="test-only",favorites=listOf(song),genres=mapOf("testA" to
            CatalogueGenre("testA",setOf("pop"),"qq-song-detail","test fixture",0,Long.MAX_VALUE)))){selected=it.mid}
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithText("测试歌曲").performClick()
        assertEquals("testA",selected)
        capture("library-test-data")
    }
    @Test fun connectedHomeRetainsMiniPlayerAndShowsActualCoverageCounts() {
        val songs=listOf("晚风经过","此刻的温柔","下一站晴天").mapIndexed {i,name -> QqCatalogueTrack("test$i","test$i",name,"测试歌手","测试专辑",200000)}
        val labels=listOf("pop","r&b","jazz")
        val genres=songs.mapIndexed {i,song -> song.mid to CatalogueGenre(song.mid,setOf(labels[i]),"qq-song-detail","test fixture",0,Long.MAX_VALUE)}.toMap()
        show(LibraryState(accountScope="test-only",favorites=songs,genres=genres),PlayerStatus(mid="test0",playing=true,positionMs=50000,durationMs=200000))
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onAllNodesWithText("晚风经过").assertCountEquals(2)
        capture("home-connected-test-data")
    }
    @Test fun openedPlaylistExposesSongsOutsideFavoritesForPlayback() {
        val song=QqCatalogueTrack("playlistOnly","playlistOnly","歌单里的歌","测试歌手","测试专辑",200000)
        var selected=""
        show(LibraryState(accountScope="test-only",discoveries=listOf(song),
            openedPlaylist=QqCataloguePlaylist("test-list","测试歌单"),playlistTracks=listOf(song))){selected=it.mid}
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithText("歌单里的歌").performClick()
        assertEquals("playlistOnly",selected)
    }
}
