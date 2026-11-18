package com.resonance.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    private fun setContent(content: @Composable () -> Unit) {
        // The bitmap renderer used for review captures cannot replay hardware shared-element layers.
        compose.setContent { CompositionLocalProvider(LocalSharedArtwork provides false, content = content) }
    }
    private fun show(library:LibraryState,player:PlayerStatus=PlayerStatus(),play:(QqCatalogueTrack)->Unit={}) {
        setContent {ResonanceScreen(library,player,{}, {}, play, {}, {}, {}, {}, {}, {}, {}, {})}
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
        compose.onNodeWithText("Resonance").assertIsDisplayed()
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
    @Test fun connectedHomePutsListeningActionsAndScoresAboveTheFold() {
        val songs=listOf("晚风经过","此刻的温柔","下一站晴天").mapIndexed {i,name -> QqCatalogueTrack("test$i","test$i",name,"测试歌手","测试专辑",200000)}
        val labels=listOf("pop","r&b","jazz")
        val genres=songs.mapIndexed {i,song -> song.mid to CatalogueGenre(song.mid,setOf(labels[i]),"qq-song-detail","test fixture",0,Long.MAX_VALUE)}.toMap()
        show(LibraryState(accountScope="test-only",favorites=songs,genres=genres),PlayerStatus(mid="test0",playing=true,positionMs=50000,durationMs=200000))
        compose.onNodeWithText("Resonance").assertIsDisplayed()
        compose.onNodeWithText("相似推荐").assertIsDisplayed()
        compose.onNodeWithText("随心听").assertIsDisplayed()
        compose.onNodeWithText("标签分数").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放或暂停").assertIsDisplayed()
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
        setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=songs,genres=genres),PlayerStatus(),
            {},{},{},{},{},{},{},{},{},{},{},onRetryMissing={retried=true})}
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithText("管理").performClick()
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
        setContent {ResonanceScreen(LibraryState(accountScope="test",discoveries=listOf(song),recommendation=plan),PlayerStatus(),
            {},{},{ordinaryPlayed=true},{},{},{},{},{},{},{},{},onPlayRecommendation={selected=it})}
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("播放这批"))
        compose.onNodeWithText("播放这批").performClick()
        assertNull(selected)
        compose.onNodeWithText("收藏外测试歌曲").performScrollTo().performClick()
        assertEquals("newSong",selected)
        assertFalse(ordinaryPlayed)
    }
    @Test fun reducedMotionRefreshesTheBatchWithoutWaitingForFadingArtwork() {
        val resolver = compose.activity.contentResolver
        val key = Settings.Global.ANIMATOR_DURATION_SCALE
        val saved = Settings.Global.getFloat(resolver, key, 1f)
        Settings.Global.putFloat(resolver, key, 0f)
        try {
            val song = QqCatalogueTrack("batch","batch","换批测试歌","测试歌手","专辑",200000)
            val pick = StylePick(StyleTrack("qq:batch", song.name, song.artist, "qq:batch", emptySet()), "曲库候选", false)
            var refreshed: String? = null
            setContent {
                ResonanceScreen(LibraryState(accountScope = "test", favorites = listOf(song),
                    recommendation = StylePlan("候选", "试听", listOf(pick))), PlayerStatus(),
                    {},{},{},{},{},{},{},{},{},{},{},onRefreshRecommendation = { refreshed = it })
            }
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("换一批"))
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("换一批").performClick()
            assertEquals(song.mid, refreshed)
        } finally {
            compose.mainClock.autoAdvance = true
            Settings.Global.putFloat(resolver, key, saved)
        }
    }
    @Test fun discoveryTabShowsSourceAndPlaysTheVisibleCandidateQueue() {
        val seed=QqCatalogueTrack("seed","seed","收藏锚点","测试歌手","专辑",200000)
        val song=QqCatalogueTrack("outside","outside","发现里的歌曲","另一歌手","专辑",200000)
        var played=emptyList<String>();var selected:String?=null
        setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(seed),discoveries=listOf(song),
            discoverySources=mapOf("outside" to listOf("seed"))),PlayerStatus(),{},{},{},{},{},{},{},{},{},{},{},
            onPlayDiscovery={mids,mid->played=mids;selected=mid})}
        compose.onNodeWithText("发现",useUnmergedTree=true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("发现里的歌曲").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("发现里的歌曲").performScrollTo().performClick()
        assertEquals(listOf("outside"),played);assertEquals("outside",selected)
        compose.onNodeWithText("来自「收藏锚点」的相关推荐").assertExists()
        capture("discovery-test-data")
    }
    @Test fun cooldownIsSelectableAndSavedWithoutRestart() {
        show(LibraryState())
        compose.onNodeWithText("我的",useUnmergedTree=true).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("不额外等待"))
        compose.onNodeWithText("不额外等待").assertIsSelected()
        compose.onNodeWithText("15秒").performClick()
        compose.runOnIdle {assertEquals(15,compose.activity.getSharedPreferences("resonance_settings",0).getInt("qq_direction_cooldown_seconds",-1))}
    }
    @Test fun retryAndSupplementButtonsRemainAvailableDuringInitialAnalysis() {
        val song=QqCatalogueTrack("pending","pending","等待中的歌","歌手","专辑",200000)
        var secondary=false
        setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song),analyzing=true),PlayerStatus(),
            {},{},{},{},{},{},{},{},{},{},{},onSupplementMissing={secondary=true})}
        compose.onNodeWithText("收藏",useUnmergedTree=true).performClick()
        compose.onNodeWithText("管理").performClick()
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
        setContent {ResonanceTheme {Box(Modifier.width(360.dp)){CompleteTagRow(setOf("pop","rock","folk","jazz"))}}}
        for(tag in listOf("流行","摇滚","民谣","爵士"))compose.onNodeWithText(tag).assertIsDisplayed()
    }
    @Test fun directionHintFadesAwayAndDoesNotConsumeTouches() {
        compose.mainClock.autoAdvance=false
        var clicks=0
        val now=SystemClock.elapsedRealtime()
        setContent {ResonanceTheme {Box(Modifier.fillMaxSize()) {
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
    @Test fun miniPlayerCyclesModesAndPreviousIsAvailableInTheFullPlayer() {
        val song=QqCatalogueTrack("mode","mode","播放模式测试歌","歌手","专辑",200000)
        val status=mutableStateOf(PlayerStatus(mid="mode",queueSize=3))
        var previousCalls=0
        setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song)),status.value,
            {},{},{},{},{},{previousCalls++},{},{},{},{},{},onPlaybackMode={status.value=status.value.copy(playbackMode=it)})}
        compose.onNodeWithContentDescription("上一首").assertDoesNotExist()
        compose.onNodeWithContentDescription("播放模式：顺序播放，点按切换").performClick()
        assertEquals(PlaybackMode.LOOP.name,status.value.playbackMode)
        compose.onAllNodesWithText("播放模式测试歌").onLast().performClick()
        compose.onNodeWithContentDescription("上一首").performScrollTo().performClick()
        assertEquals(1,previousCalls)
        compose.onNodeWithText("单曲循环").performScrollTo().performClick()
        assertEquals(PlaybackMode.SINGLE.name,status.value.playbackMode)
        capture("player-modes-test-data")
    }

    @Test fun playerSharedArtworkOpensAndBackReturnsFocusToTheDock() {
        val song = QqCatalogueTrack("shared", "shared", "共享元素测试歌曲", "歌手", "", 200000)
        var previous = 0
        val resolver = compose.activity.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val previousScale = Settings.Global.getFloat(resolver, setting, 1f)
        Settings.Global.putFloat(resolver, setting, 1f)
        try {
            // Run the production artwork transition without software screenshot capture.
            compose.setContent {
                ResonanceScreen(LibraryState(accountScope = "test", favorites = listOf(song)),
                    PlayerStatus(mid = song.mid, durationMs = song.durationMs),
                    {}, {}, {}, {}, {}, { previous++ }, {}, {}, {}, {}, {})
            }
            compose.onAllNodesWithText(song.name).onLast().performClick()
            compose.onAllNodesWithContentDescription("播放或暂停").assertCountEquals(1)
            compose.onNodeWithContentDescription("上一首").performScrollTo().performClick()
            assertEquals(1, previous)

            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription("上一首").assertDoesNotExist()
            compose.onNodeWithContentDescription("播放或暂停").assertIsDisplayed()
            compose.onNodeWithText("为你", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            Settings.Global.putFloat(resolver, setting, previousScale)
        }
    }
    @Test fun sessionIntentSheetProducesIndependentWantedAndExcludedTagSets() {
        var selected=ListeningIntent()
        setContent {ResonanceScreen(LibraryState(accountScope="test"),PlayerStatus(),{},{},{},{},{},{},{},{},{},{},{},onIntent={selected=it})}
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
        compose.onNode(hasText("标签分数") and hasClickAction()).performClick()
        compose.onAllNodesWithText("爵士").onLast().assertIsDisplayed()
        compose.onNodeWithText("0 次记录").assertIsDisplayed()
        compose.onAllNodesWithText("-1.5 / -1.5 / -1.5").assertCountEquals(2)
        capture("genre-scores-test-data")
    }
    @Test fun songDetailsFavoriteUsesServerStateAndRequestsExplicitToggle() {
        val song=QqCatalogueTrack("heart","heart","收藏测试歌","歌手","专辑",200000)
        var toggled="";var refreshed=""
        setContent {ResonanceScreen(LibraryState(accountScope="test",discoveries=listOf(song),favoriteStates=mapOf("heart" to true)),PlayerStatus(mid="heart"),
            {},{},{},{},{},{},{},{},{},{},{},onFavorite={toggled=it},onRefreshFavorite={refreshed=it})}
        compose.onAllNodesWithText("收藏测试歌").onLast().performClick()
        compose.onNodeWithContentDescription("已喜欢 · 取消收藏").performClick()
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
        setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song)),PlayerStatus(mid="range"),
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
        setContent {ResonanceScreen(state.value,PlayerStatus(),{},{},{},{},{},{},{},{},{},{},{},onClearScores={cleared++;state.value=state.value.copy(scoreListening=emptyList())})}
        compose.onNodeWithText("标签分数").assertIsDisplayed()
        compose.onNodeWithText("−3.0").assertIsDisplayed()
        capture("prominent-scores-test-data")
        compose.onNodeWithContentDescription("清除当前所有分数").performClick()
        assertEquals(0,cleared)
        compose.onNodeWithText("取消",useUnmergedTree=true).performClick()
        assertEquals(0,cleared)
        compose.onNodeWithContentDescription("清除当前所有分数").performClick()
        compose.onNodeWithText("确认清零").performClick()
        assertEquals(1,cleared)
        compose.onNodeWithText("+0.0").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h820dp-xhdpi")
    fun redesignFitsAt360Dp() = assertHomeLayout("home-redesign-360")

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h891dp-xhdpi")
    fun redesignFitsAt412Dp() = assertHomeLayout("home-redesign-412")

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h820dp-xhdpi")
    fun migratedPagesFitAt360Dp() = assertMigratedPages("system-360")

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h891dp-xhdpi")
    fun migratedPagesFitAt412Dp() = assertMigratedPages("system-412")

    private fun assertMigratedPages(prefix: String) {
        val song = QqCatalogueTrack("system", "system", "界面测试歌曲", "测试歌手", "", 200000)
        val outside = song.copy(mid = "outside", mediaMid = "outside", name = "发现测试歌曲")
        val list = QqCataloguePlaylist("system-list", "测试歌单")
        val fixture = LibraryState(accountScope = "test", favorites = listOf(song), discoveries = listOf(outside),
            playlists = listOf(list), genres = mapOf(
                song.mid to CatalogueGenre(song.mid, setOf("pop", "jazz"), "qq-song-detail", "", 0, 999),
                outside.mid to CatalogueGenre(outside.mid, setOf("electronic"), "qq-song-detail", "", 0, 999)),
            listening = listOf(ListeningSignal("qq:${song.mid}", "completed")))
        val scene = mutableStateOf(0)
        val model = mutableStateOf(fixture)
        val resolver = compose.activity.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val oldScale = Settings.Global.getFloat(resolver, setting, 1f)
        Settings.Global.putFloat(resolver, setting, 0f)

        fun reset(next: Int = scene.value + 1) = compose.runOnIdle { scene.value = next; model.value = fixture }
        fun go(tab: String) = compose.onNodeWithText(tab, useUnmergedTree = true).performClick()
        fun assertFits(label: String) {
            val node = compose.onAllNodesWithText(label, useUnmergedTree = true).onLast()
            node.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Text layout missing for $label", layouts.isNotEmpty())
            layouts.forEach {
                assertFalse("Vertical overflow in $label", it.didOverflowHeight)
                for (line in 0 until it.lineCount) {
                    assertFalse("Truncation in $label", it.isLineEllipsized(line))
                    assertTrue("Horizontal overflow in $label", it.getLineRight(line) <= it.size.width + 1f)
                }
            }
        }
        try {
            setContent {
                key(scene.value) {
                    if (scene.value == 92) ResonanceTheme {
                        ResonanceDialog("核对「界面测试歌曲」的录音版本", {}) {
                            Text("核对歌手、专辑、时长与现场/混音等版本。")
                            OutlineButton("版本相符，读取此录音曲风", {})
                            TextAction("都不匹配 / 关闭", {}, ResonancePalette.muted)
                        }
                    } else if (scene.value >= 90) ResonanceTheme {
                        Column(Modifier.fillMaxSize().padding(Space.x5)) {
                            AccountSheetHeader("y.qq.com", scene.value == 90, scene.value == 91, true, {}, {}, {})
                        }
                    } else ResonanceScreen(model.value,
                        PlayerStatus(mid = song.mid, durationMs = 200000, positionMs = 40000), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                        { selected -> model.value = fixture.copy(openedPlaylist = selected, playlistTracks = listOf(song)) })
                }
            }
            go("发现")
            assertFits("发现新歌")
            assertFits("搜索发现里的歌曲、歌手")
            assertFits("全部曲风")
            capture("$prefix-discovery")

            reset()
            go("收藏")
            assertFits("我的收藏")
            assertFits("搜索歌曲、歌手")
            capture("$prefix-collection")
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("测试歌单"))
            compose.onNodeWithText("测试歌单").performClick()
            assertFits("返回收藏")
            capture("$prefix-playlist")

            reset()
            go("我的")
            assertFits("共振图谱")
            assertFits("陪伴时长")
            capture("$prefix-profile")
            compose.onAllNodes(hasScrollToIndexAction() and hasScrollAction()).onFirst().performScrollToNode(hasText("最近换向记录"))
            compose.onNodeWithText("最近换向记录").performClick()
            capture("$prefix-history")

            reset()
            compose.onAllNodesWithText(song.name).onLast().performClick()
            compose.onNodeWithContentDescription("上一首").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("播放或暂停").assertIsDisplayed()
            capture("$prefix-player")

            reset()
            compose.onNodeWithText("调整这次选歌").performScrollTo().performClick()
            assertFits("这次想听什么")
            capture("$prefix-intent")
            compose.onNode(hasText("标签分数") and hasClickAction()).performClick()
            assertFits("搜索标签")
            capture("$prefix-scores")
            compose.onAllNodesWithText("清除当前所有分数").onLast().performScrollTo().performClick()
            assertFits("确认清零")
            capture("$prefix-reset")

            reset()
            go("收藏")
            compose.onNodeWithContentDescription("${song.name}的歌曲详情").performClick()
            assertFits("全部曲风标签")
            capture("$prefix-detail")
            compose.onNodeWithText("暂时少放").performScrollTo().performClick()
            assertFits("确认调整")
            capture("$prefix-feedback")

            reset(90)
            assertFits("打开官方播放器页继续授权")
            assertFits("正在打开官方页面…")
            capture("$prefix-account")
            reset(91)
            assertFits("官方页面暂时无法打开")
            assertFits("重试")
            capture("$prefix-account-error")

            reset(92)
            assertFits("核对「界面测试歌曲」的录音版本")
            assertFits("版本相符，读取此录音曲风")
            capture("$prefix-recording-dialog")
        } finally {
            Settings.Global.putFloat(resolver, setting, oldScale)
        }
    }

    private fun assertHomeLayout(screenshot: String) {
        val hero = QqCatalogueTrack("hero","hero","Moth To a Flame x After Hours (TikTok Version)","Music Factory","专辑",200000)
        val pick = QqCatalogueTrack("pick","pick","推荐歌曲","测试歌手","专辑",200000)
        val genres = mapOf(hero.mid to CatalogueGenre(hero.mid, setOf("dance"), "qq", "", 1, 999),
            pick.mid to CatalogueGenre(pick.mid, setOf("electronica", "pop", "jazz"), "qq", "", 1, 999))
        val plan = StylePlan("QQ 相关歌曲", "真实的推荐说明", listOf(
            StylePick(StyleTrack("qq:pick", pick.name, pick.artist, "qq:pick", setOf("pop")), "共同曲风：流行", false)
        ))
        show(LibraryState(accountScope = "test", favorites = listOf(hero, pick), genres = genres, analyzing = true,
            recommendation = plan, scoreListening = List(500) { ListeningSignal("qq:hero", "fast") },
            manualScores = mapOf("dance" to -6.0)),
            PlayerStatus(mid = hero.mid, playing = true, positionMs = 80_000, durationMs = 200_000))
        capture(screenshot)
        for (label in listOf("Resonance", "QQ 音乐已连接", "相似推荐", "随心听", "调整这次选歌", "标签分数", "−66.0")) {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText(label, useUnmergedTree = true)
            node.performScrollTo().assertIsDisplayed()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("$label at ${layout.size}: clipped vertically", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse("$label: line $line was ellipsized", layout.isLineEllipsized(line))
                assertTrue("$label: line $line exceeded ${layout.size.width}px",
                    layout.getLineRight(line) <= layout.size.width + 1f)
            }
        }
        compose.onNodeWithContentDescription("正在自动整理曲风 · 2 首已有资料").assertExists()
        compose.onNodeWithContentDescription("播放或暂停").assertIsDisplayed()
        compose.onNodeWithText("为你", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("播放这批"))
        compose.onNodeWithText("换一批").assertIsDisplayed()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("共同曲风：流行"))
        compose.onNodeWithText("共同曲风：流行").assertIsDisplayed()
        capture("$screenshot-recommendations")
    }
    @Test fun profileShowsListeningMinutesAndLiveStatusTakesPrecedenceOverStoredBaseline() {
        val clock = mutableStateOf(PlaybackClock("", 0, 2))
        setContent {ResonanceScreen(LibraryState(listeningTimeMs=60000),PlayerStatus(),
            {},{},{},{},{},{},{},{},{},{},{},liveClock=clock)}
        compose.onNodeWithText("我的",useUnmergedTree=true).performClick()
        compose.onNodeWithText("陪伴时长").assertIsDisplayed()
        compose.onNodeWithText("2 分钟").assertIsDisplayed()
        compose.runOnIdle {clock.value=clock.value.copy(listeningMinutes=3)}
        compose.onNodeWithText("3 分钟").assertIsDisplayed()
        capture("companion-time-test-data")
    }
    @Test fun directFeedbackLetsUserSelectTagsAndDoesNotChangeQQFavorite() {
        val song=QqCatalogueTrack("feedback","feedback","反馈测试歌","歌手","专辑",200000)
        var selected=emptySet<String>();var more=false;var favorite=false
        setContent {ResonanceScreen(LibraryState(accountScope="test",favorites=listOf(song),genres=mapOf(song.mid to CatalogueGenre(song.mid,setOf("pop","jazz"),"qq-song-detail","",0,999))),PlayerStatus(mid=song.mid),
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
