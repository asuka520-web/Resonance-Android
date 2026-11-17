package com.resonance.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
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
            val canvas=Canvas(bitmap)
            val roots=runCatching {
                val type=Class.forName("android.view.WindowManagerGlobal")
                val instance=type.getDeclaredMethod("getInstance").invoke(null)
                val field=type.getDeclaredField("mViews").apply {isAccessible=true}
                @Suppress("UNCHECKED_CAST")
                (field.get(instance) as List<android.view.View>).toList()
            }.getOrDefault(listOf(view))
            roots.filter {it.visibility==android.view.View.VISIBLE && it.width>0}.forEach {root ->
                val location=IntArray(2);root.getLocationOnScreen(location)
                val saved=canvas.save();canvas.translate(location[0].toFloat(),location[1].toFloat());root.draw(canvas);canvas.restoreToCount(saved)
            }
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
        compose.onNodeWithText("100%").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("晚风经过").onFirst().assertIsDisplayed()
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
    @Test fun missingGenreStatesAndRetryAreVisibleWithoutClearingReadySongs() {
        val songs=listOf("ready","waiting","retry","missing").map {QqCatalogueTrack(it,it,"测试-$it","测试歌手","测试专辑",200000)}
        val genres=mapOf("ready" to CatalogueGenre("ready",setOf("pop","soul"),"qq-song-detail","",1,999),
            "retry" to CatalogueGenre("retry",emptySet(),"pending","",1,999),
            "missing" to CatalogueGenre("missing",emptySet(),"qq-song-detail","",1,999))
        var retried=false
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=songs,genres=genres),PlayerStatus(),
            {},{},{},{},{},{},{},{},{},{},{},onRetryMissing={retried=true})}
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithText("已有曲风 1 · 等待查询 1\n待重试 1 · 暂无资料 1").assertIsDisplayed()
        compose.onNodeWithText("重试未取得曲风").performClick()
        assertTrue(retried)
        compose.onNodeWithText("流行").assertExists()
        compose.onNodeWithText("灵魂乐").assertExists()
        capture("library-status-test-data")
    }
    @Test fun recommendationBatchButtonAndSongSelectionUseRecommendationQueueCallback() {
        val song=QqCatalogueTrack("newSong","newSong","收藏外测试歌曲","测试歌手","测试专辑",200000)
        val plan=StylePlan("QQ 相关歌曲","曲风待补齐",listOf(StylePick(StyleTrack("qq:newSong",song.name,song.artist,"qq:newSong",emptySet()),"QQ 相关歌曲",true)))
        var selected:String?="not-called"
        var ordinaryPlayed=false
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",discoveries=listOf(song),recommendation=plan),PlayerStatus(),
            {},{},{ordinaryPlayed=true},{},{},{},{},{},{},{},{},onPlayRecommendation={selected=it})}
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("播放这批"))
        compose.onNodeWithText("播放这批").performClick()
        assertNull(selected)
        compose.onNodeWithText("收藏外测试歌曲").performScrollTo().performClick()
        assertEquals("newSong",selected)
        assertFalse(ordinaryPlayed)
    }
    @Test fun discoveryTabShowsSourceAndPlaysTheVisibleCandidateQueue() {
        val seed=QqCatalogueTrack("seed","seed","收藏锚点","测试歌手","专辑",200000)
        val song=QqCatalogueTrack("outside","outside","发现里的歌曲","另一歌手","专辑",200000)
        var played=emptyList<String>();var selected:String?=null
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(seed),discoveries=listOf(song),
            discoverySources=mapOf("outside" to listOf("seed"))),PlayerStatus(),{},{},{},{},{},{},{},{},{},{},{},
            onPlayDiscovery={mids,mid->played=mids;selected=mid})}
        compose.onNodeWithText("发现",useUnmergedTree=true).performClick()
        compose.onNodeWithText("发现里的歌曲").performScrollTo().performClick()
        assertEquals(listOf("outside"),played);assertEquals("outside",selected)
        compose.onNodeWithText("来自「收藏锚点」的相关推荐").assertExists()
        capture("discovery-test-data")
    }
    @Test fun cooldownIsSelectableAndSavedWithoutRestart() {
        show(LibraryState())
        compose.onNodeWithText("我的",useUnmergedTree=true).performClick()
        compose.onNodeWithText("不额外等待").performScrollTo().assertIsSelected()
        compose.onNodeWithText("15秒").performClick()
        compose.runOnIdle {assertEquals(15,compose.activity.getSharedPreferences("resonance_settings",0).getInt("qq_direction_cooldown_seconds",-1))}
    }
    @Test fun retryAndSupplementButtonsRemainAvailableDuringInitialAnalysis() {
        val song=QqCatalogueTrack("pending","pending","等待中的歌","歌手","专辑",200000)
        var secondary=false
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song),analyzing=true),PlayerStatus(),
            {},{},{},{},{},{},{},{},{},{},{},onSupplementMissing={secondary=true})}
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithText("重试未取得曲风").assertIsEnabled()
        compose.onNodeWithText("换来源补齐").performClick()
        assertTrue(secondary)
    }
    @Test fun listTagsFitByWidthAndDetailsExposeEverySavedLabelWithoutStartingPlayback() {
        val song=QqCatalogueTrack("tags","tags","多标签测试歌","歌手","专辑",200000)
        val labels=setOf("ambient","electronic","folk","jazz","pop","rock")
        var played=false
        show(LibraryState(accountScope="test",favorites=listOf(song),genres=mapOf("tags" to CatalogueGenre("tags",labels,"qq-song-detail","",0,999)))){played=true}
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("多标签测试歌的歌曲详情").performClick()
        compose.onNodeWithText("全部曲风标签").assertIsDisplayed()
        compose.onNodeWithText("ambient · 电子 · 民谣 · 爵士 · 流行 · 摇滚").assertIsDisplayed()
        assertFalse(played)
        capture("detail-tags-test-data")
    }
    @Test fun tagLineShowsMoreThanTwoCompleteTagsWhenWidthAllows() {
        compose.setContent {ResonanceTheme {Box(Modifier.width(360.dp)){CompleteTagRow(setOf("pop","rock","folk","jazz"))}}}
        for(tag in listOf("流行","摇滚","民谣","爵士"))compose.onNodeWithText(tag).assertIsDisplayed()
    }
    @Test fun directionHintFadesAwayAndDoesNotConsumeTouches() {
        compose.mainClock.autoAdvance=false
        var clicks=0
        val now=SystemClock.elapsedRealtime()
        compose.setContent {ResonanceTheme {Box(Modifier.fillMaxSize()) {
            Button(onClick={clicks++},modifier=Modifier.align(Alignment.TopCenter)){Text("底下的操作")}
            DirectionChangeBanner(DirectionNotice(1,now,setOf("jazz")),Modifier.align(Alignment.TopCenter))
        }}}
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithText("已换个方向 · 爵士").assertIsDisplayed()
        compose.onNodeWithText("底下的操作").performTouchInput {click()}
        assertEquals(1,clicks)
        capture("direction-hint-test-data")
        compose.mainClock.advanceTimeBy(4500)
        compose.onNodeWithText("已换个方向 · 爵士").assertDoesNotExist()
    }
    @Test fun miniPlayerExposesPreviousAndAllModesCanBeSelectedInThePlayer() {
        val song=QqCatalogueTrack("mode","mode","播放模式测试歌","歌手","专辑",200000)
        val status=mutableStateOf(PlayerStatus(mid="mode",queueSize=3))
        var previousCalls=0
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song)),status.value,
            {},{},{},{},{},{previousCalls++},{},{},{},{},{},onPlaybackMode={status.value=status.value.copy(playbackMode=it)})}
        compose.onNodeWithContentDescription("上一首").performClick()
        assertEquals(1,previousCalls)
        compose.onNodeWithContentDescription("播放模式：顺序播放，点按切换").performClick()
        assertEquals(PlaybackMode.LOOP.name,status.value.playbackMode)
        compose.onAllNodesWithText("播放模式测试歌").onLast().performClick()
        compose.onNodeWithText("单曲循环").performScrollTo().performClick()
        assertEquals(PlaybackMode.SINGLE.name,status.value.playbackMode)
        capture("player-modes-test-data")
    }
    @Test fun sessionIntentSheetProducesIndependentWantedAndExcludedTagSets() {
        var selected=ListeningIntent()
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test"),PlayerStatus(),{},{},{},{},{},{},{},{},{},{},{},onIntent={selected=it})}
        compose.onNodeWithText("调整这次选歌").performScrollTo().performClick()
        compose.onNodeWithContentDescription("想听爵士").performScrollTo().performClick()
        compose.onNodeWithContentDescription("避开摇滚").performScrollTo().performClick()
        capture("listening-intent-test-data")
        compose.onNodeWithText("应用并推荐").performScrollTo().performClick()
        assertEquals(setOf("jazz"),selected.wanted)
        assertEquals(setOf("rock"),selected.excluded)
    }
    @Test fun tagScoreSheetListsAllKnownLabelsIncludingZeroWithoutCreatingIntent() {
        val song=QqCatalogueTrack("score","score","分数测试歌","歌手","专辑",200000)
        val other=song.copy(mid="zero",mediaMid="zero",name="无反馈歌")
        show(LibraryState(accountScope="test",favorites=listOf(song,other),genres=mapOf(
            "score" to CatalogueGenre("score",setOf("pop","soul"),"qq-song-detail","",0,999),
            "zero" to CatalogueGenre("zero",setOf("jazz"),"qq-song-detail","",0,999)),listening=listOf(ListeningSignal("qq:score","fast"))))
        compose.onNodeWithText("调整这次选歌").performScrollTo().performClick()
        compose.onNodeWithText("标签分数").performClick()
        compose.onAllNodesWithText("爵士").onLast().assertIsDisplayed()
        compose.onNodeWithText("0 次记录").assertIsDisplayed()
        compose.onAllNodesWithText("-1.5 / -1.5 / -1.5").assertCountEquals(2)
        capture("genre-scores-test-data")
    }
    @Test fun songDetailsFavoriteUsesServerStateAndRequestsExplicitToggle() {
        val song=QqCatalogueTrack("heart","heart","收藏测试歌","歌手","专辑",200000)
        var toggled="";var refreshed=""
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",discoveries=listOf(song),favoriteStates=mapOf("heart" to true)),PlayerStatus(mid="heart"),
            {},{},{},{},{},{},{},{},{},{},{},onFavorite={toggled=it},onRefreshFavorite={refreshed=it})}
        compose.onAllNodesWithText("收藏测试歌").onLast().performClick()
        compose.onNodeWithText("已喜欢 · 取消收藏").performClick()
        assertEquals("heart",toggled);assertEquals("heart",refreshed)
    }
    @Test fun directionHistoryExposesConfirmedTargetAndUnconfirmedAttempt() {
        val rows=listOf(DirectionSwitch(1,0,"a","b","真实目标歌","GENRE","避开流行",state="PLAYING",detail="已核对目标歌曲身份及连续播放进度。",scores=mapOf("pop" to -12.0)),
            DirectionSwitch(2,1000,"c","d","加载失败歌","BATCH","标签不足",state="FAILED",detail="目标音源未能播放。"))
        show(LibraryState(directionHistory=rows))
        compose.onNodeWithText("我的",useUnmergedTree=true).performClick()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("最近换向记录"))
        compose.onNodeWithText("最近换向记录").performClick()
        compose.onNodeWithText("加载失败歌").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("真实目标歌"))
        compose.onNodeWithText("真实目标歌").assertIsDisplayed()
        compose.onNodeWithText("已确认播放 · 换曲风").assertExists()
        capture("direction-history-test-data")
    }
    @Test fun selectedPlaybackScopeCanChangeWithoutChangingCurrentTrack() {
        val song=QqCatalogueTrack("range","range","范围测试歌","歌手","专辑",200000)
        var selected=""
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song)),PlayerStatus(mid="range"),
            {},{},{},{},{},{},{},{},{},{},{},onPlaybackRange={selected=it})}
        compose.onAllNodesWithText("范围测试歌").onLast().performClick()
        compose.onNodeWithText("仅收藏").performScrollTo().performClick()
        assertEquals("FAVORITES",selected)
        capture("player-range-test-data")
    }
    @Test fun scoresAreProminentAndResetRequiresAnExplicitConfirmation() {
        val song=QqCatalogueTrack("score","score","分数测试歌","歌手","专辑",200000)
        val state=mutableStateOf(LibraryState(accountScope="test",favorites=listOf(song),genres=mapOf("score" to CatalogueGenre("score",setOf("pop"),"qq-song-detail","",0,999)),
            listening=listOf(ListeningSignal("qq:score","fast"))))
        var cleared=0
        compose.setContent {ResonanceScreen(state.value,PlayerStatus(),{},{},{},{},{},{},{},{},{},{},{},onClearScores={cleared++;state.value=state.value.copy(scoreListening=emptyList())})}
        compose.onNodeWithText("当前标签分数").assertIsDisplayed()
        compose.onNodeWithText("-3.0").assertIsDisplayed()
        capture("prominent-scores-test-data")
        compose.onNodeWithText("清除当前所有分数").performClick()
        assertEquals(0,cleared)
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
        assertEquals(0,cleared)
        compose.onNodeWithText("清除当前所有分数").performClick()
        compose.onNodeWithText("确认清零").performClick()
        assertEquals(1,cleared)
        compose.onNodeWithText("+0.0").assertIsDisplayed()
    }
    @Test fun profileShowsListeningMinutesAndLiveStatusTakesPrecedenceOverStoredBaseline() {
        val player=mutableStateOf(PlayerStatus(listeningTimeMs=125000))
        compose.setContent {ResonanceScreen(LibraryState(listeningTimeMs=60000),player.value,{},{},{},{},{},{},{},{},{},{},{})}
        compose.onNodeWithText("我的",useUnmergedTree=true).performClick()
        compose.onNodeWithText("陪伴时长").assertIsDisplayed()
        compose.onNodeWithText("2 分钟").assertIsDisplayed()
        compose.runOnIdle {player.value=player.value.copy(listeningTimeMs=180000)}
        compose.onNodeWithText("3 分钟").assertIsDisplayed()
        capture("companion-time-test-data")
    }
    @Test fun directFeedbackLetsUserSelectTagsAndDoesNotChangeQQFavorite() {
        val song=QqCatalogueTrack("feedback","feedback","反馈测试歌","歌手","专辑",200000)
        var selected=emptySet<String>();var more=false;var favorite=false
        compose.setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song),genres=mapOf(song.mid to CatalogueGenre(song.mid,setOf("pop","jazz"),"qq-song-detail","",0,999))),PlayerStatus(mid=song.mid),
            {},{},{},{},{},{},{},{},{},{},{},onFavorite={favorite=true},onSongFeedback={mid,tags,positive->assertEquals(song.mid,mid);selected=tags;more=positive})}
        compose.onAllNodesWithText("反馈测试歌").onLast().performClick()
        compose.onNodeWithText("多来这种").performScrollTo().performClick()
        compose.onAllNodesWithText("流行").onLast().performClick()
        capture("active-feedback-test-data")
        compose.onNodeWithText("确认调整").performScrollTo().performClick()
        assertEquals(setOf("jazz"),selected);assertTrue(more);assertFalse(favorite)
    }
    @Test fun detailShowsCommunityDescriptorsSeparatelyFromGenres() {
        val song=QqCatalogueTrack("descriptor","descriptor","线索测试歌","歌手","专辑",200000)
        show(LibraryState(accountScope="test",favorites=listOf(song),genres=mapOf(song.mid to CatalogueGenre(song.mid,setOf("pop"),"qq-song-detail","",0,999,descriptors=setOf("mood:calm","instrument:piano")))))
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("线索测试歌的歌曲详情").performClick()
        compose.onNodeWithText("听感线索 · 曲库标注").assertIsDisplayed()
        compose.onNodeWithText("钢琴 · 舒缓").performScrollTo().assertIsDisplayed()
        capture("recording-descriptors-test-data")
    }
}
