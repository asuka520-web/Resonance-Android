package com.resonance.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagScores(library: LibraryState, onClear: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var showCalculation by remember { mutableStateOf(false) }
    val scores = remember(library.favorites, library.discoveries, library.genres, library.scoreSignals, library.manualScores) {
        library.genreScores().sortedByDescending { abs(it.combined) }
    }
    val shown = remember(scores, query, filter) {
        scores.filter { row ->
            val matchingName = query.isBlank() || row.label.contains(query.trim(), true) || genreName(row.label).contains(query.trim(), true)
            val matchingSign = when (filter) { 1 -> row.combined > 0; 2 -> row.combined < 0; 3 -> row.combined == 0.0; else -> true }
            matchingName && matchingSign
        }
    }
    val ticker = remember { FrameTicker() }
    val animated = LocalResonanceMotion.current
    RunResonanceFrames(ticker, animated, continuous = false, drawSand = false)
    SearchField(query, { query = it }, "搜索标签")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
        listOf("全部", "正分", "负分", "零分").forEachIndexed { index, title ->
            ResonanceChip(title, filter == index) { filter = index }
        }
    }
    Text("综合分越高，随机播放和推荐时越容易选到该曲风。", fontSize = TypeSize.secondary, color = ResonancePalette.muted)
    Text("近期 / 长期 / 综合", fontSize = TypeSize.caption, color = ResonancePalette.muted)
    val maxAbs = (scores.firstOrNull()?.let { abs(it.combined) } ?: 0.0).coerceAtLeast(6.0)
    if (scores.isEmpty()) EmptyState("暂没有可用曲风标签。")
    else if (shown.isEmpty()) EmptyState("暂无符合条件的标签。", "全部", { query = ""; filter = 0 })
    shown.forEach { row ->
        key(row.label) {
            val value = scoreMotion(row.combined.toFloat(), Motion.Score, Motion.settle, ticker, animated)
            val bar = scoreMotion((row.combined / maxAbs).toFloat(), Motion.ScoreBar, Motion.responsive, ticker, animated)
            val info = "${row.observations} 次记录" + if (row.manual != 0.0) " · 主动 ${scoreDisplay(row.manual.toFloat())}" else ""
            val previousScores = listOf(row.recent, row.longTerm, row.combined)
                .joinToString(" / ") { String.format(java.util.Locale.ROOT, "%+.1f", it) }
            ScoreRow(genreName(row.label), scoreDisplay(value.value), bar, detail = info)
            Text(previousScores, fontSize = TypeSize.caption, color = ResonancePalette.muted)
            HorizontalDivider(color = ResonancePalette.line)
        }
    }
    TextAction(if (showCalculation) "收起计分说明" else "分数怎么算", { showCalculation = !showCalculation }, ResonancePalette.signal)
    if (showCalculation) Text("听完 +2，中途切歌 −1，有效快跳 −3。多标签平分反馈。综合分包含最近 20 条的 70%、最近 500 条归一到 20 条后的 30%，再加主动反馈。",
        fontSize = TypeSize.secondary, color = ResonancePalette.muted)
    TextAction("清除当前所有分数", onClear, ResonancePalette.muted)
}

@Composable
internal fun ClearScoresDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ResonanceDialog("清除当前所有分数？", onDismiss) {
        val ticker = remember { FrameTicker() }
        val animate = LocalResonanceMotion.current
        val framePermission = rememberUpdatedState(animate)
        var burst by remember { mutableStateOf<Long?>(null) }
        RunResonanceFrames(ticker, animate, continuous = false, drawSand = false)
        LaunchedEffect(burst) {
            if (burst != null) {
                ticker.await(Motion.BatchExit, framePermission)
                onDismiss()
            }
        }
        SandPlate("clear-scores", Modifier.size(Space.listCover).align(Alignment.CenterHorizontally),
            ticker = ticker, fadeStartedAt = burst, density = .25f)
        Text("当前账号的全部标签分数归零，之后按新的听歌反馈积累。保留收藏、曲风、听歌记录和陪伴时长；正在播放的歌曲继续播放。",
            fontSize = TypeSize.body, color = ResonancePalette.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextAction("取消", onDismiss, ResonancePalette.muted)
            PrimaryButton("确认清零", {
                if (burst == null) {
                    onConfirm()
                    if (animate) burst = ticker.time else onDismiss()
                }
            })
        }
    }
}
