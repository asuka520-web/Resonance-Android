package com.resonance.app

import android.graphics.BitmapFactory
import android.util.LruCache
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.math.abs

private data class PageContent(val tab: Int, val library: LibraryState)

private val Ink = ResonancePalette.ink
private val Panel = ResonancePalette.surface
private val Raised = ResonancePalette.raised
private val Selected = ResonancePalette.line
private val Muted = ResonancePalette.muted
private val Accent = ResonancePalette.signal
private val Cream = ResonancePalette.sand
private val SoftLine = ResonancePalette.line

enum class ResonanceGlyph { PLAY, PAUSE, NEXT, PREVIOUS, HOME, LIBRARY, PERSON, SEARCH, HEART, BACK, CLOSE, REFRESH, WAVE, DETAILS }

@Composable
fun Glyph(type: ResonanceGlyph, modifier: Modifier = Modifier.size(Space.x6), color: Color = Cream) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1*w,y1*h), Offset(x2*w,y2*h), w*.075f, StrokeCap.Round)
        when(type) {
            ResonanceGlyph.PLAY -> drawPath(Path().apply { moveTo(w*.32f,h*.2f); lineTo(w*.8f,h*.5f); lineTo(w*.32f,h*.8f); close() },color)
            ResonanceGlyph.PAUSE -> { drawRoundRect(color,Offset(w*.25f,h*.2f),Size(w*.16f,h*.6f)); drawRoundRect(color,Offset(w*.59f,h*.2f),Size(w*.16f,h*.6f)) }
            ResonanceGlyph.NEXT -> { drawPath(Path().apply { moveTo(w*.2f,h*.22f); lineTo(w*.67f,h*.5f); lineTo(w*.2f,h*.78f); close() },color); line(.77f,.24f,.77f,.76f) }
            ResonanceGlyph.PREVIOUS -> { drawPath(Path().apply { moveTo(w*.8f,h*.22f); lineTo(w*.33f,h*.5f); lineTo(w*.8f,h*.78f); close() },color); line(.23f,.24f,.23f,.76f) }
            ResonanceGlyph.SEARCH -> { drawCircle(color,w*.27f,Offset(w*.43f,h*.43f),style=Stroke(w*.07f)); line(.63f,.63f,.86f,.86f) }
            ResonanceGlyph.BACK -> { line(.65f,.2f,.35f,.5f); line(.35f,.5f,.65f,.8f) }
            ResonanceGlyph.CLOSE -> { line(.25f,.25f,.75f,.75f); line(.25f,.75f,.75f,.25f) }
            ResonanceGlyph.HOME -> { drawPath(Path().apply { moveTo(w*.16f,h*.46f);lineTo(w*.5f,h*.16f);lineTo(w*.84f,h*.46f);lineTo(w*.84f,h*.84f);lineTo(w*.16f,h*.84f);close() },color,style=Stroke(w*.065f)); line(.43f,.84f,.43f,.6f);line(.43f,.6f,.59f,.6f);line(.59f,.6f,.59f,.84f) }
            ResonanceGlyph.LIBRARY -> { line(.2f,.2f,.2f,.8f);line(.43f,.2f,.43f,.8f);line(.65f,.23f,.8f,.78f) }
            ResonanceGlyph.PERSON -> { drawCircle(color,w*.16f,Offset(w*.5f,h*.3f),style=Stroke(w*.065f));drawArc(color,190f,160f,false,Offset(w*.2f,h*.53f),Size(w*.6f,h*.42f),style=Stroke(w*.065f,cap=StrokeCap.Round)) }
            ResonanceGlyph.HEART -> drawPath(Path().apply { moveTo(w*.5f,h*.83f);cubicTo(w*.06f,h*.55f,w*.03f,h*.21f,w*.3f,h*.2f);cubicTo(w*.42f,h*.2f,w*.47f,h*.26f,w*.5f,h*.32f);cubicTo(w*.6f,h*.07f,w*.97f,h*.19f,w*.87f,h*.46f);cubicTo(w*.82f,h*.62f,w*.65f,h*.75f,w*.5f,h*.83f);close() },color)
            ResonanceGlyph.REFRESH -> { drawArc(color,35f,285f,false,Offset(w*.2f,h*.2f),Size(w*.6f,h*.6f),style=Stroke(w*.07f,cap=StrokeCap.Round));line(.82f,.2f,.82f,.43f);line(.82f,.43f,.61f,.42f) }
            ResonanceGlyph.WAVE -> { listOf(.25f to .2f,.42f to .05f,.59f to .32f,.76f to .16f).forEach { (x,y) -> line(x,.5f-y,x,.5f+y) } }
            ResonanceGlyph.DETAILS -> {for(y in listOf(.25f,.5f,.75f))drawCircle(color,w*.065f,Offset(w*.5f,h*y))}
        }
    }
}

@Composable
private fun PlaybackChip(label: String, selected: Boolean, onClick: () -> Unit) =
    ResonanceChip(label, selected, onClick = onClick)

private object CoverMemory {
    val images = object : LruCache<String, ImageBitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) =
            (value.width * value.height * 4 + 1023) / 1024
    }
    private val locks = Array(16) { Mutex() }
    private val downloads = Semaphore(3)

    suspend fun load(url: String, cacheDir: java.io.File, album: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val lock = locks[(url.hashCode() and Int.MAX_VALUE) % locks.size]
        lock.withLock {
            images.get(url) ?: downloads.withPermit {
                download(url, cacheDir, album)?.also { images.put(url, it) }
            }
        }
    }

    private fun download(url: String, cacheDir: java.io.File, album: String): ImageBitmap? = try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (connection.responseCode != 200) null else connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (output.size() + bytesRead > 1_048_576) return@use null
                    output.write(buffer, 0, bytesRead)
                }
                val data = output.toByteArray()
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { bitmap ->
                    try {
                        CoverThumbnails.save(cacheDir, album, data)
                    } catch (_: SecurityException) {
                        // An unavailable thumbnail cache leaves ordinary artwork usable.
                    }
                    bitmap.asImageBitmap()
                }
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
@Composable
internal fun rememberCoverArtwork(song: QqCatalogueTrack?): ImageBitmap? = rememberArtworkLoad(song).image

internal data class ArtworkLoad(val image: ImageBitmap?, val pending: Boolean)

@Composable
internal fun rememberArtworkLoad(song: QqCatalogueTrack?): ArtworkLoad {
    val cacheDir = LocalContext.current.cacheDir
    val url = song?.let(CatalogueStore::artwork)
    var load by remember(url) {
        val cached = url?.let { CoverMemory.images.get(it) }
        mutableStateOf(ArtworkLoad(cached, url != null && cached == null))
    }
    LaunchedEffect(url) {
        if (url != null && load.pending) load = ArtworkLoad(CoverMemory.load(url, cacheDir, song?.albumMid.orEmpty()), pending = false)
    }
    return load
}

internal fun genreName(value: String) = when(value) {
    "pop"->"流行";"rock"->"摇滚";"folk"->"民谣";"jazz"->"爵士";"electronic"->"电子";"hip hop"->"说唱";
    "r&b"->"R&B";"classical"->"古典";"soul"->"灵魂乐";"funk"->"放克";"country"->"乡村";"metal"->"金属";else->value
}

private fun descriptorName(value:String)=when(value){"mood:bright"->"明快";"mood:sad"->"低落";"mood:calm"->"舒缓";"mood:energetic"->"活力";
    "instrument:piano"->"钢琴";"instrument:guitar"->"吉他";"instrument:violin"->"小提琴";"instrument:saxophone"->"萨克斯";"voice:instrumental"->"器乐";else->value}
@Composable private fun TinyTag(text:String, active:Boolean=false) {
    Text(text, color=if(active)Accent else Muted, fontSize=TypeSize.caption,
        modifier=Modifier.clip(Corners.tag).background(if(active)Selected else Raised).padding(horizontal=Space.connectionDot,vertical=Space.x1))
}
@Composable
private fun TrackGenres(genre: CatalogueGenre?) {
    val labels = genre?.labels.orEmpty()
    if (labels.isEmpty()) Text(GenreLookupPolicy.description(genre), color = Muted, fontSize = TypeSize.caption)
    else CompleteTagRow(labels)
    if (genre?.descriptors?.isNotEmpty() == true) {
        Text("曲库线索 · " + genre.descriptors.sorted().joinToString(" · ") { descriptorName(it) },
            color = Muted, fontSize = TypeSize.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun ResonanceScreen(library:LibraryState, player:PlayerStatus, onLogin:()->Unit, onSync:()->Unit,
    onPlay:(QqCatalogueTrack)->Unit, onToggle:()->Unit, onNext:()->Unit, onPrevious:()->Unit, onSeek:(Float)->Unit,
    onLogout:()->Unit, onLegacy:()->Unit, onPlaylists:()->Unit, onPlaylist:(QqCataloguePlaylist)->Unit,
    onRetryMissing:()->Unit = {QqLibrary.retryMissing()}, onRefreshDiscovery:()->Unit = {QqLibrary.warmDiscovery(true)},
    onPlayRecommendation:(String?)->Unit = {}, onPlayDiscovery:(List<String>,String?)->Unit = {_,_->},
    onSupplementMissing:()->Unit = {QqLibrary.retryMissing(true)},onSurprise:()->Unit={},
    onPlaybackMode:(String)->Unit={},onIntent:(ListeningIntent)->Unit={QqLibrary.setIntent(it)},
    onFavorite:(String)->Unit={},onRefreshFavorite:(String)->Unit={},onEnrich:(String?)->Unit={},onPlaybackRange:(String)->Unit={},onClearScores:()->Unit={},
    onSongFeedback:(String,Set<String>,Boolean)->Unit={_,_,_->}, liveClock:State<PlaybackClock>?=null,
    onRefreshRecommendation:(String)->Unit={QqLibrary.similar(it)}) {
    val clock=liveClock ?: rememberUpdatedState(player.clock())
    val context=LocalContext.current
    val settings=remember {context.getSharedPreferences("resonance_settings",0)}
    var tab by remember {mutableIntStateOf(0)}
    var search by remember {mutableStateOf("")}
    var filter by remember {mutableIntStateOf(0)}
    var showCatalogueDetails by remember {mutableStateOf(false)}
    var discoverySearch by remember {mutableStateOf("")}
    var unheardOnly by remember {mutableStateOf(false)}
    var discoveryGenre by remember {mutableStateOf<String?>(null)}
    var cooldownSeconds by remember {mutableIntStateOf(settings.getInt("qq_direction_cooldown_seconds",0).takeIf {it in DirectionCooldown.options}?:0)}
    val pages = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    val listState = pages[tab]
    LaunchedEffect(tab) {listState.scrollToItem(0)}
    var expandedPlayer by remember {mutableStateOf(false)}
    var detailSong by remember {mutableStateOf<QqCatalogueTrack?>(null)}
    var showIntent by remember {mutableStateOf(false)}
    var scoreTab by remember {mutableStateOf(false)}
    var showScoreReset by remember {mutableStateOf(false)}
    var feedbackSong by remember {mutableStateOf<QqCatalogueTrack?>(null)}
    var feedbackMore by remember {mutableStateOf(true)}
    var feedbackLabels by remember {mutableStateOf<Set<String>>(emptySet())}
    fun openFeedback(song:QqCatalogueTrack,more:Boolean) {
        feedbackSong=song;feedbackMore=more;feedbackLabels=library.genres[song.mid]?.labels.orEmpty()
        expandedPlayer=false;detailSong=null
    }
    var showDirectionHistory by remember {mutableStateOf(false)}
    var previewNotice by remember {mutableStateOf<DirectionNotice?>(null)}
    var draftIntent by remember {mutableStateOf(ListeningIntent())}
    var showRecommendationReason by remember {mutableStateOf(false)}
    var showConnectionDetails by remember {mutableStateOf(false)}
    var autoDirection by remember {mutableStateOf(settings.getBoolean("qq_auto_direction",true))}
    var supplements by remember {mutableStateOf(settings.getBoolean("catalogue_external_tags",true))}
    var surpriseEnabled by remember {mutableStateOf(settings.getBoolean("qq_surprise_enabled",true))}
    val tracksByMid = remember(library.favorites, library.discoveries) { library.allTracks.associateBy { it.mid } }
    val current = tracksByMid[player.mid]
    val seed=current ?: remember(library.favorites,library.genres) {
        library.favorites.firstOrNull {library.genres[it.mid]?.labels?.isNotEmpty()==true} ?: library.favorites.firstOrNull()
    }
    val seedCover = rememberArtworkLoad(seed)
    val seedArtwork = seedCover.image
    val connected=library.accountScope.isNotBlank()
    val motionEnvironment = rememberMotionEnvironment()
    val motionEnabled = motionEnvironment.enabled
    val sharedArtwork = motionEnabled && LocalSharedArtwork.current
    val controlsActive = !expandedPlayer && detailSong == null && !showIntent && !showScoreReset && feedbackSong == null && !showDirectionHistory
    val homeActive = tab == 0 && controlsActive
    val ticker = ResonanceEngine.clock
    SideEffect { ResonanceEngine.updatePlayback(player.playing, player.mid) }
    DisposableEffect(Unit) {
        onDispose { ResonanceEngine.updatePlayback(false, ""); ticker.cancelSand() }
    }
    val renderer = remember { HomeSandRenderer() }
    val logo = remember { MotionFloat(0f) }
    var coldPlate by rememberSaveable { mutableStateOf(true) }
    val plateVisible by remember(listState) { derivedStateOf { listState.partVisible("resonance-plate") } }
    val logoVisible by remember(listState) { derivedStateOf { listState.partVisible("resonance-logo") } }
    val animatePlate = homeActive && plateVisible
    val scores = remember(tab, library.favorites, library.discoveries, library.genres, library.scoreSignals, library.manualScores) {
        if(tab != 2 && connected) library.genreScores().sortedByDescending { abs(it.combined) } else emptyList()
    }
    val scoreSnapshot = remember(scores) { scores.filter { it.combined != 0.0 }.associate { it.label to it.combined } }
    var seenScores by remember(library.accountScope) { mutableStateOf<Map<String, Double>?>(null) }
    var seenIntent by remember(library.accountScope) { mutableStateOf(library.intent) }
    var pendingStrike by remember(library.accountScope) { mutableStateOf(false) }
    LaunchedEffect(tab, scoreSnapshot, library.intent) {
        if (tab != 0) {
            seenScores = null
        } else {
            if (motionEnvironment.systemEnabled && plateVisible && seenScores != null &&
                (seenScores != scoreSnapshot || seenIntent != library.intent)) pendingStrike = true
            seenScores = scoreSnapshot
            seenIntent = library.intent
        }
    }
    LaunchedEffect(pendingStrike, animatePlate, motionEnvironment) {
        if (!pendingStrike) return@LaunchedEffect
        if (!plateVisible || tab != 0 || !motionEnvironment.systemEnabled) pendingStrike = false
        else if (animatePlate && motionEnabled) {
            renderer.strike(ticker)
            pendingStrike = false
        }
    }
    LaunchedEffect(ResonanceEngine.isPlaying, motionEnabled, controlsActive, logoVisible) {
        val animate = motionEnabled && controlsActive && logoVisible
        logo.update(if (ResonanceEngine.isPlaying && animate) 1f else 0f, Motion.LogoRest, Motion.settle, ticker, animate)
    }
    val playlistSeeds = remember(library.accountScope) { mutableStateMapOf<String, String>() }
    var requestedPlaylist by remember(library.accountScope) { mutableStateOf<QqCataloguePlaylist?>(null) }
    LaunchedEffect(library.openedPlaylist?.id, library.playlistTracks) {
        val playlistId = library.openedPlaylist?.id
        val first = library.playlistTracks.firstOrNull()?.mid
        if (playlistId != null && first != null) playlistSeeds[playlistId] = first
    }
    val browserEntries = rememberDiscoveryEntries(library, discoverySearch, unheardOnly, discoveryGenre, tab == 1)
    val thumbnailDir = LocalContext.current.cacheDir
    val discoveryGenres by key(library.accountScope) {
        produceState(emptyList<List<GenreTileData>>(), tab, library.favorites, library.discoveries, library.genres, scores) {
            if (tab == 1) {
                val cached = withContext(Dispatchers.IO) { CoverThumbnails.availableAlbums(thumbnailDir) }
                value = withContext(Dispatchers.Default) { discoveryTiles(library, scores, cached).chunked(3) }
            }
        }
    }
    val profileRenderer = remember(library.accountScope) { GraphSandRenderer() }
    val profileTimeline = rememberProfileTimeline(library, tab == 3 && motionEnvironment.windowReady, player.mid)
    val companionMinutes = profileMinutes(library.listeningTimeMs, clock)
    val profileGraphVisible by remember(pages[3]) { derivedStateOf { pages[3].partVisible("profile-graph") } }
    val animateGraph = tab == 3 && controlsActive && profileGraphVisible
    RunResonanceFrames(ticker, motionEnabled && (controlsActive || expandedPlayer),
        ResonanceEngine.isPlaying && controlsActive && (current != null || logoVisible), drawSand = false)
    val hasPlateWork by remember(ticker) { derivedStateOf { ticker.hasSandWork() } }
    MonitorResonanceFrames(ticker, motionEnabled && (animatePlate || animateGraph) && (player.playing || hasPlateWork) && !ticker.reducedQuality)
    val notice=listOfNotNull(player.directionNotice,library.feedback,previewNotice).maxByOrNull {it.createdAt}
    LaunchedEffect(expandedPlayer,current?.mid,detailSong?.mid) {
        if(connected)(detailSong?.mid?:current?.mid?.takeIf {expandedPlayer})?.let(onRefreshFavorite)
    }
    ResonanceTheme { CompositionLocalProvider(LocalResonanceMotion provides motionEnabled) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
        val shared = this
        Box(Modifier.fillMaxSize()) {
        Scaffold(modifier = if (expandedPlayer) Modifier.clearAndSetSemantics {} else Modifier,
            containerColor=ResonancePalette.ink,bottomBar={
            Dock(tab, current, player, clock, seedArtwork, ticker, { tab = it }, { expandedPlayer = true },
                onPlaybackMode, onToggle, onNext) { plate, content ->
                if (sharedArtwork && current != null) {
                    AnimatedVisibility(!expandedPlayer, enter = EnterTransition.None, exit = ExitTransition.None) {
                        val art = shared.playerArtwork(current.mid, plate, this)
                        Box(Modifier.fillMaxSize().then(art)) { content() }
                    }
                } else if (!expandedPlayer) content()
            }
        }) {padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
            val pageOffset = with(LocalDensity.current) { Motion.SecondaryOffsetDp.dp.roundToPx() }
            AnimatedContent(PageContent(tab, library), label = "page",
                contentKey = { it.tab to it.library.openedPlaylist?.id?.takeIf { _ -> it.tab == 2 } },
                transitionSpec = {
                    when {
                        !motionEnabled -> EnterTransition.None togetherWith ExitTransition.None
                        initialState.tab != targetState.tab -> {
                            val fade = tween<Float>(Motion.PageFade, easing = Motion.easeInOut)
                            fadeIn(fade) togetherWith fadeOut(fade)
                        }
                        else -> {
                            val fade = tween<Float>(Motion.SecondaryPage, easing = Motion.responsive)
                            val slide = tween<androidx.compose.ui.unit.IntOffset>(Motion.SecondaryPage, easing = Motion.responsive)
                            val distance = if (targetState.library.openedPlaylist == null) -pageOffset else pageOffset
                            (fadeIn(fade) + slideInVertically(slide) { distance }) togetherWith
                                (fadeOut(fade) + slideOutVertically(slide) { -distance })
                        }
                    }
                }) { (page, library) ->
            val pageList = if (page == 2 && library.openedPlaylist != null) rememberLazyListState() else pages[page]
            LazyColumn(Modifier.fillMaxSize(), state = pageList,
                contentPadding = PaddingValues(horizontal = Space.x5, vertical = Space.x3),
                verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                item(key = "resonance-logo") {
                    TopBar(connected, ticker, logo, page == tab && controlsActive && logoVisible) { if (connected) tab = 3 else onLogin() }
                }
                if(page==0) {
                    val refreshSimilar: () -> Unit = { seed?.let { QqLibrary.similar(it.mid) } }
                    item(key = "resonance-plate") {
                        HomePlate(seed, seedCover, renderer, ticker, animatePlate,
                            coldPlate, motionEnvironment.systemEnabled) { coldPlate = false }
                    }
                    item {
                        HomeHero(connected, seed, onLogin, refreshSimilar, onSurprise) {
                            draftIntent=library.intent;scoreTab=false;showIntent=true
                        }
                    }
                    if(library.syncing || library.analyzing) item {
                        HomeCatalogueStatus(library.syncing, library.message, library.taggedCount)
                    }
                    if(connected && library.intent.active) item {
                        Text("想听：${library.intent.wanted.sorted().joinToString {genreName(it)}.ifBlank {"不限"}}\n避开：${library.intent.excluded.sorted().joinToString {genreName(it)}.ifBlank {"不限"}}",
                            color=ResonancePalette.muted,fontSize=TypeSize.caption,lineHeight=TypeSize.mediumLine)
                    }
                    if(connected) item {
                        HomeScoreCards(scores, ticker, motionEnvironment.systemEnabled,
                            {draftIntent=library.intent;scoreTab=true;showIntent=true},{showScoreReset=true})
                    }
                    val recommendations=library.recommendation
                    if(recommendations!=null) {
                        val refreshMid = seed?.mid ?: recommendations.picks.firstOrNull()?.track?.mediaId?.removePrefix("qq:")
                        item {
                            SectionHeader("为你推荐", refreshMid?.let { mid -> { onRefreshRecommendation(mid) } }, "换一批",
                                if(recommendations.picks.isNotEmpty()) ({onPlayRecommendation(null)}) else null)
                        }
                        item {
                            HomeRecommendationReason(recommendations, showRecommendationReason) {
                                showRecommendationReason = !showRecommendationReason
                            }
                        }
                        // Keep the artwork slot mounted during batch replacement so both covers can blend.
                        itemsIndexed(recommendations.picks, key = { index, _ -> "resonance-pick-$index" }) { _, pick ->
                            tracksByMid[pick.track.mediaId.removePrefix("qq:")]?.let {song ->
                                TrackRow(song, pick.reason, song.mid==player.mid,
                                    {onPlayRecommendation(song.mid)}, {detailSong=song}, unfamiliar = pick.unfamiliar)
                                if(showRecommendationReason && pick.reason.isNotBlank()) {
                                    Text(pick.reason,color=ResonancePalette.signal,fontSize=TypeSize.secondary)
                                }
                            }
                        }
                    } else {
                        item { SectionHeader("收藏精选", {tab=2}, "查看全部") }
                        items(library.favorites.take(6),key={it.mid}) {song ->
                            TrackRow(song, "", song.mid==player.mid, {onPlay(song)}, {detailSong=song})
                        }
                    }
                    if(library.favorites.isEmpty()) item {Text(if(connected)"收藏正在路上，稍后就会出现在这里。" else "登录后自动同步收藏，无需逐首填写曲风。",color=ResonancePalette.muted,fontSize=TypeSize.action,modifier=Modifier.padding(vertical=Space.x4))}
                    if(connected)item {
                        SectionHeader("收藏外候选 · ${library.outsideCount} 首", {tab=1}, "去发现")
                    }
                } else if(page==1) {
                    item { SectionHeader("发现新歌", onRefreshDiscovery.takeUnless { library.discovering },
                        if (library.discovering) "发现中…" else "发现更多") }
                    item { SearchField(discoverySearch, { discoverySearch = it }, "搜索发现里的歌曲、歌手") }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2),
                            verticalAlignment = Alignment.CenterVertically) {
                            PlaybackChip("本机未听", unheardOnly) { unheardOnly = !unheardOnly }
                            PlaybackChip("全部曲风", discoveryGenre == null) { discoveryGenre = null }
                            Spacer(Modifier.weight(1f))
                            TextAction(if (library.intent.active) "调整已选条件" else "选歌条件",
                                { draftIntent = library.intent; scoreTab = false; showIntent = true }, Accent)
                        }
                    }
                    items(discoveryGenres, key = { "genre-grid-${it.first().label}" }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.tileGap)) {
                            row.forEach { tile ->
                                GenreTile(tile, discoveryGenre == tile.label, { discoveryGenre = tile.label }, Modifier.weight(1f))
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    val entries = browserEntries.orEmpty()
                    if (entries.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${entries.size} 首可浏览", fontSize = TypeSize.secondary, color = Muted, modifier = Modifier.weight(1f))
                            PrimaryButton(if (entries.size > 100) "播放前 100 首" else "播放这批",
                                { onPlayDiscovery(entries.take(100).map { it.song.mid }, null) })
                        }
                    }
                    if (entries.isEmpty()) when {
                        !connected -> item { EmptyState("连接 QQ 音乐后自动发现新歌。", "连接 QQ 音乐", onLogin) }
                        browserEntries == null || library.discovering -> item(key = "discovery-loading") {
                            LoadingState(if (library.discovering) "正在寻找候选，稍后就会出现。" else "正在整理候选…")
                        }
                        library.discoveryMessage.contains("失败") -> item {
                            ErrorState("发现新歌", library.discoveryMessage, "发现更多", onRefreshDiscovery)
                        }
                        else -> item { EmptyState("暂无符合条件的新歌，可清除筛选或点“发现更多”。", "发现更多", onRefreshDiscovery) }
                    }
                    if (entries.isNotEmpty() && library.discoveryMessage.contains("失败")) item {
                        ErrorState("发现新歌", library.discoveryMessage, "发现更多", onRefreshDiscovery)
                    }
                    items(entries, key = { it.song.mid }) { entry ->
                        val song = entry.song
                        val genre = library.genres[song.mid]
                        TrackRow(song, entry.reason, song.mid == player.mid, {
                            val index = entries.indexOfFirst { it.song.mid == song.mid }
                            val window = entries.drop((index - 10).coerceAtLeast(0)).take(100)
                            onPlayDiscovery(window.map { it.song.mid }, song.mid)
                        }, { detailSong = song }, subtitle = song.artist + if (entry.locallyHeard) " · 本机有记录" else "",
                            detailsButton = true) { TrackGenres(genre) }
                    }
                } else if(page==2) {
                    item {SectionHeader(library.openedPlaylist?.name ?: "我的收藏",
                        {if(library.openedPlaylist!=null) QqLibrary.closePlaylist() else onSync()},
                        if(library.openedPlaylist!=null)"返回收藏" else if(library.syncing)"同步中…" else "同步收藏")}
                    item { SearchField(search, { search = it }, "搜索歌曲、歌手") }
                    if(library.openedPlaylist==null && connected) item {
                        val coverage=if(library.favorites.isEmpty())0 else library.taggedCount*100/library.favorites.size
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = Space.touch)
                                .clickable(role=Role.Button) {showCatalogueDetails=!showCatalogueDetails}
                                .semantics {stateDescription=if(showCatalogueDetails)"已展开" else "已收起"}
                                .padding(vertical = Space.x3),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(Space.logoStroke)) {
                                Text("曲风资料",fontSize=TypeSize.body,fontWeight=FontWeight.Medium)
                                Text(if(library.enriching)"补充中 ${library.enrichedProgress}/${library.enrichmentTotal}"
                                    else "${library.taggedCount} / ${library.favorites.size} 首 · $coverage%",color=Muted,fontSize=TypeSize.caption)
                            }
                            Text(if(showCatalogueDetails)"收起" else "管理",color=Accent,fontSize=TypeSize.secondary)
                        }
                    }
                    if (library.openedPlaylist == null && connected) item { HorizontalDivider(color = SoftLine) }
                    if(library.openedPlaylist==null && connected && showCatalogueDetails) item {
                        Text("已有曲风 ${library.taggedCount} · 等待查询 ${library.statusCount(GenreLookupStatus.WAITING)}\n" +
                            "待重试 ${library.statusCount(GenreLookupStatus.RETRY)} · 暂无资料 ${library.statusCount(GenreLookupStatus.UNAVAILABLE)}",
                            color=Muted,fontSize=TypeSize.secondary,lineHeight=TypeSize.paragraphLine)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            OutlineButton(onClick=onRetryMissing,enabled=!library.retryScheduled && library.taggedCount<library.favorites.size) {
                                Text(if(library.retryScheduled)"重试已安排…" else "重试未取得曲风",fontSize=TypeSize.caption)
                            }
                            Spacer(Modifier.width(Space.x2))
                            PrimaryButton(onClick=onSupplementMissing,enabled=supplements && !library.retryScheduled && library.taggedCount<library.favorites.size) {Text("换来源补齐",fontSize=TypeSize.caption)}
                        }
                        Text(if(supplements)"QQ 无标签或读取失败时尝试补充来源；已有曲风保留。" else "在“我的”开启在线补全，可使用补充来源。",fontSize=TypeSize.caption,color=Muted,lineHeight=TypeSize.compactLine)
                        OutlineButton(onClick={onEnrich(null)},enabled=supplements && !library.enriching) {
                            Text(if(library.enriching)"补充中 ${library.enrichedProgress}/${library.enrichmentTotal}" else "补充细分曲风",fontSize=TypeSize.secondary)
                        }
                        Text("自动逐首补查单标签收藏，中断后下次继续。补充来源没有更多资料时保留原标签。",fontSize=TypeSize.caption,color=Muted,lineHeight=TypeSize.compactLine)
                        Text("多曲风 ${library.favorites.count {library.genres[it.mid]?.labels.orEmpty().size>1}} 首 · 有听感线索 ${library.favorites.count {library.genres[it.mid]?.descriptors?.isNotEmpty()==true}} 首",fontSize=TypeSize.caption,color=Accent)
                        val topGenres=library.favorites.flatMap {library.genres[it.mid]?.labels.orEmpty()}
                            .groupingBy {it}.eachCount().entries.sortedByDescending {it.value}.take(4)
                        if(topGenres.isNotEmpty()) Text(topGenres.joinToString(" · ") {"${genreName(it.key)} ${it.value} 首"},
                            modifier=Modifier.padding(top=Space.statusDot),fontSize=TypeSize.caption,color=Muted)
                    }
                    item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Space.wordmarkGap)) {listOf("全部","已识别曲风","待补齐","待重试","暂无资料").forEachIndexed {i,label ->
                        ResonanceChip(label, filter == i) { filter = i }
                    }}}
                    val browsing = if(library.openedPlaylist!=null) library.playlistTracks else library.favorites
                    val filtered=browsing.filter {song -> (search.isBlank() || song.name.contains(search,true) || song.artist.contains(search,true)) &&
                        when(filter) {
                            0 -> true
                            1 -> GenreLookupPolicy.status(library.genres[song.mid])==GenreLookupStatus.READY
                            2 -> GenreLookupPolicy.status(library.genres[song.mid])!=GenreLookupStatus.READY
                            3 -> GenreLookupPolicy.status(library.genres[song.mid])==GenreLookupStatus.RETRY
                            else -> GenreLookupPolicy.status(library.genres[song.mid])==GenreLookupStatus.UNAVAILABLE
                        }}
                    val playlistError = library.message.startsWith("歌单暂时未能读取") || library.message.startsWith("歌单内容暂时不可用")
                    if (library.syncing && library.openedPlaylist == null) item(key = "collection-loading") {
                        LoadingState(library.message)
                    }
                    if (playlistError) item {
                        val retry = if (library.message.startsWith("歌单内容")) requestedPlaylist?.let { playlist -> { onPlaylist(playlist) } }
                            else onPlaylists
                        ErrorState("歌单", library.message, "重试", retry)
                    }
                    if (filtered.isEmpty() && !library.syncing && !playlistError) item {
                        if (!connected) EmptyState("连接 QQ 音乐，自动带回你的收藏。", "连接 QQ 音乐", onLogin)
                        else EmptyState("暂无符合条件的歌曲。", if (search.isNotBlank() || filter != 0) "全部" else "同步收藏", {
                            if (search.isNotBlank() || filter != 0) { search = ""; filter = 0 } else onSync()
                        })
                    }
                    items(filtered, key = { it.mid }) { song ->
                        TrackRow(song, "", song.mid == player.mid, { onPlay(song) }, { detailSong = song },
                            subtitle = if (library.activeGenreMid == song.mid) "正在查询曲风…" else "", detailsButton = true) {
                            TrackGenres(library.genres[song.mid])
                        }
                    }
                    item { SectionHeader("我的歌单", onPlaylists, "读取歌单") }
                    items(library.playlists, key = { "playlist-${it.id}" }) { playlist ->
                        val firstMid = playlistSeeds[playlist.id]
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min = Space.touch)
                                .clickable(role = Role.Button) { requestedPlaylist = playlist; onPlaylist(playlist) }
                                .padding(vertical = Space.x2), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
                                SandPlate(firstMid ?: "playlist:${playlist.id}", Modifier.size(Space.listCover))
                                Text(playlist.name, fontSize = TypeSize.track, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                            }
                            HorizontalDivider(color = SoftLine)
                        }
                    }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("共振图谱", Modifier.weight(1f), fontSize = TypeSize.title, fontWeight = FontWeight.Black)
                            Text("正分叠加，负分反相抵消", color = Muted, fontSize = TypeSize.secondary)
                        }
                    }
                    item(key = "profile-graph") {
                        ResonanceGraph(profileRenderer, scores, companionMinutes.value, profileTimeline.newCompletions,
                            animateGraph, Modifier.fillMaxWidth().aspectRatio(350f / 280f))
                    }
                    item {
                        ProfileHours(companionMinutes.value, profileTimeline.week)
                        HorizontalDivider(color = SoftLine)
                    }
                    if (connected) item {
                        GenreScoreSummary(scores, ticker, motionEnvironment.systemEnabled,
                            { draftIntent = library.intent; scoreTab = true; showIntent = true }, { showScoreReset = true })
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                            SectionHeader("QQ 音乐", null, "")
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(Space.x2).background(Accent, CircleShape))
                                Text(if (connected) "账号已连接 · 收藏自动同步" else "连接你的账号，带回喜欢的音乐。",
                                    color = Muted, fontSize = TypeSize.secondary)
                            }
                            OutlineButton(if (connected) "管理账号 / 更新授权" else "连接 QQ 音乐", onLogin)
                        }
                        HorizontalDivider(color = SoftLine)
                    }
                    item { SectionHeader("听歌方式", null, "") }
                    item {PlaybackRangeChoices(player.playbackRange,onPlaybackRange)}
                    item {SettingSwitch("连续快跳，自动换方向","每满 4 次有效快跳，尝试不同曲风。",autoDirection){autoDirection=it;settings.edit().putBoolean("qq_auto_direction",it).apply()}}
                    item {Text("换方向后的等待",fontSize=TypeSize.action)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Space.x2)) {
                            DirectionCooldown.options.forEach { seconds ->
                                ResonanceChip(DirectionCooldown.label(seconds), cooldownSeconds == seconds, enabled = autoDirection) {
                                    cooldownSeconds = seconds
                                    settings.edit().putInt("qq_direction_cooldown_seconds", seconds).apply()
                                }
                            }
                        }
                        Text("默认不额外等待，再次连续快跳 4 首即可重新尝试。",color=Muted,fontSize=TypeSize.caption)
                        HorizontalDivider(Modifier.padding(top = Space.x2), color = SoftLine)
                    }
                    item {SettingSwitch("在线补全曲库资料","QQ 标签优先，缺失资料由补充来源自动查询。",supplements){supplements=it;settings.edit().putBoolean("catalogue_external_tags",it).apply()}}
                    item {SettingSwitch("推荐里加入惊喜一首","每批最多一首随机未知曲风；收藏里的歌仍算熟悉。",surpriseEnabled){surpriseEnabled=it;settings.edit().putBoolean("qq_surprise_enabled",it).apply();QqLibrary.refreshRecommendationPreferences()}}
                    item { TextAction("预览切歌提示", {
                        previewNotice = DirectionNotice(System.nanoTime(), SystemClock.elapsedRealtime(), emptySet(), "INFO", "提示预览 · 实际切歌后会显示目标歌名")
                    }, Accent) }
                    item { TextAction("最近换向记录", { showDirectionHistory = true }, Accent) }
                    item { SectionHeader("账号与资料", null, "") }
                    item { TextAction(if (showConnectionDetails) "收起连接与资料详情" else "连接状态、流量与隐私",
                        { showConnectionDetails = !showConnectionDetails }, Accent) }
                    if(showConnectionDetails) item {
                        Text(library.message,color=Muted,fontSize=TypeSize.secondary,lineHeight=TypeSize.readingLine)
                        if(player.mid.isNotBlank())Text(player.message,color=Muted,fontSize=TypeSize.secondary,modifier=Modifier.padding(top=Space.x2))
                        Text("标准音质 · 音频缓存上限 100 MB。收藏分析只读取资料。",fontSize=TypeSize.secondary,color=Muted,lineHeight=TypeSize.readingLine,modifier=Modifier.padding(top=Space.scoreTickHeight))
                        Text("曲风优先读取 QQ 音乐单曲资料。在线补全向 MusicBrainz 查询歌名、歌手、专辑与时长。听歌记录与登录凭据保存在本机。",
                            color=Muted,fontSize=TypeSize.secondary,lineHeight=TypeSize.readingLine,modifier=Modifier.padding(top=Space.x2))
                    }
                    item { TextAction("旧版监听与记录", onLegacy, Muted) }
                    if (connected) item { TextAction("退出 QQ 音乐账号", onLogout, Accent) }
                    item {Text("RESONANCE · 共鸣 ${BuildConfig.VERSION_NAME}",color=Muted,fontSize=TypeSize.caption,letterSpacing=TypeSize.wordmarkTracking,modifier=Modifier.padding(vertical=Space.sectionGap))}
                }
            }
            }
            DirectionChangeBanner(notice, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.scoreTickHeight))
            }
        }
        }
        AnimatedVisibility(expandedPlayer && current != null,
            enter = if (motionEnabled) fadeIn(tween(Motion.SharedArtwork, easing = Motion.responsive)) else EnterTransition.None,
            exit = if (motionEnabled) fadeOut(tween(Motion.SharedArtwork, easing = Motion.responsive)) else ExitTransition.None) {
            current?.let { song ->
                val plate = if (sharedArtwork) shared.playerArtwork(song.mid, true, this) else Modifier
                val cover = if (sharedArtwork && seedCover.image != null) shared.playerArtwork(song.mid, false, this) else Modifier
                NowPlaying(expandedPlayer, song, seedCover, player, clock, plate, cover, library.genres[song.mid]?.labels.orEmpty(),
                    GenreLookupPolicy.description(library.genres[song.mid]), notice,
                    onClose = { expandedPlayer = false }, onPlayPause = onToggle, onPrevious = onPrevious, onNext = onNext,
                    onSeek = onSeek, onRetry = { onPlay(song) },
                    onSimilar = { QqLibrary.similar(song.mid); expandedPlayer = false; tab = 0 },
                    onMore = { openFeedback(song, true) }, onLess = { openFeedback(song, false) },
                    onHistory = { expandedPlayer = false; showDirectionHistory = true },
                    favorite = { FavoriteControl(song, library, onFavorite, compact = true) },
                    queueControls = {
                        Text("播放模式 · 队列 ${player.queueSize} 首", color = Muted, fontSize = TypeSize.secondary)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                            PlaybackMode.entries.forEach { mode ->
                                ResonanceChip(mode.label, player.playbackMode == mode.name) { onPlaybackMode(mode.name) }
                            }
                        }
                        PlaybackRangeChoices(player.playbackRange, onPlaybackRange)
                    })
            }
        }
        detailSong?.let {song -> SheetScaffold(onDismissRequest={detailSong=null},
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Box {Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.x6).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(Space.x3)) {
                SectionHeader("歌曲详情", null, "")
                Text(song.name, style = resonanceTextStyle(TypeSize.song, FontWeight.ExtraBold, true))
                Text(song.artist,color=Muted,fontSize=TypeSize.body)
                if(song.album.isNotBlank())Text(song.album,color=Muted,fontSize=TypeSize.secondary)
                Text(formatPlaybackTime(song.durationMs),color=Muted,fontSize=TypeSize.secondary)
                FavoriteControl(song,library,onFavorite)
                HorizontalDivider(color = SoftLine)
                SectionHeader("全部曲风标签", null, "")
                Text(library.genres[song.mid]?.labels?.takeIf {it.isNotEmpty()}?.sorted()?.joinToString(" · ") {genreName(it)}
                    ?: GenreLookupPolicy.description(library.genres[song.mid]),color=Accent,fontSize=TypeSize.body,lineHeight=TypeSize.panelTitle)
                library.genres[song.mid]?.providers()?.forEach {(provider,labels)->
                    Text((if(provider=="qq-song-detail")"QQ 单曲资料：" else "严格匹配的补充曲库：")+labels.sorted().joinToString(" · "){genreName(it)},color=Muted,fontSize=TypeSize.caption,lineHeight=TypeSize.lead)
                }
                library.genres[song.mid]?.descriptors?.takeIf {it.isNotEmpty()}?.let {labels ->
                    Text("听感线索 · 曲库标注",fontSize=TypeSize.track)
                    Text(labels.sorted().joinToString(" · "){descriptorName(it)},color=Accent,fontSize=TypeSize.body)
                    Text("来自严格匹配录音的社区标注，至少 2 票；单独展示，尚不用于自动选歌，也不是声音模型识别结果。",color=Muted,fontSize=TypeSize.caption,lineHeight=TypeSize.lead)
                }
                Text(library.genres[song.mid]?.enrichmentNote?.ifBlank {"仅展示曲库实际提供的标签，缺少资料不会猜测情绪。"}?:"缺少曲风不影响播放，惊喜抽取不会为歌曲添加标签。",color=Muted,fontSize=TypeSize.caption,lineHeight=TypeSize.lead)
                OutlineButton("补充这首的标签", { onEnrich(song.mid) }, enabled = supplements && connected && !library.enriching)
                Row(horizontalArrangement=Arrangement.spacedBy(Space.scoreTickHeight)) {
                    TextAction("多来这种", { openFeedback(song, true) }, Accent)
                    TextAction("暂时少放", { openFeedback(song, false) }, Muted)
                }
                Spacer(Modifier.height(Space.x5))
            }
            DirectionChangeBanner(notice,Modifier.align(Alignment.BottomCenter).padding(bottom=Space.x6))}
        }}
        if (showIntent) SheetScaffold(onDismissRequest = { showIntent = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.x6).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.x3)) {
                SectionHeader("这次想听什么", null, "")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                    PlaybackChip("选歌条件", !scoreTab) { scoreTab = false }
                    PlaybackChip("标签分数", scoreTab) { scoreTab = true }
                }
                val animate = LocalResonanceMotion.current
                val slide = with(LocalDensity.current) { if (animate) Motion.SecondaryOffsetDp.dp.roundToPx() else 0 }
                AnimatedContent(scoreTab, label = "score sections", transitionSpec = {
                    val spec = if (animate) tween<Float>(Motion.SecondaryPage, easing = Motion.responsive) else snap()
                    (fadeIn(spec) + slideInVertically(if (animate) tween(Motion.SecondaryPage, easing = Motion.responsive) else snap()) {
                        if (targetState) slide else -slide
                    }) togetherWith (fadeOut(spec) + slideOutVertically(if (animate) tween(Motion.SecondaryPage, easing = Motion.responsive) else snap()) {
                        if (targetState) -slide else slide
                    })
                }) { scoresPage ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.x3)) {
                        if (scoresPage) TagScores(library) { showScoreReset = true }
                        else IntentEditor(library.genres, draftIntent, { draftIntent = it },
                            { onIntent(draftIntent); showIntent = false },
                            { onIntent(ListeningIntent()); showIntent = false })
                    }
                }
            }
        }
        if (showScoreReset) ClearScoresDialog({ showScoreReset = false }, onClearScores)
        feedbackSong?.let {song->SheetScaffold(onDismissRequest={feedbackSong=null},
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.x6).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(Space.x3)) {
                SectionHeader(if (feedbackMore) "多来这种" else "暂时少放", null, "")
                Text(song.name,color=Muted,fontSize=TypeSize.action)
                Text("选择要调整的曲风。每次主动反馈 ${if(feedbackMore)"+2" else "−2"}，累计上限 ±6；影响相似推荐、换向和随机权重。",fontSize=TypeSize.secondary,color=Muted,lineHeight=TypeSize.readingLine)
                val labels=library.genres[song.mid]?.labels.orEmpty()
                if (labels.isEmpty()) EmptyState("这首歌还没有可靠曲风，先补充资料后再调整。")
                labels.sorted().forEach {tag->ResonanceChip(genreName(tag), tag in feedbackLabels) { feedbackLabels = if (tag in feedbackLabels) feedbackLabels - tag else feedbackLabels + tag }}
                if(labels.size==1)Text("目前只有一个标签，反馈会影响所有带这个标签的歌。",fontSize=TypeSize.caption,color=Muted,lineHeight=TypeSize.lead)
                PrimaryButton("确认调整", { onSongFeedback(song.mid, feedbackLabels, feedbackMore); feedbackSong = null },
                    Modifier.fillMaxWidth(), enabled = feedbackLabels.isNotEmpty())
            }
        }}
        if(showDirectionHistory)SheetScaffold(onDismissRequest={showDirectionHistory=false},
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(),contentPadding=PaddingValues(Space.x6),verticalArrangement=Arrangement.spacedBy(Space.x4)) {
                item {SectionHeader("最近换向记录", null, "")
                    Text("只在目标歌曲实际播放且进度推进后确认；保存最近 20 次。",color=Muted,fontSize=TypeSize.secondary,lineHeight=TypeSize.readingLine)}
                if (library.directionHistory.isEmpty()) item { EmptyState("还没有自动换向记录。连续 4 次有效快跳后会记录尝试结果。") }
                items(library.directionHistory.asReversed(),key={it.id}) {row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                        Text(directionStateName(row.state)+" · "+directionLabel(row.kind),color=Accent,fontSize=TypeSize.secondary)
                        Text(row.targetName.ifBlank {row.targetMid},fontSize=TypeSize.compactLine)
                        Text(java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date(row.time)),fontSize=TypeSize.caption,color=Muted)
                        Text(row.detail.ifBlank {"等待目标歌曲播放确认"},fontSize=TypeSize.secondary,lineHeight=TypeSize.readingLine)
                        Text(row.reason,fontSize=TypeSize.caption,color=Muted,lineHeight=TypeSize.lead)
                        Text("当时标签分数："+row.scores.entries.sortedByDescending {it.value}.joinToString(" · "){genreName(it.key)+" "+scoreText(it.value)}.ifBlank {"暂无可用标签"},fontSize=TypeSize.caption,color=Muted,lineHeight=TypeSize.lead)
                        HorizontalDivider(color = SoftLine)
                    }
                }
            }
        }
    } } }
}

private fun androidx.compose.foundation.lazy.LazyListState.partVisible(key: String): Boolean {
    val layout = layoutInfo
    return layout.visibleItemsInfo.any {
        it.key == key && it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset
    }
}

@Composable
fun CompleteTagRow(labels:Set<String>,modifier:Modifier=Modifier) {
    val gap=Space.opticalInset
    Layout(content={labels.sorted().forEach {TinyTag(genreName(it),true)}},modifier=modifier.fillMaxWidth()) {measurables,constraints ->
        val measured=measurables.map {it.measure(Constraints())}
        val spacing=gap.roundToPx()
        val available=if(constraints.hasBoundedWidth)constraints.maxWidth else measured.sumOf {it.width}+spacing*(measured.size-1).coerceAtLeast(0)
        val visible=mutableListOf<androidx.compose.ui.layout.Placeable>()
        var width=0
        for(item in measured) {
            val needed=item.width+if(visible.isEmpty())0 else spacing
            if(width+needed>available)continue
            visible.add(item);width+=needed
        }
        layout(constraints.constrainWidth(available),constraints.constrainHeight(visible.maxOfOrNull {it.height}?:Space.sectionGap.roundToPx())) {
            var x=0
            visible.forEach {it.placeRelative(x,0);x+=it.width+spacing}
        }
    }
}

@Composable
fun DirectionChangeBanner(notice:DirectionNotice?,modifier:Modifier=Modifier) {
    var visible by remember {mutableStateOf(false)}
    var shown by remember {mutableStateOf<DirectionNotice?>(null)}
    LaunchedEffect(notice?.id) {
        val remaining=notice?.let {Motion.NoticeLifetime-(SystemClock.elapsedRealtime()-it.createdAt)}?:0
        if(notice!=null && remaining>0) {
            shown=notice;visible=true;delay(remaining);visible=false
        } else visible=false
    }
    val fade = LocalResonanceMotion.current
    AnimatedVisibility(visible, modifier.padding(horizontal = Space.x6).widthIn(max = Space.x8 * 10 + Space.x5),
        enter = fadeIn(if (fade) tween(Motion.NoticeEnter, easing = Motion.easeInOut) else snap()),
        exit = fadeOut(if (fade) tween(Motion.NoticeExit, easing = Motion.easeInOut) else snap())) {
        Box(Modifier.clip(Corners.notice).background(Accent.copy(alpha=.96f)).padding(horizontal=Space.sectionGap,vertical=Space.noticeInset)
            .semantics {liveRegion=LiveRegionMode.Polite}) {
            Text(shown?.message?.takeIf {it.isNotBlank()} ?: (if(shown?.kind=="BATCH")"已换一批" else "已换个方向")+shown?.genres?.takeIf {it.isNotEmpty()}?.sorted()?.joinToString(" · ",prefix=" · ") {genreName(it)}.orEmpty(),
                color=Ink,fontSize=TypeSize.body,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun FavoriteControl(song:QqCatalogueTrack,library:LibraryState,onFavorite:(String)->Unit,compact:Boolean=false) {
    val liked=library.favoriteStates[song.mid]?:library.favorites.any {it.mid==song.mid}
    val busy=song.mid in library.favoriteBusy
    val enabled=library.accountScope.isNotBlank() && !busy
    val label=when {
        busy -> "正在确认 QQ 收藏…"
        song.mid in library.favoriteUncertain -> "刷新收藏状态"
        liked -> "已喜欢 · 取消收藏"
        else -> "收藏到 QQ 我喜欢"
    }
    val interaction = remember { MutableInteractionSource() }
    if(compact) IconButton(onClick={onFavorite(song.mid)},enabled=enabled,interactionSource=interaction,
        modifier=Modifier.semantics {contentDescription=label}) {
        Box(Modifier.size(Space.favoriteDisk).resonancePress(interaction).clip(CircleShape).background(if(liked)Selected else Raised),contentAlignment=Alignment.Center) {
            if(busy)SandPlate(song.mid, Modifier.size(Space.x6), density = .18f)
            else Glyph(if(song.mid in library.favoriteUncertain)ResonanceGlyph.REFRESH else ResonanceGlyph.HEART,
                Modifier.size(Space.x5),if(liked && enabled)Accent else Muted)
        }
    } else OutlineButton(onClick={onFavorite(song.mid)},enabled=enabled) {
        Glyph(ResonanceGlyph.HEART,Modifier.size(Space.heartGlyph),if(liked)Accent else Muted)
        Spacer(Modifier.width(Space.x2))
        Text(label,fontSize=TypeSize.secondary)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlaybackRangeChoices(selected:String,onSelect:(String)->Unit) {
    Column {
        Text("随机与循环范围",fontSize=TypeSize.secondary,color=Muted)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.statusDot)) {
            PlaybackRange.entries.forEach {range -> PlaybackChip(range.label, selected == range.name) {onSelect(range.name)}}
        }
    }
}
private fun scoreText(value:Double)=String.format(java.util.Locale.ROOT,"%+.1f",value)
private fun directionStateName(state:String)=when(state){"PLAYING"->"已确认播放";"FAILED"->"播放失败";"CANCELED"->"已取消";"UNCONFIRMED"->"未确认播放";else->"正在切换"}
@Composable
private fun GenreScoreSummary(scores: List<GenreScore>, ticker: FrameTicker, systemMotion: Boolean,
    onAll: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        SectionHeader("当前标签分数", onAll, "查看全部 ${scores.size} 项", actionColor = Accent)
        if (scores.isEmpty()) Text("暂无曲风标签，取得资料后会在这里显示。", fontSize = TypeSize.secondary, color = Muted)
        else LazyRow(
            modifier = Modifier.layout { measurable, constraints ->
                val inset = Space.x5.roundToPx()
                val row = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + inset))
                layout(constraints.maxWidth, row.height) { row.placeRelative(0, 0) }
            },
            contentPadding = PaddingValues(end = Space.x5),
            horizontalArrangement = Arrangement.spacedBy(Space.tileGap)
        ) {
            val maxAbs = (scores.firstOrNull()?.let { abs(it.combined) } ?: 0.0).coerceAtLeast(6.0)
            items(scores, key = { it.label }) { score ->
                ScoreCard(score, maxAbs, Space.profileScoreWidth - Space.x6, ticker, systemMotion,
                    Modifier.size(Space.profileScoreWidth, Space.heroCover), compact = true)
            }
        }
        TextAction("清除当前所有分数", onClear, Muted)
    }
}

@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(vertical = Space.x2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = TypeSize.body, fontWeight = FontWeight.Medium)
                Text(detail, fontSize = TypeSize.caption, color = Muted, modifier = Modifier.padding(top = Space.x1))
            }
            Switch(checked, onChange)
        }
        HorizontalDivider(color = SoftLine)
    }
}

@Composable
private fun rememberDiscoveryEntries(
    library: LibraryState, search: String, unheardOnly: Boolean, genre: String?, active: Boolean
): List<DiscoveryEntry>? {
    val input = remember(library.favorites, library.discoveries, library.genres, library.listening,
        library.scoreSignals, library.manualScores, library.discoverySources, library.intent) { library }
    return key(library.accountScope, library.intent, search, unheardOnly, genre) {
        var entries by remember { mutableStateOf<List<DiscoveryEntry>?>(null) }
        if (active) LaunchedEffect(input) {
            entries = withContext(Dispatchers.Default) { DiscoveryBrowser.entries(input, search, unheardOnly, genre) }
        }
        entries
    }
}
