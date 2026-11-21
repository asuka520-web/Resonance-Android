package com.resonance.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun GenreAnalysisPage(library: LibraryState, player: PlayerStatus, automatic: Boolean, onChange: (Boolean) -> Unit,
                               onBack: () -> Unit, onPlay: (QqCatalogueTrack) -> Unit, onDetails: (QqCatalogueTrack) -> Unit) {
    val context = LocalContext.current
    val revision by ModelGenres.revision.collectAsState()
    var search by remember { mutableStateOf("") }
    val songs = remember(library.favorites, library.discoveries) { library.allTracks }
    val entries = remember(library.accountScope, songs, library.genres, revision) {
        songs.associateWith { song ->
            ModelGenres.read(context, library.accountScope, song, library.genres[song.mid]?.labels.orEmpty().size >= 2)
        }
    }
    val matching = remember(songs, search) {
        val query = search.trim()
        songs.filter { it.name.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) }
    }
    val withResults = remember(entries) { entries.values.count { it.labels.isNotEmpty() } }
    val queued = remember(entries) { entries.values.count { it.requested } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.x5, vertical = Space.x3),
        verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        item { SectionHeader("曲风分析", onBack, "返回", actionColor = ResonancePalette.signal) }
        item {
            SettingSwitch("自动识别曲风", "优先分析曲风较少的歌曲。首次需要下载约 4 MB 的模型，歌曲在本机分析。", automatic, onChange)
        }
        if (player.modelGenreStatus.isNotBlank()) item {
            Text(player.modelGenreStatus, color = ResonancePalette.muted, fontSize = TypeSize.secondary)
        }
        item {
            SectionHeader("歌曲与结果", null, "")
            Text("有结果 $withResults 首 · 已安排 $queued 首", color = ResonancePalette.muted, fontSize = TypeSize.secondary)
        }
        if (songs.isNotEmpty()) item { SearchField(search, { search = it }, "搜索歌曲、歌手") }
        if (matching.isEmpty()) item {
            val text = when {
                library.accountScope.isBlank() -> "连接 QQ 音乐后可查看歌曲。"
                songs.isEmpty() -> "同步歌曲后可在这里查看曲风分析。"
                else -> "没有找到匹配的歌曲。"
            }
            Text(text, color = ResonancePalette.muted, fontSize = TypeSize.body)
        }
        items(matching, key = { it.mid }) { song ->
            val entry = entries.getValue(song)
            val status = if (player.modelGenreAnalyzingMid == song.mid) "正在分析" else entry.status
            TrackRow(song, status, song.mid == player.mid, { onPlay(song) }, { onDetails(song) },
                detailsButton = true, quietDetails = true) {
                if (entry.labels.isNotEmpty()) {
                    Text(entry.labels.joinToString(" · "), color = ResonancePalette.muted, fontSize = TypeSize.secondary)
                }
            }
            HorizontalDivider(color = ResonancePalette.line)
        }
    }
}

@Composable
internal fun GenreAnalysisDetail(song: QqCatalogueTrack, scope: String, player: PlayerStatus, hasCatalogueGenres: Boolean,
                                 onPlayFromStart: () -> Unit) {
    val context = LocalContext.current
    val revision by ModelGenres.revision.collectAsState()
    val entry = remember(scope, song, revision, hasCatalogueGenres) { ModelGenres.read(context, scope, song, hasCatalogueGenres) }
    val busy = player.modelGenreAnalyzingMid == song.mid
    val canPlay = scope.isNotBlank() && song.durationMs !in 1 until GenreSamplePlan.PRIMARY.endMs
    val status = when {
        busy -> "正在分析"
        player.mid == song.mid && player.excerpt -> "试听片段无法分析，请播放完整歌曲"
        else -> entry.status
    }
    SectionHeader("曲风 · 模型推测", null, "")
    Text(status, color = ResonancePalette.muted, fontSize = TypeSize.secondary)
    if (entry.labels.isNotEmpty()) {
        Text(entry.labels.joinToString(" · "), color = ResonancePalette.signal, fontSize = TypeSize.body)
        TextAction("移除此首的模型标签", { ModelGenres.dismiss(context, scope, song) }, ResonancePalette.muted)
    }
    OutlineButton("重新分析曲风", { ModelGenres.request(context, scope, song) }, Modifier.fillMaxWidth(),
        enabled = canPlay && !entry.requested && !busy)
    if (entry.requested && canPlay && !busy) {
        OutlineButton("从头播放", onPlayFromStart, Modifier.fillMaxWidth())
    }
}
