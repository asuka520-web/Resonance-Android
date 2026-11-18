package com.resonance.app

import android.graphics.BitmapFactory
import android.util.LruCache
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
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

internal const val ResonanceBackgroundArgb = 0xFF111119
private val Ink = Color(ResonanceBackgroundArgb)
private val Panel = Color(0xFF1B1B26)
private val Raised = Color(0xFF272638)
private val Selected = Color(0xFF342A49)
private val Muted = Color(0xFFB0ADBF)
private val Accent = Color(0xFFD8C5FF)
private val Cream = Color(0xFFF5F1FA)
private val SoftLine = Color(0xFF363446)

@Composable
fun ResonanceTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink, background = Ink, onBackground = Cream,
        surface = Panel, onSurface = Cream, secondary = Accent, outline = SoftLine,
        primaryContainer = Selected, onPrimaryContainer = Accent,
        secondaryContainer = Selected, onSecondaryContainer = Accent,
        surfaceVariant = Raised, onSurfaceVariant = Muted), content = content)

enum class ResonanceGlyph { PLAY, PAUSE, NEXT, PREVIOUS, HOME, LIBRARY, PERSON, SEARCH, HEART, BACK, CLOSE, REFRESH, WAVE, DETAILS }

@Composable
fun Glyph(type: ResonanceGlyph, modifier: Modifier = Modifier.size(24.dp), color: Color = Cream) {
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

private val resonanceWordmark = buildAnnotatedString {
    append("Resonance")
    append(" ")
    withStyle(SpanStyle(color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)) {
        append("by Ardolie")
    }
}

@Composable
private fun ListeningAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, outlined: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val animated = modifier.pressMotion(interaction)
    if(outlined) {
        OutlinedButton(onClick, animated, interactionSource = interaction, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(text, fontSize = 13.sp)
        }
    } else {
        Button(onClick, animated, interactionSource = interaction, colors = ButtonDefaults.buttonColors(containerColor = Raised, contentColor = Accent)) {
            Text(text)
        }
    }
}

@Composable
private fun PlaybackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    FilterChip(selected, onClick, modifier = Modifier.pressMotion(interaction),
        label = {Text(label, fontSize = 11.sp)}, interactionSource = interaction)
}

private object CoverMemory {
    val images = object : LruCache<String, ImageBitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) =
            (value.width * value.height * 4 + 1023) / 1024
    }
    private val locks = Array(16) { Mutex() }
    private val downloads = Semaphore(3)

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val lock = locks[(url.hashCode() and Int.MAX_VALUE) % locks.size]
        lock.withLock {
            images.get(url) ?: downloads.withPermit {
                download(url)?.also { images.put(url, it) }
            }
        }
    }

    private fun download(url: String): ImageBitmap? = try {
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
                BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
@Composable
internal fun rememberCoverArtwork(song: QqCatalogueTrack?): ImageBitmap? {
    val url = song?.let(CatalogueStore::artwork)
    var bitmap by remember(url) { mutableStateOf(url?.let { CoverMemory.images.get(it) }) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = CoverMemory.load(url)
    }
    return bitmap
}

@Composable
fun AlbumCover(song: QqCatalogueTrack?, modifier: Modifier = Modifier) {
    CoverArt(rememberCoverArtwork(song), modifier)
}

@Composable
private fun CoverArt(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF504263),Color(0xFF2A2940)))),contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val r=size.minDimension*.4f
            drawCircle(Ink,r)
            for(i in 1..4)drawCircle(Color.White.copy(alpha=.075f),r*(.3f+i*.13f),style=Stroke(1.2f))
            drawCircle(Accent.copy(alpha=.85f),r*.22f)
            drawCircle(Ink,r*.045f)
        }
    }
}
internal fun genreName(value: String) = when(value) {
    "pop"->"流行";"rock"->"摇滚";"folk"->"民谣";"jazz"->"爵士";"electronic"->"电子";"hip hop"->"说唱";
    "r&b"->"R&B";"classical"->"古典";"soul"->"灵魂乐";"funk"->"放克";"country"->"乡村";"metal"->"金属";else->value
}

private fun descriptorName(value:String)=when(value){"mood:bright"->"明快";"mood:sad"->"低落";"mood:calm"->"舒缓";"mood:energetic"->"活力";
    "instrument:piano"->"钢琴";"instrument:guitar"->"吉他";"instrument:violin"->"小提琴";"instrument:saxophone"->"萨克斯";"voice:instrumental"->"器乐";else->value}
@Composable private fun TinyTag(text:String, active:Boolean=false) {
    Text(text, color=if(active)Accent else Muted, fontSize=10.sp,
        modifier=Modifier.clip(RoundedCornerShape(6.dp)).background(if(active)Selected else Raised).padding(horizontal=7.dp,vertical=4.dp))
}
@Composable private fun SectionTitle(title:String, detail:String="", action:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold)
        if(detail.isNotBlank()) Text(detail,color=Muted,fontSize=12.sp,modifier=if(action==null)Modifier else Modifier.clickable(onClick=action).padding(6.dp))
    }
}
@Composable private fun CircleAction(type:ResonanceGlyph,label:String,onClick:()->Unit,accent:Boolean=false,size:Int=44) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size.dp).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val surface = Modifier.fillMaxSize().pressMotion(interaction, .90f)
        Box(
            if(accent) surface.shadow(if(size > 48) 14.dp else 7.dp, CircleShape,
                ambientColor = Accent.copy(alpha = .40f), spotColor = Accent.copy(alpha = .34f))
                .clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFF1E6FF), Accent, Color(0xFFB29CE0))))
            else surface,
            contentAlignment = Alignment.Center
        ) {
            if(accent) {
                Crossfade(type, animationSpec = tween(135), label = "play state") { glyph ->
                    Glyph(glyph, Modifier.size((size * .52f).dp), Ink)
                }
            } else Glyph(type, Modifier.size((size * .52f).dp), Cream)
        }
    }
}
@Composable private fun TrackRow(song:QqCatalogueTrack, labels:Set<String>, onClick:()->Unit, current:Boolean=false, note:String="", genre:CatalogueGenre?=null,onDetails:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        AlbumCover(song,Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal=13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(song.name,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(current)Accent else Cream,fontSize=15.sp,fontWeight=FontWeight.Medium)
            Text(note.ifBlank {song.artist},maxLines=1,overflow=TextOverflow.Ellipsis,color=Muted,fontSize=12.sp)
            if(labels.isEmpty()) Text(GenreLookupPolicy.description(genre),color=Muted,fontSize=10.sp)
            else CompleteTagRow(labels)
            if(genre?.descriptors?.isNotEmpty()==true)Text("曲库线索 · "+genre.descriptors.sorted().joinToString(" · "){descriptorName(it)},color=Muted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        if(onDetails!=null)CircleAction(ResonanceGlyph.DETAILS,"${song.name}的歌曲详情",onDetails,size=32)
        Glyph(if(current)ResonanceGlyph.WAVE else ResonanceGlyph.PLAY,Modifier.padding(start=12.dp,end=4.dp).size(21.dp),if(current)Accent else Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ResonanceScreen(library:LibraryState, player:PlayerStatus, onLogin:()->Unit, onSync:()->Unit,
    onPlay:(QqCatalogueTrack)->Unit, onToggle:()->Unit, onNext:()->Unit, onPrevious:()->Unit, onSeek:(Float)->Unit,
    onLogout:()->Unit, onLegacy:()->Unit, onPlaylists:()->Unit, onPlaylist:(QqCataloguePlaylist)->Unit,
    onRetryMissing:()->Unit = {QqLibrary.retryMissing()}, onRefreshDiscovery:()->Unit = {QqLibrary.warmDiscovery(true)},
    onPlayRecommendation:(String?)->Unit = {}, onPlayDiscovery:(List<String>,String?)->Unit = {_,_->},
    onSupplementMissing:()->Unit = {QqLibrary.retryMissing(true)},onSurprise:()->Unit={},
    onPlaybackMode:(String)->Unit={},onIntent:(ListeningIntent)->Unit={QqLibrary.setIntent(it)},
    onFavorite:(String)->Unit={},onRefreshFavorite:(String)->Unit={},onEnrich:(String?)->Unit={},onPlaybackRange:(String)->Unit={},onClearScores:()->Unit={},
    onSongFeedback:(String,Set<String>,Boolean)->Unit={_,_,_->}, liveClock:State<PlaybackClock>?=null) {
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
    val listState=rememberLazyListState()
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
    val seedArtwork=rememberCoverArtwork(seed)
    val connected=library.accountScope.isNotBlank()
    val notice=listOfNotNull(player.directionNotice,library.feedback,previewNotice).maxByOrNull {it.createdAt}
    LaunchedEffect(expandedPlayer,current?.mid,detailSong?.mid) {
        if(connected)(detailSong?.mid?:current?.mid?.takeIf {expandedPlayer})?.let(onRefreshFavorite)
    }
    val browserEntries=if(tab==1) rememberDiscoveryEntries(library,discoverySearch,unheardOnly,discoveryGenre) else null
    ResonanceTheme { HomeFonts {
        Box(Modifier.fillMaxSize()) {
        Scaffold(containerColor=if(tab==0) HomePalette.ink else Ink,bottomBar={
            HomeBottomBar(tab, current, player, clock, seedArtwork, {tab=it}, {expandedPlayer=true},
                onPlaybackMode, onToggle, onNext)
        }) {padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(tab==0) item {
                    HomeHeader(connected) {if(connected) tab=3 else onLogin()}
                } else item {
                    Row(Modifier.fillMaxWidth().padding(bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Glyph(ResonanceGlyph.WAVE,Modifier.size(23.dp),Accent)
                        Text(resonanceWordmark, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp,
                            modifier = Modifier.weight(1f).padding(start = 5.dp, end = 8.dp), maxLines = 2)
                        Row(Modifier.clip(CircleShape).background(Panel).clickable {if(!connected)onLogin() else tab=3}.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(if(connected)Accent else Muted))
                            Text(if(connected)"QQ 音乐已连接" else "连接 QQ 音乐",fontSize=10.sp,color=Muted,modifier=Modifier.padding(start=6.dp))
                        }
                    }
                }
                if(tab==0) {
                    val refreshSimilar: () -> Unit = { seed?.let { QqLibrary.similar(it.mid) } }
                    item {
                        HomeHero(connected, seed, seedArtwork, onLogin, refreshSimilar, onSurprise) {
                            draftIntent=library.intent;scoreTab=false;showIntent=true
                        }
                    }
                    if(library.syncing || library.analyzing) item {
                        HomeCatalogueStatus(library.syncing, library.message, library.taggedCount)
                    }
                    if(connected && library.intent.active) item {
                        Text("想听：${library.intent.wanted.sorted().joinToString {genreName(it)}.ifBlank {"不限"}}\n避开：${library.intent.excluded.sorted().joinToString {genreName(it)}.ifBlank {"不限"}}",
                            color=HomePalette.muted,fontSize=11.sp,lineHeight=19.sp)
                    }
                    if(connected) item {
                        HomeScoreCards(library,{draftIntent=library.intent;scoreTab=true;showIntent=true},{showScoreReset=true})
                    }
                    val recommendations=library.recommendation
                    if(recommendations!=null) {
                        val refreshMid = seed?.mid ?: recommendations.picks.firstOrNull()?.track?.mediaId?.removePrefix("qq:")
                        item {
                            HomeRecommendationHeader("为你推荐", refreshMid?.let { mid -> { QqLibrary.similar(mid); Unit } }, "换一批",
                                if(recommendations.picks.isNotEmpty()) ({onPlayRecommendation(null)}) else null)
                        }
                        item {
                            HomeRecommendationReason(recommendations, showRecommendationReason) {
                                showRecommendationReason = !showRecommendationReason
                            }
                        }
                        items(recommendations.picks,key={it.track.mediaId}) {pick ->
                            tracksByMid[pick.track.mediaId.removePrefix("qq:")]?.let {song ->
                                HomeTrackRow(song, pick.reason, song.mid==player.mid,
                                    {onPlayRecommendation(song.mid)}, {detailSong=song}, unfamiliar = pick.unfamiliar)
                                if(showRecommendationReason && pick.reason.isNotBlank()) {
                                    Text(pick.reason,color=HomePalette.signal,fontSize=12.sp)
                                }
                            }
                        }
                    } else {
                        item { HomeRecommendationHeader("收藏精选", {tab=2}, "查看全部") }
                        items(library.favorites.take(6),key={it.mid}) {song ->
                            HomeTrackRow(song, "", song.mid==player.mid, {onPlay(song)}, {detailSong=song})
                        }
                    }
                    if(library.favorites.isEmpty()) item {Text(if(connected)"收藏正在路上，稍后就会出现在这里。" else "登录后自动同步收藏，无需逐首填写曲风。",color=HomePalette.muted,fontSize=13.sp,modifier=Modifier.padding(vertical=16.dp))}
                    if(connected)item {
                        HomeRecommendationHeader("收藏外候选 · ${library.outsideCount} 首", {tab=1}, "去发现")
                    }
                } else if(tab==1) {
                    item {SectionTitle("发现新歌",if(library.discovering)"发现中…" else "发现更多",if(library.discovering)null else onRefreshDiscovery)}
                    item {OutlinedTextField(discoverySearch,{discoverySearch=it},modifier=Modifier.fillMaxWidth(),singleLine=true,
                        placeholder={Text("搜索发现里的歌曲、歌手",fontSize=12.sp)},leadingIcon={Glyph(ResonanceGlyph.SEARCH,Modifier.size(19.dp),Muted)},shape=RoundedCornerShape(15.dp))}
                    item {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            FilterChip(selected=unheardOnly,onClick={unheardOnly=!unheardOnly},label={Text("本机未听",fontSize=12.sp)})
                            TextButton(onClick={draftIntent=library.intent;scoreTab=false;showIntent=true}){
                                Text(if(library.intent.active)"调整已选条件" else "选歌条件",fontSize=12.sp)
                            }
                        }
                    }
                    item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=discoveryGenre==null,onClick={discoveryGenre=null},label={Text("全部曲风",fontSize=11.sp)})
                        library.discoveries.flatMap {library.genres[it.mid]?.labels.orEmpty()}.distinct().sorted().forEach {label ->
                            FilterChip(selected=discoveryGenre==label,onClick={discoveryGenre=label},label={Text(genreName(label),fontSize=11.sp)})
                        }
                    }}
                    val entries=browserEntries.orEmpty()
                    item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(if(browserEntries==null && connected) "正在整理候选…" else "${entries.size} 首可浏览",
                            fontSize=12.sp,color=Muted,modifier=Modifier.weight(1f))
                        if(entries.isNotEmpty()) Button(onClick={onPlayDiscovery(entries.take(100).map {it.song.mid},null)}) {
                            Text(if(entries.size>100)"播放前 100 首" else "播放这批",fontSize=12.sp)
                        }
                    }}
                    if(entries.isEmpty() && (browserEntries!=null || !connected))item {Text(if(!connected)"连接 QQ 音乐后自动发现新歌。" else if(library.discovering)"正在寻找候选，稍后就会出现。" else
                        "暂无符合条件的新歌，可清除筛选或点“发现更多”。",color=Muted,fontSize=13.sp,modifier=Modifier.padding(vertical=18.dp))}
                    items(entries,key={it.song.mid}) {entry ->
                        TrackRow(entry.song,library.genres[entry.song.mid]?.labels.orEmpty(),{
                            val index=entries.indexOfFirst {it.song.mid==entry.song.mid}
                            val window=entries.drop((index-10).coerceAtLeast(0)).take(100)
                            onPlayDiscovery(window.map {it.song.mid},entry.song.mid)
                        },entry.song.mid==player.mid,note=entry.song.artist + if(entry.locallyHeard)" · 本机有记录" else "",genre=library.genres[entry.song.mid],onDetails={detailSong=entry.song})
                        Text(entry.reason,color=Muted,fontSize=10.sp,lineHeight=17.sp,modifier=Modifier.padding(start=65.dp,bottom=8.dp))
                    }
                } else if(tab==2) {
                    item {SectionTitle(library.openedPlaylist?.name ?: "我的收藏",
                        if(library.openedPlaylist!=null)"返回收藏" else if(library.syncing)"同步中…" else "同步收藏",
                        {if(library.openedPlaylist!=null) QqLibrary.closePlaylist() else onSync()})}
                    item {OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("搜索歌曲、歌手",fontSize=13.sp)},
                        leadingIcon={Glyph(ResonanceGlyph.SEARCH,Modifier.size(19.dp),Muted)},shape=RoundedCornerShape(15.dp))}
                    if(library.openedPlaylist==null && connected) item {
                        val coverage=if(library.favorites.isEmpty())0 else library.taggedCount*100/library.favorites.size
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel)
                                .clickable(role=Role.Button) {showCatalogueDetails=!showCatalogueDetails}
                                .semantics {stateDescription=if(showCatalogueDetails)"已展开" else "已收起"}
                                .padding(horizontal=14.dp,vertical=12.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text("曲风资料",fontSize=14.sp,fontWeight=FontWeight.Medium)
                                Text(if(library.enriching)"补充中 ${library.enrichedProgress}/${library.enrichmentTotal}"
                                    else "${library.taggedCount} / ${library.favorites.size} 首 · $coverage%",color=Muted,fontSize=11.sp)
                            }
                            Text(if(showCatalogueDetails)"收起" else "管理",color=Accent,fontSize=12.sp)
                        }
                    }
                    if(library.openedPlaylist==null && connected && showCatalogueDetails) item {
                        Text("已有曲风 ${library.taggedCount} · 等待查询 ${library.statusCount(GenreLookupStatus.WAITING)}\n" +
                            "待重试 ${library.statusCount(GenreLookupStatus.RETRY)} · 暂无资料 ${library.statusCount(GenreLookupStatus.UNAVAILABLE)}",
                            color=Muted,fontSize=12.sp,lineHeight=21.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            OutlinedButton(onClick=onRetryMissing,enabled=!library.retryScheduled && library.taggedCount<library.favorites.size) {
                                Text(if(library.retryScheduled)"重试已安排…" else "重试未取得曲风",fontSize=11.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick=onSupplementMissing,enabled=supplements && !library.retryScheduled && library.taggedCount<library.favorites.size) {Text("换来源补齐",fontSize=11.sp)}
                        }
                        Text(if(supplements)"QQ 无标签或读取失败时尝试补充来源；已有曲风保留。" else "在“我的”开启在线补全，可使用补充来源。",fontSize=10.sp,color=Muted,lineHeight=17.sp)
                        OutlinedButton(onClick={onEnrich(null)},enabled=supplements && !library.enriching) {
                            Text(if(library.enriching)"补充中 ${library.enrichedProgress}/${library.enrichmentTotal}" else "补充细分曲风",fontSize=12.sp)
                        }
                        Text("自动逐首补查单标签收藏，中断后下次继续。补充来源没有更多资料时保留原标签。",fontSize=10.sp,color=Muted,lineHeight=17.sp)
                        Text("多曲风 ${library.favorites.count {library.genres[it.mid]?.labels.orEmpty().size>1}} 首 · 有听感线索 ${library.favorites.count {library.genres[it.mid]?.descriptors?.isNotEmpty()==true}} 首",fontSize=11.sp,color=Accent)
                        val topGenres=library.favorites.flatMap {library.genres[it.mid]?.labels.orEmpty()}
                            .groupingBy {it}.eachCount().entries.sortedByDescending {it.value}.take(4)
                        if(topGenres.isNotEmpty()) Text(topGenres.joinToString(" · ") {"${genreName(it.key)} ${it.value} 首"},
                            modifier=Modifier.padding(top=6.dp),fontSize=11.sp,color=Muted)
                    }
                    item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(9.dp)) {listOf("全部","已识别曲风","待补齐","待重试","暂无资料").forEachIndexed {i,label ->
                        FilterChip(selected=filter==i,onClick={filter=i},label={Text(label,fontSize=11.sp)})
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
                    if(filtered.isEmpty()) item {Text(if(!connected)"连接 QQ 音乐，自动带回你的收藏。" else "暂无符合条件的歌曲。",color=Muted,modifier=Modifier.padding(vertical=24.dp))}
                    items(filtered,key={it.mid}) {song ->TrackRow(song,library.genres[song.mid]?.labels.orEmpty(),{onPlay(song)},song.mid==player.mid,
                        note=if(library.activeGenreMid==song.mid)"正在查询曲风…" else "",genre=library.genres[song.mid],onDetails={detailSong=song})}
                    item {SectionTitle("我的歌单","读取歌单",onPlaylists)}
                    items(library.playlists,key={it.id}) {list ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable {onPlaylist(list)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                            Glyph(ResonanceGlyph.LIBRARY,Modifier.size(24.dp),Accent);Text(list.name,fontSize=14.sp,modifier=Modifier.padding(start=14.dp))
                        }
                    }
                } else {
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Selected,Panel)))
                            .padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("陪伴时长",fontSize=13.sp,color=Accent,fontWeight=FontWeight.Medium)
                            ListeningMinutes(library.listeningTimeMs / 60000, clock)
                            Text("累计听歌时间",fontSize=12.sp,color=Muted)
                        }
                    }
                    if(connected)item {GenreScoreSummary(library,{draftIntent=library.intent;scoreTab=true;showIntent=true},{showScoreReset=true})}
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("QQ 音乐",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(connected)"账号已连接 · 收藏自动同步" else "连接你的账号，带回喜欢的音乐。",color=Muted,fontSize=12.sp)
                            Button(onClick=onLogin) {Text(if(connected)"管理账号 / 更新授权" else "连接 QQ 音乐")}
                        }
                    }
                    item {SectionTitle("听歌方式")}
                    item {PlaybackRangeChoices(player.playbackRange,onPlaybackRange)}
                    item {SettingSwitch("连续快跳，自动换方向","每满 4 次有效快跳，尝试不同曲风。",autoDirection){autoDirection=it;settings.edit().putBoolean("qq_auto_direction",it).apply()}}
                    item {Text("换方向后的等待",fontSize=13.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            DirectionCooldown.options.forEach {seconds -> FilterChip(selected=cooldownSeconds==seconds,onClick={cooldownSeconds=seconds;settings.edit().putInt("qq_direction_cooldown_seconds",seconds).apply()},
                                enabled=autoDirection,label={Text(DirectionCooldown.label(seconds),fontSize=11.sp)})}
                        }
                        Text("默认不额外等待，再次连续快跳 4 首即可重新尝试。",color=Muted,fontSize=10.sp)
                    }
                    item {SettingSwitch("在线补全曲库资料","QQ 标签优先，缺失资料由补充来源自动查询。",supplements){supplements=it;settings.edit().putBoolean("catalogue_external_tags",it).apply()}}
                    item {SettingSwitch("推荐里加入惊喜一首","每批最多一首随机未知曲风；收藏里的歌仍算熟悉。",surpriseEnabled){surpriseEnabled=it;settings.edit().putBoolean("qq_surprise_enabled",it).apply();QqLibrary.refreshRecommendationPreferences()}}
                    item {TextButton(onClick={previewNotice=DirectionNotice(System.nanoTime(),SystemClock.elapsedRealtime(),emptySet(),"INFO","提示预览 · 实际切歌后会显示目标歌名")}){Text("预览切歌提示")}}
                    item {TextButton(onClick={showDirectionHistory=true}){Text("最近换向记录")}}
                    item {SectionTitle("账号与资料")}
                    item {TextButton(onClick={showConnectionDetails=!showConnectionDetails}) {
                        Text(if(showConnectionDetails)"收起连接与资料详情" else "连接状态、流量与隐私",fontSize=13.sp)
                    }}
                    if(showConnectionDetails) item {
                        Text(library.message,color=Muted,fontSize=12.sp,lineHeight=20.sp)
                        if(player.mid.isNotBlank())Text(player.message,color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))
                        Text("标准音质 · 音频缓存上限 100 MB。收藏分析只读取资料。",fontSize=12.sp,color=Muted,lineHeight=20.sp,modifier=Modifier.padding(top=10.dp))
                        Text("曲风优先读取 QQ 音乐单曲资料。在线补全向 MusicBrainz 查询歌名、歌手、专辑与时长。听歌记录与登录凭据保存在本机。",
                            color=Muted,fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.padding(top=8.dp))
                    }
                    item {TextButton(onClick=onLegacy) {Text("旧版监听与记录",color=Muted,fontSize=12.sp)}}
                    if(connected)item {TextButton(onClick=onLogout) {Text("退出 QQ 音乐账号",color=Color(0xFFCE9E91),fontSize=12.sp)}}
                    item {Text("RESONANCE · 共鸣 ${BuildConfig.VERSION_NAME}",color=Muted.copy(alpha=.5f),fontSize=10.sp,letterSpacing=1.5.sp,modifier=Modifier.padding(vertical=18.dp))}
                }
            }
            DirectionChangeBanner(notice, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
            }
        }
        }
        if(expandedPlayer && current!=null) ModalBottomSheet(onDismissRequest={expandedPlayer=false},containerColor=Ink,
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Box {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=30.dp).navigationBarsPadding(),horizontalAlignment=Alignment.CenterHorizontally) {
                val coverShape = RoundedCornerShape(22.dp)
                CoverArt(seedArtwork,Modifier.widthIn(max=232.dp).fillMaxWidth(.9f).aspectRatio(1f)
                    .shadow(15.dp,coverShape).clip(coverShape))
                Row(Modifier.fillMaxWidth().padding(top=16.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(current.name,fontSize=24.sp,fontWeight=FontWeight.SemiBold,lineHeight=30.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(current.artist,color=Muted,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    FavoriteControl(current,library,onFavorite,compact=true)
                }
                PlaybackSeekBar(current.mid, player.durationMs, clock, onSeek)
                Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                    CircleAction(ResonanceGlyph.PREVIOUS,"上一首",onPrevious,size=48)
                    CircleAction(if(player.playing || (player.loading && player.playRequested))ResonanceGlyph.PAUSE else ResonanceGlyph.PLAY,"播放或暂停",onToggle,true,72)
                    CircleAction(ResonanceGlyph.NEXT,"下一首",onNext,size=48)
                }
                val labels=library.genres[current.mid]?.labels.orEmpty()
                if(labels.isNotEmpty())CompleteTagRow(labels)
                else Text(GenreLookupPolicy.description(library.genres[current.mid]),color=Muted,fontSize=12.sp)
                ListeningAction("相似推荐",{QqLibrary.similar(current.mid);expandedPlayer=false;tab=0},
                    Modifier.fillMaxWidth().padding(top=8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick={openFeedback(current,true)}){Text("多来这种")}
                    TextButton(onClick={openFeedback(current,false)}){Text("暂时少放")}
                }
                GlassSurface(Modifier.fillMaxWidth(), 16.dp, Panel) {
                    Column(Modifier.padding(12.dp)) {
                        Text("播放模式 · 队列 ${player.queueSize} 首",color=Muted,fontSize=12.sp)
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                            PlaybackMode.entries.forEach {mode ->
                                PlaybackChip(mode.label, player.playbackMode == mode.name) {onPlaybackMode(mode.name)}
                            }
                        }
                        PlaybackRangeChoices(player.playbackRange,onPlaybackRange)
                    }
                }
                TextButton(onClick={expandedPlayer=false;showDirectionHistory=true}){Text("最近换向记录")}
                Text(if(player.excerpt)"试听片段 · 暂不纳入偏好" else "QQ 音乐音源 · 后台播放",color=Muted,fontSize=11.sp,modifier=Modifier.padding(vertical=20.dp))
            }
            DirectionChangeBanner(notice,Modifier.align(Alignment.BottomCenter).padding(bottom=32.dp))
            }
        }
        detailSong?.let {song -> ModalBottomSheet(onDismissRequest={detailSong=null},containerColor=Ink,
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Box {Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("歌曲详情",color=Muted,fontSize=12.sp)
                Text(song.name,fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                Text(song.artist,color=Muted,fontSize=14.sp)
                if(song.album.isNotBlank())Text(song.album,color=Muted,fontSize=12.sp)
                Text(timeText(song.durationMs),color=Muted,fontSize=12.sp)
                FavoriteControl(song,library,onFavorite)
                Text("全部曲风标签",fontSize=15.sp)
                Text(library.genres[song.mid]?.labels?.takeIf {it.isNotEmpty()}?.sorted()?.joinToString(" · ") {genreName(it)}
                    ?: GenreLookupPolicy.description(library.genres[song.mid]),color=Accent,fontSize=14.sp,lineHeight=24.sp)
                library.genres[song.mid]?.providers()?.forEach {(provider,labels)->
                    Text((if(provider=="qq-song-detail")"QQ 单曲资料：" else "严格匹配的补充曲库：")+labels.sorted().joinToString(" · "){genreName(it)},color=Muted,fontSize=11.sp,lineHeight=18.sp)
                }
                library.genres[song.mid]?.descriptors?.takeIf {it.isNotEmpty()}?.let {labels ->
                    Text("听感线索 · 曲库标注",fontSize=15.sp)
                    Text(labels.sorted().joinToString(" · "){descriptorName(it)},color=Accent,fontSize=14.sp)
                    Text("来自严格匹配录音的社区标注，至少 2 票；单独展示，尚不用于自动选歌，也不是声音模型识别结果。",color=Muted,fontSize=11.sp,lineHeight=18.sp)
                }
                Text(library.genres[song.mid]?.enrichmentNote?.ifBlank {"仅展示曲库实际提供的标签，缺少资料不会猜测情绪。"}?:"缺少曲风不影响播放，惊喜抽取不会为歌曲添加标签。",color=Muted,fontSize=11.sp,lineHeight=18.sp)
                OutlinedButton(onClick={onEnrich(song.mid)},enabled=supplements && connected && !library.enriching){Text("补充这首的标签")}
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick={openFeedback(song,true)}){Text("多来这种")}
                    TextButton(onClick={openFeedback(song,false)}){Text("暂时少放")}
                }
                Spacer(Modifier.height(20.dp))
            }
            DirectionChangeBanner(notice,Modifier.align(Alignment.BottomCenter).padding(bottom=24.dp))}
        }}
        if(showIntent)ModalBottomSheet(onDismissRequest={showIntent=false},containerColor=Ink,
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("这次想听什么",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(!scoreTab,{scoreTab=false},label={Text("选歌条件")})
                    FilterChip(scoreTab,{scoreTab=true},label={Text("标签分数")})
                }
                if(scoreTab) {
                    GenreScorePanel(library)
                    OutlinedButton(onClick={showScoreReset=true},modifier=Modifier.fillMaxWidth()){Text("清除当前所有分数")}
                } else {
                Text("检查歌曲的全部已有标签。想听可多选，避开项优先；条件不会替歌曲添加标签。",fontSize=12.sp,color=Muted,lineHeight=20.sp)
                val choices=(library.genres.values.flatMap {it.labels}+listOf("pop","rock","folk","jazz","electronic","r&b","hip hop")).distinct().sorted()
                Text("想听（多选）",fontSize=14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {choices.forEach {label ->
                    FilterChip(selected=label in draftIntent.wanted,onClick={draftIntent=draftIntent.copy(wanted=if(label in draftIntent.wanted)draftIntent.wanted-label else draftIntent.wanted+label,excluded=draftIntent.excluded-label)},
                        label={Text(genreName(label),fontSize=12.sp)},modifier=Modifier.semantics {contentDescription="想听${genreName(label)}"})
                }}
                SettingSwitch("同时满足所有想听标签","关闭时命中任意一个即可；开启时必须全部命中。",draftIntent.matchAll){draftIntent=draftIntent.copy(matchAll=it)}
                Text("暂时避开（多选）",fontSize=14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {choices.forEach {label ->
                    FilterChip(selected=label in draftIntent.excluded,onClick={draftIntent=draftIntent.copy(excluded=if(label in draftIntent.excluded)draftIntent.excluded-label else draftIntent.excluded+label,wanted=draftIntent.wanted-label)},
                        label={Text(genreName(label),fontSize=12.sp)},modifier=Modifier.semantics {contentDescription="避开${genreName(label)}"})
                }}
                Text("有筛选条件时，未知曲风暂不进入推荐或惊喜抽取。只有“流行”等大类时，仍无法据此区分情绪与节奏。手动从收藏点歌不受这些推荐条件限制。",fontSize=11.sp,color=Muted,lineHeight=19.sp)
                Button(onClick={onIntent(draftIntent);showIntent=false},modifier=Modifier.fillMaxWidth()){Text("应用并推荐")}
                TextButton(onClick={onIntent(ListeningIntent());showIntent=false},modifier=Modifier.fillMaxWidth()){Text("清除本次条件")}
                }
            }
        }
        if(showScoreReset)AlertDialog(onDismissRequest={showScoreReset=false},containerColor=Panel,
            title={Text("清除当前所有分数？")},
            text={Text("当前账号的全部标签分数归零，之后按新的听歌反馈积累。保留收藏、曲风、听歌记录和陪伴时长；正在播放的歌曲继续播放。")},
            confirmButton={TextButton(onClick={showScoreReset=false;onClearScores()}){Text("确认清零")}},
            dismissButton={TextButton(onClick={showScoreReset=false}){Text("取消")}})
        feedbackSong?.let {song->ModalBottomSheet(onDismissRequest={feedbackSong=null},containerColor=Ink,
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(feedbackMore)"多来这种" else "暂时少放",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                Text(song.name,color=Muted,fontSize=13.sp)
                Text("选择要调整的曲风。每次主动反馈 ${if(feedbackMore)"+2" else "−2"}，累计上限 ±6；影响相似推荐、换向和随机权重。",fontSize=12.sp,color=Muted,lineHeight=20.sp)
                val labels=library.genres[song.mid]?.labels.orEmpty()
                if(labels.isEmpty())Text("这首歌还没有可靠曲风，先补充资料后再调整。",color=Muted,fontSize=13.sp)
                labels.sorted().forEach {tag->FilterChip(tag in feedbackLabels,{feedbackLabels=if(tag in feedbackLabels)feedbackLabels-tag else feedbackLabels+tag},label={Text(genreName(tag))})}
                if(labels.size==1)Text("目前只有一个标签，反馈会影响所有带这个标签的歌。",fontSize=11.sp,color=Muted,lineHeight=18.sp)
                Button(onClick={onSongFeedback(song.mid,feedbackLabels,feedbackMore);feedbackSong=null},enabled=feedbackLabels.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("确认调整")}
            }
        }}
        if(showDirectionHistory)ModalBottomSheet(onDismissRequest={showDirectionHistory=false},containerColor=Ink,
            sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {Text("最近换向记录",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                    Text("只在目标歌曲实际播放且进度推进后确认；保存最近 20 次。",color=Muted,fontSize=12.sp,lineHeight=20.sp)}
                if(library.directionHistory.isEmpty())item {Text("还没有自动换向记录。连续 4 次有效快跳后会记录尝试结果。",color=Muted,fontSize=13.sp)}
                items(library.directionHistory.asReversed(),key={it.id}) {row ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(directionStateName(row.state)+" · "+directionLabel(row.kind),color=Accent,fontSize=12.sp)
                        Text(row.targetName.ifBlank {row.targetMid},fontSize=17.sp)
                        Text(java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(java.util.Date(row.time)),fontSize=11.sp,color=Muted)
                        Text(row.detail.ifBlank {"等待目标歌曲播放确认"},fontSize=12.sp,lineHeight=20.sp)
                        Text(row.reason,fontSize=11.sp,color=Muted,lineHeight=18.sp)
                        Text("当时标签分数："+row.scores.entries.sortedByDescending {it.value}.joinToString(" · "){genreName(it.key)+" "+scoreText(it.value)}.ifBlank {"暂无可用标签"},fontSize=11.sp,color=Muted,lineHeight=18.sp)
                    }
                }
            }
        }
    } }
}

@Composable
fun CompleteTagRow(labels:Set<String>,modifier:Modifier=Modifier) {
    val gap=5.dp
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
        layout(constraints.constrainWidth(available),constraints.constrainHeight(visible.maxOfOrNull {it.height}?:18.dp.roundToPx())) {
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
        val remaining=notice?.let {3600-(SystemClock.elapsedRealtime()-it.createdAt)}?:0
        if(notice!=null && remaining>0) {
            shown=notice;visible=true;delay(remaining);visible=false
        } else visible=false
    }
    AnimatedVisibility(visible,modifier.padding(horizontal=24.dp).widthIn(max=340.dp),enter=fadeIn(tween(200)),exit=fadeOut(tween(450))) {
        Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Accent.copy(alpha=.96f)).padding(horizontal=18.dp,vertical=13.dp)
            .semantics {liveRegion=LiveRegionMode.Polite}) {
            Text(shown?.message?.takeIf {it.isNotBlank()} ?: (if(shown?.kind=="BATCH")"已换一批" else "已换个方向")+shown?.genres?.takeIf {it.isNotEmpty()}?.sorted()?.joinToString(" · ",prefix=" · ") {genreName(it)}.orEmpty(),
                color=Ink,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
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
        Box(Modifier.size(40.dp).pressMotion(interaction,.90f).clip(CircleShape).background(if(liked)Selected else Raised),contentAlignment=Alignment.Center) {
            if(busy)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
            else Glyph(if(song.mid in library.favoriteUncertain)ResonanceGlyph.REFRESH else ResonanceGlyph.HEART,
                Modifier.size(20.dp),if(liked && enabled)Accent else Muted)
        }
    } else OutlinedButton(onClick={onFavorite(song.mid)},enabled=enabled,interactionSource=interaction,modifier=Modifier.pressMotion(interaction)) {
        Glyph(ResonanceGlyph.HEART,Modifier.size(17.dp),if(liked)Accent else Muted)
        Spacer(Modifier.width(8.dp))
        Text(label,fontSize=12.sp)
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlaybackRangeChoices(selected:String,onSelect:(String)->Unit) {
    Column {
        Text("随机与循环范围",fontSize=12.sp,color=Muted)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            PlaybackRange.entries.forEach {range -> PlaybackChip(range.label, selected == range.name) {onSelect(range.name)}}
        }
    }
}
private fun scoreText(value:Double)=String.format(java.util.Locale.ROOT,"%+.1f",value)
private fun directionStateName(state:String)=when(state){"PLAYING"->"已确认播放";"FAILED"->"播放失败";"CANCELED"->"已取消";"UNCONFIRMED"->"未确认播放";else->"正在切换"}
@Composable private fun GenreScorePanel(library:LibraryState) {
    val scores=remember(library.allTracks,library.genres,library.scoreSignals,library.manualScores){library.genreScores()}
    var showCalculation by remember {mutableStateOf(false)}
    Text("综合分越高，随机播放和推荐时越容易选到该曲风。",fontSize=12.sp,color=Muted,lineHeight=20.sp)
    Row(Modifier.fillMaxWidth()) {Text("标签",Modifier.weight(1f),fontSize=12.sp);Text("近期 / 长期 / 综合",fontSize=12.sp,color=Muted)}
    if(scores.isEmpty())Text("暂没有可用曲风标签。",color=Muted,fontSize=13.sp)
    scores.forEach {row ->
        Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text(genreName(row.label),fontSize=14.sp);Text("${row.observations} 次记录"+if(row.manual!=0.0)" · 主动 ${scoreText(row.manual)}" else "",fontSize=10.sp,color=Muted)}
            Text("${scoreText(row.recent)} / ${scoreText(row.longTerm)} / ${scoreText(row.combined)}",fontSize=12.sp,color=if(row.combined<0)Color(0xFFCE9E91) else Accent)
        }
    }
    TextButton(onClick={showCalculation=!showCalculation},contentPadding=PaddingValues(horizontal=0.dp)) {
        Text(if(showCalculation)"收起计分说明" else "分数怎么算",fontSize=12.sp)
    }
    if(showCalculation) Text("听完 +2，中途切歌 −1，有效快跳 −3。多标签平分反馈。综合分包含最近 20 条的 70%、最近 500 条归一到 20 条后的 30%，再加主动反馈。",
        fontSize=12.sp,color=Muted,lineHeight=20.sp)
}
@Composable private fun GenreScoreSummary(library:LibraryState,onAll:()->Unit,onClear:()->Unit) {
    val scores=remember(library.allTracks,library.genres,library.scoreSignals,library.manualScores){library.genreScores()}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(horizontal=14.dp,vertical=6.dp),
        verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("当前标签分数",fontSize=17.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            TextButton(onClick=onAll,contentPadding=PaddingValues(horizontal=6.dp)){Text("查看全部 ${scores.size} 项",fontSize=11.sp)}
        }
        if(scores.isEmpty())Text("暂无曲风标签，取得资料后会在这里显示。",fontSize=12.sp,color=Muted)
        else Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            scores.sortedByDescending {kotlin.math.abs(it.combined)}.forEach {row ->
                Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Raised).padding(horizontal=10.dp,vertical=7.dp),
                    horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(genreName(row.label),fontSize=12.sp,color=Muted)
                    Text(scoreText(row.combined),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=if(row.combined<0)Color(0xFFFFB8B2) else Accent)
                }
            }
        }
        TextButton(onClick=onClear,contentPadding=PaddingValues(0.dp)){Text("清除当前所有分数",fontSize=11.sp,color=Muted)}
    }
}
@Composable private fun SettingSwitch(title:String,detail:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=14.dp)) {Text(title,fontSize=14.sp);Text(detail,fontSize=11.sp,color=Muted,lineHeight=17.sp,modifier=Modifier.padding(top=6.dp))}
        Switch(checked,onChange)
    }
}
@Composable
private fun rememberDiscoveryEntries(
    library: LibraryState, search: String, unheardOnly: Boolean, genre: String?
): List<DiscoveryEntry>? {
    val input = remember(library.favorites, library.discoveries, library.genres, library.listening,
        library.scoreSignals, library.manualScores, library.discoverySources, library.intent) { library }
    return key(library.accountScope, library.intent, search, unheardOnly, genre) {
        var entries by remember { mutableStateOf<List<DiscoveryEntry>?>(null) }
        LaunchedEffect(input) {
            entries = withContext(Dispatchers.Default) { DiscoveryBrowser.entries(input, search, unheardOnly, genre) }
        }
        entries
    }
}

@Composable
private fun ListeningMinutes(savedMinutes: Long, clock: State<PlaybackClock>) {
    val minutes by remember(clock, savedMinutes) {
        derivedStateOf { maxOf(savedMinutes, clock.value.listeningMinutes) }
    }
    Text("$minutes 分钟", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PlaybackSeekBar(mid: String, durationMs: Long, clock: State<PlaybackClock>, onSeek: (Float) -> Unit) {
    val positionMs = clock.value.positionFor(mid)
    var drag by remember(mid) { mutableStateOf<Float?>(null) }
    Slider(
        value = drag ?: playbackFraction(positionMs, durationMs),
        onValueChange = { drag = it },
        onValueChangeFinished = { drag?.let(onSeek); drag = null },
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(timeText(drag?.let { (durationMs * it).toLong() } ?: positionMs), color = Muted, fontSize = 11.sp)
        Text(timeText(durationMs), color = Muted, fontSize = 11.sp)
    }
}

private fun PlaybackClock.positionFor(songMid: String): Long = if (mid == songMid) positionMs else 0L
private fun playbackFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

private fun timeText(ms:Long):String {val seconds=(ms/1000).coerceAtLeast(0);return "%d:%02d".format(seconds/60,seconds%60)}
