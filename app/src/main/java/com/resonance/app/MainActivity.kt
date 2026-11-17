package com.resonance.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = this@MainActivity
            val settings = remember { getSharedPreferences("resonance_settings", Context.MODE_PRIVATE) }
            val legacyLabels = remember { getSharedPreferences("resonance_song_labels", Context.MODE_PRIVATE) }
            val historyPrefs = remember { getSharedPreferences("resonance_history", Context.MODE_PRIVATE) }

            var automaticMode by remember { mutableStateOf(settings.getBoolean("auto", false)) }
            var snapshot by remember { mutableStateOf(MusicSnapshot("等待 QQ 音乐播放", 0, "等待歌曲切换")) }
            var editedName by remember { mutableStateOf("") }
            var editedArtist by remember { mutableStateOf("") }
            var editedAlbum by remember { mutableStateOf("") }
            var savedRevision by remember { mutableIntStateOf(0) }
            var editorTouched by remember { mutableStateOf(false) }
            var historyRevision by remember { mutableIntStateOf(0) }
            var showDiagnostics by remember { mutableStateOf(false) }
            var showProfile by remember { mutableStateOf(false) }
            var showHistory by remember { mutableStateOf(false) }

            val diagnostics = snapshot.diagnostics
            val currentId = diagnostics.mediaId.ifBlank {
                if (snapshot.title.startsWith("媒体 ID：")) snapshot.title.removePrefix("媒体 ID：") else ""
            }
            val info = remember(currentId, savedRevision, snapshot.metadataRevision) {
                if (currentId.isBlank()) null else SongInfoStore.get(context, currentId)
            }
            val history = remember(historyRevision, snapshot.historyRevision, snapshot.fast, snapshot.partial, snapshot.completed) {
                try { JSONArray(historyPrefs.getString("events", "[]")) } catch (_: Exception) { JSONArray() }
            }
            val recent = remember(history) { ListeningInsights.fromEvents(history, 20) }
            val longTerm = remember(history) { ListeningInsights.fromEvents(history, 500) }

            LaunchedEffect(currentId, savedRevision) {
                editorTouched = false
                editedName = info?.name.orEmpty()
                editedArtist = info?.artist.orEmpty()
                editedAlbum = info?.album.orEmpty()
            }
            LaunchedEffect(currentId, snapshot.metadataRevision) {
                if (!editorTouched) {
                    editedName = info?.name.orEmpty()
                    editedArtist = info?.artist.orEmpty()
                    editedAlbum = info?.album.orEmpty()
                }
            }
            LaunchedEffect(automaticMode) { MusicState.autoMode = automaticMode }
            DisposableEffect(Unit) {
                val listener: (MusicSnapshot) -> Unit = { snapshot = it }
                MusicState.subscribe(listener)
                onDispose { MusicState.unsubscribe(listener) }
            }

            MaterialTheme {
                Scaffold(topBar = {
                    Text(
                        "Resonance · 共鸣 v0.6.2",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(20.dp)
                    )
                }) { inner ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(inner)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("当前歌曲", style = MaterialTheme.typography.titleMedium)
                        Text(info?.name ?: snapshot.title, style = MaterialTheme.typography.titleLarge)
                        if (currentId.isNotBlank()) {
                            Text("稳定媒体 ID：" + currentId, style = MaterialTheme.typography.bodySmall)
                            if (info != null) {
                                Text((if (info.automatic) "QQ 音乐自动读取：" else "人工确认信息：") + info.name +
                                    (if (info.artist.isNotBlank()) " — " + info.artist else "") +
                                    (if (info.album.isNotBlank()) " · " + info.album else ""))
                            } else {
                                Text(snapshot.metadataStatus + "；不会使用可能是歌词的 TITLE 自动填充。")
                            }
                            OutlinedTextField(
                                value = editedName, onValueChange = { editedName = it; editorTouched = true },
                                label = { Text("歌曲名称（请核实）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = editedArtist, onValueChange = { editedArtist = it; editorTouched = true },
                                label = { Text("歌手（可选）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = editedAlbum, onValueChange = { editedAlbum = it; editorTouched = true },
                                label = { Text("专辑（可选）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                enabled = editedName.isNotBlank(),
                                onClick = {
                                    SongInfoStore.saveConfirmed(
                                        context, currentId, editedName, editedArtist, editedAlbum
                                    )
                                    savedRevision++
                                }
                            ) { Text("确认并保存歌曲信息") }
                        }

                        HorizontalDivider()
                        Button(onClick = { showDiagnostics = !showDiagnostics }) {
                            Text(if (showDiagnostics) "收起媒体元数据诊断" else "查看媒体元数据诊断")
                        }
                        if (showDiagnostics) {
                            Text("当前会话的只读字段，供判断 QQ 音乐能否提供真实歌名。")
                            Text("MEDIA_ID：" + diagnostics.mediaId)
                            Text("TITLE（可能是动态歌词）：" + diagnostics.rawTitle)
                            Text("DISPLAY_TITLE（未验证）：" + diagnostics.displayTitle)
                            Text("ARTIST（QQ 原始复合字段）：" + diagnostics.artist)
                            Text("自动读取状态：" + snapshot.metadataStatus)
                            Text("ALBUM：" + diagnostics.album)
                            Text("DISPLAY_SUBTITLE：" + diagnostics.subtitle)
                            Text("DISPLAY_DESCRIPTION：" + diagnostics.description)
                            Text("播放进度：" + (diagnostics.positionMs.coerceAtLeast(0L) / 1000L) +
                                " 秒 / " + (diagnostics.durationMs / 1000L) + " 秒")
                            Text("播放状态：" + if (diagnostics.playing) "播放中" else "暂停或未知")
                            Text("同一 ID 内 TITLE 发生过变化：" +
                                if (diagnostics.titleChangedWithinId) "是" else "尚未观察到")
                            Text("同一 ID 内 DISPLAY_TITLE 发生过变化：" +
                                if (diagnostics.displayTitleChangedWithinId) "是" else "尚未观察到")
                            if (currentId.isNotBlank() && diagnostics.displayTitle.isNotBlank()) {
                                OutlinedButton(onClick = { editedName = diagnostics.displayTitle; editorTouched = true }) {
                                    Text("将 DISPLAY_TITLE 填入编辑框（需人工核实）")
                                }
                            }
                            Text(
                                "经设备验证的 ARTIST 歌名－歌手格式稳定后会自动缓存，并标明来源；TITLE 不会写入缓存。人工标注优先。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        HorizontalDivider()
                        Text("连续快速跳过：" + snapshot.skips + " / 4")
                        Text("最近记录累计：快速跳过 " + snapshot.fast + " · 中途切歌 " +
                            snapshot.partial + " · 基本听完 " + snapshot.completed)
                        Text("检测信息：" + snapshot.analysis)
                        Text("基本听完率（最多 500 条）：" + longTerm.completionPercent + "%")
                        LinearProgressIndicator(
                            progress = { longTerm.completionPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(onClick = { showProfile = !showProfile; historyRevision++ }) {
                            Text(if (showProfile) "收起偏好分析" else "查看短期 / 长期偏好分析")
                        }
                        if (showProfile) {
                            ProfileSection("短期偏好 · 最近最多 20 条", recent) { id ->
                                SongInfoStore.get(context, id)?.name ?: id
                            }
                            ProfileSection("长期偏好 · 最近最多 500 条", longTerm) { id ->
                                SongInfoStore.get(context, id)?.name ?: id
                            }
                            Text("这是单曲行为画像，不是已识别曲风，也没有连接推荐曲库。")
                        }

                        Button(onClick = { showHistory = !showHistory; historyRevision++ }) {
                            Text(if (showHistory) "收起最近记录" else "查看最近 10 条记录")
                        }
                        if (showHistory) {
                            if (history.length() == 0) Text("暂无历史记录")
                            for (i in history.length() - 1 downTo maxOf(0, history.length() - 10)) {
                                val item = history.optJSONObject(i) ?: continue
                                val id = item.optString("mediaId")
                                val songName = SongInfoStore.get(context, id)?.name ?: id
                                val category = when (item.optString("category")) {
                                    "fast" -> "快速跳过"
                                    "partial" -> "中途切歌"
                                    else -> "基本听完"
                                }
                                Text(songName + " · " + category + " · " +
                                    (item.optLong("playedMs") / 1000L) + " 秒")
                            }
                        }

                        Button(onClick = {
                            val labels = JSONObject()
                            legacyLabels.all.forEach { (id, value) ->
                                if (value is String) labels.put(id, value)
                            }
                            val exported = JSONObject()
                                .put("schemaVersion", 3)
                                .put("platform", "android")
                                .put("events", history)
                                .put("songLabels", labels)
                                .put("songInfo", SongInfoStore.exportJson(context))
                                .put("exportedAt", System.currentTimeMillis())
                                .toString(2)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("application/json").putExtra(Intent.EXTRA_TEXT, exported)
                            startActivity(Intent.createChooser(send, "导出 Resonance 本地记录"))
                        }) { Text("分享 / 导出听歌记录 JSON") }

                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("自动模式")
                            Switch(checked = automaticMode, onCheckedChange = {
                                automaticMode = it
                                settings.edit().putBoolean("auto", it).apply()
                            })
                        }
                        Text(
                            if (automaticMode) "自动模式：只分析行为，尚不会控制 QQ 音乐播放"
                            else "推荐模式：只分析行为，暂不干预播放"
                        )
                        Text("陌生歌曲探索：目标为 30%，尚未接入真实推荐接口。")
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("查看通知读取权限") }
                        Text(
                            "继续保留 v0.5 已验证的后台监听、行为记录和手动标注。没有上传听歌历史的操作。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSection(
    title: String,
    profile: BehaviorProfile,
    resolveName: (String) -> String
) {
    Text(title, fontWeight = FontWeight.Bold)
    Text("样本：" + profile.total + " 条 · 快跳 " + profile.fast +
        " · 中途 " + profile.partial + " · 基本听完 " + profile.completed +
        " · 听完率 " + profile.completionPercent + "%")
    if (profile.total < 5) {
        Text("样本不足（少于 5 条），不宜据此判断喜好。")
    }
    if (profile.scores.isNotEmpty()) {
        Text("分数较高的歌曲")
        profile.scores.sortedByDescending { it.score }.take(5).forEach { score ->
            Text(resolveName(score.mediaId) + " · " + score.score +
                " 分 · " + score.eventCount + " 次")
        }
        Text("分数较低的歌曲")
        profile.scores.sortedBy { it.score }.take(5).forEach { score ->
            Text(resolveName(score.mediaId) + " · " + score.score +
                " 分 · " + score.eventCount + " 次")
        }
    }
    Text("评分：基本听完 +2，中途切歌 -1，快速跳过 -3。")
}
