package com.resonance.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun RecommendationPanel(current: SongInfo?, snapshot: MusicSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { context.getSharedPreferences("resonance_settings", Context.MODE_PRIVATE) }
    var automatic by remember { mutableStateOf(settings.getBoolean(StyleSession.AUTO_CONTROL, false)) }
    var featureRevision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var querySong by remember { mutableStateOf<SongInfo?>(null) }
    var matches by remember { mutableStateOf<List<RecordingMatch>?>(null) }
    var showLibrary by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf(StyleSession.plan) }
    var requestMessage by remember { mutableStateOf("") }
    LaunchedEffect(snapshot.control.status) { requestMessage = "" }
    val songs = remember(snapshot.metadataRevision, snapshot.historyRevision, current, featureRevision) { TrackFeatureStore.songs(context) }
    val featured = remember(songs, featureRevision) { TrackFeatureStore.tracks(context) }
    val currentFeature = remember(current, featureRevision) { current?.let { TrackFeatureStore.get(context, it) } }
    DisposableEffect(Unit) {
        val listener: (StylePlan?) -> Unit = { plan = it }
        StyleSession.subscribe(listener)
        onDispose { StyleSession.unsubscribe(listener) }
    }

    fun search(song: SongInfo) {
        if (busy) return
        querySong = song
        message = "正在查询「${song.name}」…"
        busy = true
        scope.launch {
            try {
                val result = MusicBrainzClient.search(song)
                matches = result.takeIf { it.isNotEmpty() }
                message = if (result.isEmpty()) "公开曲库未找到这首歌，请核实歌名和歌手；未生成曲风。" else "找到 ${result.size} 个录音候选，请核对版本。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "查询未完成：${e.message ?: "网络暂不可用"}" }
            finally { busy = false }
        }
    }

    HorizontalDivider()
    Text("同曲风推荐 / 快跳换方向")
    Text("连续快速跳过：${snapshot.skips} / 4 · ${snapshot.analysis}")
    Text("曲库：本机已识别 ${songs.size} 首，其中 ${featured.size} 首有已核对的曲风。")
    Text("当前曲风：" + (currentFeature?.genres?.takeIf { it.isNotEmpty() }?.joinToString() ?: "未知"))
    if (currentFeature != null) TextButton(onClick = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://musicbrainz.org/recording/${currentFeature.recordingId}")))
        }.onFailure { message = "没有可打开录音资料的浏览器，曲风资料仍保存在本机。" }
    }) { Text("曲风来源：MusicBrainz（录音版本已由你核对）") }
    Text("点击查询会把所选歌曲的歌名和歌手发送到 MusicBrainz 公开曲库；不发送媒体 ID、歌词或听歌历史。")
    Button(enabled = !busy && current != null && current.artist.isNotBlank(), onClick = { current?.let(::search) }) {
        Text(if (busy) "正在读取曲库…" else "查询当前歌曲曲风并核对版本")
    }
    if (current?.artist.isNullOrBlank()) Text("需要先识别或确认歌名与歌手，才能准确查询。")
    if (message.isNotBlank()) Text(message)
    OutlinedButton(onClick = { showLibrary = !showLibrary }) {
        Text(if (showLibrary) "收起本机曲库" else "补齐本机其他歌曲曲风")
    }
    if (showLibrary) {
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (songs.isEmpty()) Text("请先在 QQ 音乐播放几首歌，让应用识别歌曲信息。")
            songs.forEach { song ->
                val tags = TrackFeatureStore.get(context, song)?.genres.orEmpty()
                OutlinedButton(enabled = !busy && song.artist.isNotBlank(), onClick = { search(song) }) {
                    Text("${song.name} · ${song.artist}\n${if (tags.isEmpty()) "曲风未知，点击查询" else tags.joinToString()}")
                }
            }
        }
    }
    Button(enabled = current != null, onClick = {
        requestMessage = ""
        StyleSession.similar(context, current!!.mediaId)
    }) { Text("我想继续听这类歌") }
    OutlinedButton(enabled = current != null, onClick = {
        requestMessage = ""
        StyleSession.changeDirection(context, current!!.mediaId, automaticTrigger = false)
    }) { Text("重看最近四次快跳的换方向建议") }

    plan?.let { result ->
        Text(result.heading)
        Text(result.explanation)
        result.picks.forEach { pick ->
            Text("${pick.track.name} · ${pick.track.artist}")
            Text(pick.reason + if (pick.unfamiliar) " · 本机尚无听歌记录" else " · 本机有听歌记录")
            Button(enabled = snapshot.control.canRequest(pick.track.mediaId), onClick = {
                requestMessage = QqPlayback.request(context, pick.track.mediaId)
            }) { Text(if (snapshot.control.pending) "等待播放确认…" else "在 QQ 音乐播放此推荐") }
            if (!snapshot.control.canRequest(pick.track.mediaId) && !snapshot.control.pending)
                Text("QQ 音乐尚未公开此目标的指定播放能力，可到 QQ 音乐按歌名搜索。")
        }
    }
    if (requestMessage.isNotBlank()) Text(requestMessage)
    Text("播放结果：${snapshot.control.status}")
    Text("QQ 音乐公开队列 ${snapshot.control.queueSize} 首；媒体 ID 选曲：${if (snapshot.control.canPlayFromId) "已声明" else "未声明"}；队列选曲：${if (snapshot.control.canSkipToQueueItem) "已声明" else "未声明"}。声明不代表真机已验证。")
    Text("允许四次快跳后自动尝试换方向")
    Switch(checked = automatic, onCheckedChange = {
        automatic = it
        settings.edit().putBoolean(StyleSession.AUTO_CONTROL, it).apply()
    })
    Text("默认关闭。开启后，四次快跳且距上次分析满 120 秒时，只在曲风与指定播放能力齐全时请求一首；失败不循环跳歌。推荐操作产生的切换不算你的负反馈。")

    if (matches != null && querySong != null) {
        val selectedSong = querySong!!
        AlertDialog(onDismissRequest = { if (!busy) matches = null },
            title = { Text("核对「${selectedSong.name}」的录音版本") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("核对歌手、专辑、时长与现场/混音等版本。核对按钮会继续读取该录音的公开曲风；曲库可能未收录曲风。")
                    Text(message)
                    Text("QQ 音乐专辑：${selectedSong.album.ifBlank { "未知" }}；当前时长：" +
                        if (selectedSong.mediaId == current?.mediaId) "${snapshot.diagnostics.durationMs / 1000} 秒" else "请按歌曲实际版本核对")
                    matches.orEmpty().forEach { recording ->
                        Text("${recording.title} · ${recording.artist}\n" +
                            "${if (recording.durationMs > 0) "${recording.durationMs / 1000} 秒" else "时长未知"} · ${recording.version.ifBlank { "无版本备注" }}\n${recording.releases}")
                        TextButton(enabled = !busy, onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://musicbrainz.org/recording/${recording.id}")))
                            }.onFailure { message = "没有可打开资料的浏览器，请在当前列表核对版本。" }
                        }) { Text("查看录音资料") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val tags = MusicBrainzClient.genres(recording.id)
                                    val liveSong = SongInfoStore.get(context, selectedSong.mediaId)
                                    if (liveSong?.name != selectedSong.name || liveSong.artist != selectedSong.artist || liveSong.album != selectedSong.album)
                                        throw IllegalStateException("本机歌曲信息已变化，请重新查询核对")
                                    TrackFeatureStore.save(context, selectedSong, recording, tags)
                                    featureRevision++
                                    matches = null
                                    message = if (tags.isEmpty()) "这条录音没有公开曲风标签，已记下核对结果，仍按未知处理。" else
                                        "已保存「${selectedSong.name}」曲风：${tags.joinToString()}。来源：MusicBrainz 录音；版本对应由你核对。"
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { message = "曲风读取未完成：${e.message ?: "网络暂不可用"}" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "读取中…" else "版本相符，读取此录音曲风") }
                    }
                }
            }, confirmButton = { TextButton(enabled = !busy, onClick = { matches = null }) { Text("都不匹配 / 关闭") } })
    }
}
