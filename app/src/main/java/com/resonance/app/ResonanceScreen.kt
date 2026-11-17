package com.resonance.app

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

private val Ink = Color(0xFF111610)
private val Panel = Color(0xFF1C231B)
private val Muted = Color(0xFF9AA393)
private val Lime = Color(0xFFCEFA8D)
private val Cream = Color(0xFFF5F5E9)
private val SoftLine = Color(0xFF303A2C)

@Composable
fun ResonanceTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink, onBackground = Cream,
        surface = Panel, onSurface = Cream, secondary = Lime, outline = SoftLine,
        primaryContainer = Color(0xFF35452B), onPrimaryContainer = Lime,
        secondaryContainer = Color(0xFF35452B), onSecondaryContainer = Lime,
        surfaceVariant = Color(0xFF252E22), onSurfaceVariant = Muted), content = content)

enum class ResonanceGlyph { PLAY, PAUSE, NEXT, PREVIOUS, HOME, LIBRARY, PERSON, SEARCH, HEART, BACK, CLOSE, REFRESH, WAVE }

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
        }
    }
}

private object CoverMemory {
    val images = object : LruCache<String, ImageBitmap>(12*1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4 / 1024
    }
}
@Composable
fun AlbumCover(song: QqCatalogueTrack?, modifier: Modifier = Modifier) {
    val url = song?.let(CatalogueStore::artwork)
    var bitmap by remember(url) { mutableStateOf(url?.let { CoverMemory.images.get(it) }) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = withContext(Dispatchers.IO) {
            try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout=5000;connection.readTimeout=5000
                    if(connection.responseCode != 200) null else connection.inputStream.use { input ->
                        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) { val n=input.read(buffer);if(n<0)break;if(output.size()+n>1_048_576)throw IllegalStateException();output.write(buffer,0,n) }
                        BitmapFactory.decodeByteArray(output.toByteArray(),0,output.size())?.asImageBitmap()?.also { CoverMemory.images.put(url,it) }
                    }
                } finally {connection.disconnect()}
            } catch(_:Exception){null}
        }
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF3D5138),Color(0xFF252C22)))),contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val r=size.minDimension*.4f
            drawCircle(Color(0xFF121A11),r)
            for(i in 1..4)drawCircle(Color.White.copy(alpha=.075f),r*(.3f+i*.13f),style=Stroke(1.2f))
            drawCircle(Lime.copy(alpha=.8f),r*.22f)
            drawCircle(Ink,r*.045f)
        }
    }
}
private fun genreName(value: String) = when(value) {
    "pop"->"流行";"rock"->"摇滚";"folk"->"民谣";"jazz"->"爵士";"electronic"->"电子";"hip hop"->"说唱";
    "r&b"->"R&B";"classical"->"古典";"soul"->"灵魂乐";"funk"->"放克";"country"->"乡村";"metal"->"金属";else->value
}
@Composable private fun TinyTag(text:String, active:Boolean=false) {
    Text(text, color=if(active)Lime else Muted, fontSize=10.sp,
        modifier=Modifier.clip(RoundedCornerShape(6.dp)).background(if(active)Color(0xFF35452B) else Color(0xFF272F24)).padding(horizontal=7.dp,vertical=4.dp))
}
@Composable private fun SectionTitle(title:String, detail:String="", action:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold)
        if(detail.isNotBlank()) Text(detail,color=Muted,fontSize=12.sp,modifier=if(action==null)Modifier else Modifier.clickable(onClick=action).padding(6.dp))
    }
}
@Composable private fun CircleAction(type:ResonanceGlyph,label:String,onClick:()->Unit,accent:Boolean=false,size:Int=44) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(if(accent)Lime else Color.Transparent).clickable(onClick=onClick)
        .semantics {contentDescription=label},contentAlignment=Alignment.Center) {
        Glyph(type,Modifier.size((size*.52f).dp),if(accent)Ink else Cream)
    }
}
@Composable private fun TrackRow(song:QqCatalogueTrack, labels:Set<String>, onClick:()->Unit, current:Boolean=false, note:String="") {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        AlbumCover(song,Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal=13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(song.name,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(current)Lime else Cream,fontSize=15.sp,fontWeight=FontWeight.Medium)
            Text(note.ifBlank {song.artist},maxLines=1,overflow=TextOverflow.Ellipsis,color=Muted,fontSize=12.sp)
        }
        if(labels.isNotEmpty()) TinyTag(genreName(labels.first()))
        Glyph(if(current)ResonanceGlyph.WAVE else ResonanceGlyph.PLAY,Modifier.padding(start=12.dp,end=4.dp).size(21.dp),if(current)Lime else Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResonanceScreen(library:LibraryState, player:PlayerStatus, onLogin:()->Unit, onSync:()->Unit,
    onPlay:(QqCatalogueTrack)->Unit, onToggle:()->Unit, onNext:()->Unit, onPrevious:()->Unit, onSeek:(Float)->Unit,
    onLogout:()->Unit, onLegacy:()->Unit, onPlaylists:()->Unit, onPlaylist:(QqCataloguePlaylist)->Unit) {
    val context=LocalContext.current
    val settings=remember {context.getSharedPreferences("resonance_settings",0)}
    var tab by remember {mutableIntStateOf(0)}
    var search by remember {mutableStateOf("")}
    var filter by remember {mutableIntStateOf(0)}
    var expandedPlayer by remember {mutableStateOf(false)}
    var sources by remember {mutableStateOf(false)}
    var autoDirection by remember {mutableStateOf(settings.getBoolean("qq_auto_direction",true))}
    var supplements by remember {mutableStateOf(settings.getBoolean("catalogue_external_tags",true))}
    val current=library.allTracks.firstOrNull {it.mid==player.mid}
    val seed=current ?: library.favorites.firstOrNull {library.genres[it.mid]?.labels?.isNotEmpty()==true} ?: library.favorites.firstOrNull()
    val connected=library.accountScope.isNotBlank()
    ResonanceTheme {
        Scaffold(containerColor=Ink,bottomBar={
            Column(Modifier.background(Ink)) {
                if(current!=null) {
                    Row(Modifier.padding(horizontal=16.dp,vertical=8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
                        .clickable {expandedPlayer=true}.padding(9.dp),verticalAlignment=Alignment.CenterVertically) {
                        AlbumCover(current,Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).padding(horizontal=11.dp)) {
                            Text(current.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(player.loading)"正在连接音源…" else if(player.message.contains("无法")) player.message else current.artist,color=Muted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        CircleAction(if(player.playing || (player.loading && player.playRequested))ResonanceGlyph.PAUSE else ResonanceGlyph.PLAY,"播放或暂停",onToggle,true,36)
                        CircleAction(ResonanceGlyph.NEXT,"下一首",onNext,false,36)
                    }
                }
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp),horizontalArrangement=Arrangement.SpaceAround) {
                    listOf("为你" to ResonanceGlyph.HOME,"收藏" to ResonanceGlyph.LIBRARY,"我的" to ResonanceGlyph.PERSON).forEachIndexed {i,item ->
                        Column(Modifier.weight(1f).fillMaxHeight().clickable {tab=i}.padding(top=8.dp),horizontalAlignment=Alignment.CenterHorizontally,
                            verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Glyph(item.second,Modifier.size(23.dp),if(tab==i)Lime else Muted)
                            Text(item.first,fontSize=10.sp,color=if(tab==i)Lime else Muted)
                        }
                    }
                }
            }
        }) {padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(horizontal=22.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Glyph(ResonanceGlyph.WAVE,Modifier.size(31.dp),Lime)
                        Text("共鸣",fontSize=25.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp,modifier=Modifier.padding(start=8.dp))
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.clip(CircleShape).background(Panel).clickable {if(!connected)onLogin() else tab=2}.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(if(connected)Lime else Muted))
                            Text(if(connected)"QQ 音乐已连接" else "连接 QQ 音乐",fontSize=10.sp,color=Muted,modifier=Modifier.padding(start=6.dp))
                        }
                    }
                }
                if(tab==0) {
                    item {
                        Text("找到当下的感觉。",fontSize=29.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.7).sp)
                        Text("熟悉的喜欢，也有下一次心动。",color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=9.dp,bottom=14.dp))
                    }
                    item {
                        Box(Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(25.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF3E5034),Color(0xFF232F22))))) {
                            AlbumCover(seed,Modifier.align(Alignment.CenterEnd).padding(end=18.dp).size(122.dp).clip(CircleShape))
                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF34452D),Color(0xFF34452D).copy(alpha=.88f),Color.Transparent))))
                            Column(Modifier.padding(23.dp).fillMaxHeight(),verticalArrangement=Arrangement.SpaceBetween) {
                                Text("RESONANCE  /  为你而来",color=Lime,fontSize=10.sp,letterSpacing=1.8.sp)
                                Text(if(connected)"从你喜欢的歌，\n听见新的可能。" else "把喜欢的歌，\n带到这里。",fontSize=23.sp,fontWeight=FontWeight.Medium,lineHeight=33.sp)
                                Button(onClick={if(!connected)onLogin() else seed?.let {QqLibrary.similar(it.mid)}},
                                    contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),colors=ButtonDefaults.buttonColors(containerColor=Lime,contentColor=Ink)) {
                                    Text(if(connected)"继续这个感觉" else "连接我的 QQ 音乐",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if(connected) item {
                        Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=5.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                            listOf(library.favorites.size.toString() to "收藏歌曲",library.taggedCount.toString() to "曲风资料",
                                (if(library.favorites.isEmpty())"0%" else "${library.taggedCount*100/library.favorites.size}%") to "资料覆盖").forEach { metric ->
                                Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {Text(metric.first,fontSize=23.sp,fontWeight=FontWeight.Medium);Text(metric.second,color=Muted,fontSize=10.sp)}
                            }
                        }
                    }
                    if(library.syncing || library.analyzing) item {
                        LinearProgressIndicator(modifier=Modifier.fillMaxWidth().height(2.dp),color=Lime,trackColor=SoftLine)
                        Text(if(library.syncing)library.message else "正在自动整理曲风 · ${library.taggedCount} 首已有资料",color=Muted,fontSize=11.sp)
                    }
                    item {SectionTitle(if(library.recommendation==null)"从你的收藏开始" else "接着，听这些",if(library.recommendation==null)"查看全部" else "推荐理由",{if(library.recommendation==null)tab=1 else sources=!sources})}
                    val recommendations=library.recommendation
                    if(recommendations!=null) {
                        item {Text(recommendations.heading,color=Lime,fontSize=12.sp)}
                        if(recommendations.picks.isEmpty() || sources) item {Text(recommendations.explanation,color=Muted,fontSize=12.sp,lineHeight=20.sp)}
                        items(recommendations.picks,key={it.track.mediaId}) {pick ->
                            library.allTracks.firstOrNull {"qq:${it.mid}"==pick.track.mediaId}?.let {song ->
                                TrackRow(song,library.genres[song.mid]?.labels.orEmpty(),{onPlay(song)},song.mid==player.mid,
                                    if(pick.unfamiliar)"新歌候选 · ${song.artist}" else song.artist)
                            }
                        }
                    } else {
                        items(library.favorites.take(6),key={it.mid}) {song -> TrackRow(song,library.genres[song.mid]?.labels.orEmpty(),{onPlay(song)},song.mid==player.mid)}
                    }
                    if(library.favorites.isEmpty()) item {Text(if(connected)"收藏正在路上，稍后就会出现在这里。" else "登录后自动同步收藏，无需逐首填写曲风。",color=Muted,fontSize=13.sp,modifier=Modifier.padding(vertical=16.dp))}
                    if(connected) {
                        item {SectionTitle("你的收藏偏好","来自曲库标签")}
                        val taste=library.favorites.flatMap {library.genres[it.mid]?.labels.orEmpty()}.groupingBy {it}.eachCount().entries.sortedByDescending {it.value}.take(4)
                        items(taste,key={it.key}) {entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(genreName(entry.key),color=Cream,fontSize=12.sp,modifier=Modifier.width(65.dp))
                                LinearProgressIndicator(progress={entry.value.toFloat()/(taste.firstOrNull()?.value?:1)},modifier=Modifier.weight(1f).height(5.dp).clip(CircleShape),color=Lime,trackColor=Panel)
                                Text("${entry.value} 首",color=Muted,fontSize=10.sp,modifier=Modifier.padding(start=14.dp))
                            }
                        }
                    }
                } else if(tab==1) {
                    item {SectionTitle(library.openedPlaylist?.name ?: "我的收藏",
                        if(library.openedPlaylist!=null)"返回收藏" else if(library.syncing)"同步中…" else "同步收藏",
                        {if(library.openedPlaylist!=null) QqLibrary.closePlaylist() else onSync()})}
                    item {OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("搜索歌曲、歌手",fontSize=13.sp)},
                        leadingIcon={Glyph(ResonanceGlyph.SEARCH,Modifier.size(19.dp),Muted)},shape=RoundedCornerShape(15.dp))}
                    item {Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {listOf("全部","已识别曲风","待补齐").forEachIndexed {i,label ->
                        FilterChip(selected=filter==i,onClick={filter=i},label={Text(label,fontSize=11.sp)})
                    }}}
                    val browsing = if(library.openedPlaylist!=null) library.playlistTracks else library.favorites
                    val filtered=browsing.filter {song -> (search.isBlank() || song.name.contains(search,true) || song.artist.contains(search,true)) &&
                        (filter==0 || (library.genres[song.mid]?.labels?.isNotEmpty()==true)==(filter==1))}
                    if(filtered.isEmpty()) item {Text(if(!connected)"连接 QQ 音乐，自动带回你的收藏。" else "暂无符合条件的歌曲。",color=Muted,modifier=Modifier.padding(vertical=24.dp))}
                    items(filtered,key={it.mid}) {song ->TrackRow(song,library.genres[song.mid]?.labels.orEmpty(),{onPlay(song)},song.mid==player.mid)}
                    item {SectionTitle("我的歌单","读取歌单",onPlaylists)}
                    items(library.playlists,key={it.id}) {list ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).clickable {onPlaylist(list)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                            Glyph(ResonanceGlyph.LIBRARY,Modifier.size(24.dp),Lime);Text(list.name,fontSize=14.sp,modifier=Modifier.padding(start=14.dp))
                        }
                    }
                } else {
                    item {Text("让音乐，适合你。",fontSize=27.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(bottom=12.dp))}
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("QQ 音乐",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(connected)"账号已连接 · 收藏自动同步" else "连接你的账号，带回喜欢的音乐。",color=Muted,fontSize=12.sp)
                            Button(onClick=onLogin) {Text(if(connected)"管理账号 / 更新授权" else "连接 QQ 音乐")}
                        }
                    }
                    item {SectionTitle("听歌方式")}
                    item {SettingSwitch("连续快跳，自动换方向","4 次快跳后尝试不同曲风，保持 120 秒冷却。",autoDirection){autoDirection=it;settings.edit().putBoolean("qq_auto_direction",it).apply()}}
                    item {SettingSwitch("在线补全曲库资料","QQ 标签优先，缺失资料由补充来源自动查询。",supplements){supplements=it;settings.edit().putBoolean("catalogue_external_tags",it).apply()}}
                    item {SectionTitle("播放与流量")}
                    item {Text("默认标准音质 · 按需流式播放\n音频缓存上限 100MB\n收藏分析只读取资料，不批量下载歌曲。",fontSize=13.sp,color=Muted,lineHeight=24.sp)}
                    item {SectionTitle("连接状态")}
                    item {Text(library.message,color=Muted,fontSize=12.sp,lineHeight=20.sp);if(player.mid.isNotBlank())Text(player.message,color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))}
                    item {TextButton(onClick={sources=!sources}) {Text("资料来源与隐私",color=Muted,fontSize=12.sp)}}
                    if(sources) item {Text("歌曲、收藏及音源：QQ 音乐。曲风优先读取 QQ 单曲流派；缺失时查询 MusicBrainz，仅发送歌名、歌手、专辑与时长。听歌记录与登录凭据留在本机。曲库标签不等于已完成声音或情绪模型分析。",color=Muted,fontSize=12.sp,lineHeight=20.sp)}
                    item {TextButton(onClick=onLegacy) {Text("旧版监听与记录",color=Muted,fontSize=12.sp)}}
                    if(connected)item {TextButton(onClick=onLogout) {Text("退出 QQ 音乐账号",color=Color(0xFFCE9E91),fontSize=12.sp)}}
                    item {Text("RESONANCE · 共鸣 0.9.0",color=Muted.copy(alpha=.5f),fontSize=10.sp,letterSpacing=1.5.sp,modifier=Modifier.padding(vertical=18.dp))}
                }
            }
        }
        if(expandedPlayer && current!=null) ModalBottomSheet(onDismissRequest={expandedPlayer=false},containerColor=Ink) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=30.dp).navigationBarsPadding(),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("此刻，正在共鸣",color=Muted,fontSize=12.sp,letterSpacing=2.sp,modifier=Modifier.padding(bottom=25.dp))
                AlbumCover(current,Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
                Text(current.name,fontSize=25.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=25.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(current.artist,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=8.dp,bottom=18.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {library.genres[current.mid]?.labels?.take(3)?.forEach {TinyTag(genreName(it),true)}}
                var drag by remember {mutableStateOf<Float?>(null)}
                Slider(value=drag?:if(player.durationMs>0)(player.positionMs.toFloat()/player.durationMs).coerceIn(0f,1f) else 0f,
                    onValueChange={drag=it},onValueChangeFinished={drag?.let(onSeek);drag=null},modifier=Modifier.padding(top=14.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(timeText(player.positionMs),color=Muted,fontSize=11.sp);Text(timeText(player.durationMs),color=Muted,fontSize=11.sp)}
                Row(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                    CircleAction(ResonanceGlyph.PREVIOUS,"上一首",onPrevious,size=48)
                    CircleAction(if(player.playing || (player.loading && player.playRequested))ResonanceGlyph.PAUSE else ResonanceGlyph.PLAY,"播放或暂停",onToggle,true,72)
                    CircleAction(ResonanceGlyph.NEXT,"下一首",onNext,size=48)
                }
                Button(onClick={QqLibrary.similar(current.mid);expandedPlayer=false;tab=0},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Panel,contentColor=Lime)) {Text("继续这个感觉")}
                Text(if(player.excerpt)"试听片段 · 暂不纳入偏好" else "QQ 音乐音源 · 后台播放",color=Muted,fontSize=11.sp,modifier=Modifier.padding(vertical=20.dp))
            }
        }
    }
}
@Composable private fun SettingSwitch(title:String,detail:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end=14.dp)) {Text(title,fontSize=14.sp);Text(detail,fontSize=11.sp,color=Muted,lineHeight=17.sp,modifier=Modifier.padding(top=6.dp))}
        Switch(checked,onChange)
    }
}
private fun timeText(ms:Long):String {val seconds=(ms/1000).coerceAtLeast(0);return "%d:%02d".format(seconds/60,seconds%60)}
