package com.resonance.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var auto by remember { mutableStateOf(false) }
            var title by remember { mutableStateOf("等待 QQ 音乐播放") }
            var skips by remember { mutableIntStateOf(0) }
            var analysis by remember { mutableStateOf("尚未触发分析") }
            DisposableEffect(Unit) {
                val listener: (MusicSnapshot) -> Unit = { snapshot ->
                    title = snapshot.title
                    skips = snapshot.skips
                    analysis = snapshot.analysis
                }
                MusicState.subscribe(listener)
                onDispose { MusicState.unsubscribe(listener) }
            }
            MaterialTheme {
                Scaffold(topBar = { Text("Resonance · 共鸣", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall) }) { inner ->
                    Column(Modifier.padding(inner).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("当前歌曲：$title")
                        Text("连续跳过：$skips / 4")
                        Text("分析结果：$analysis")
                        Text("陌生歌曲探索：30%（曲库接口待验证）")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("自动模式")
                            Switch(checked = auto, onCheckedChange = { auto = it; MusicState.autoMode = it })
                        }
                        Text(if (auto) "自动模式：仅分析，暂不主动替换 QQ 音乐队列" else "推荐模式：仅分析，不干预播放")
                        Button(onClick = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("授予通知读取权限") }
                        Text("需要在系统设置中允许 Resonance 通知访问，然后打开 QQ 音乐播放歌曲。")
                        Text("QQ 音乐的收藏列表、相似歌曲搜索及指定歌曲播放尚未接入。")
                    }
                }
            }
        }
    }
}
