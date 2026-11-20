package com.resonance.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs

internal data class PlaybackClock(val mid: String, val positionMs: Long, val listeningMinutes: Long)
internal fun PlayerStatus.clock() = PlaybackClock(mid, positionMs, listeningTimeMs / 60_000)
internal fun PlayerStatus.withoutClock() = copy(positionMs = 0, listeningTimeMs = 0)

@Composable
internal fun HomePlate(
    song: QqCatalogueTrack?, cover: ArtworkLoad, renderer: HomeSandRenderer, ticker: FrameTicker,
    visible: Boolean, cold: Boolean, systemMotion: Boolean, playing: Boolean, current: Boolean,
    onPlayPause: () -> Unit, onExpand: () -> Unit, onSearch: () -> Unit, onFirstPattern: () -> Unit,
    artworkSlot: @Composable (@Composable () -> Unit) -> Unit = { it() }
) {
    val shape = Corners.plate
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        if (song != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                    Box(Modifier.size(Space.statusDot).background(ResonancePalette.signal, CircleShape))
                    ResonanceText(if (!current) "待播放" else if (playing) "正在播放" else "已暂停", TypeSize.secondary,
                        color = ResonancePalette.muted)
                }
                IconAction("搜索歌曲", onSearch, ResonancePalette.muted) {
                    Glyph(ResonanceGlyph.SEARCH, Modifier.size(Space.x5), ResonancePalette.muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                    Text(song.name, style = resonanceTextStyle(TypeSize.deckTitle, FontWeight.Bold, english = true),
                        color = ResonancePalette.artTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ResonanceText(song.artist, TypeSize.body, color = ResonancePalette.muted, english = true)
                }
                IconAction(if (current && playing) "暂停当前歌曲" else "播放当前歌曲",
                    onPlayPause, ResonancePalette.sand, filled = true, diameter = Space.touch) {
                    PlayPauseMorph(current && playing, Modifier.size(Space.x6))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = (maxWidth * .55f).coerceIn(Space.heroMinHeight, Space.heroMaxHeight)
            Row(Modifier.fillMaxWidth().height(height).clip(shape).background(ResonancePalette.plate)
                .border(Space.thin, ResonancePalette.line, shape)) {
                if (song != null) Box(Modifier.weight(1.24f).fillMaxHeight().background(ResonancePalette.surface)) {
                    artworkSlot {
                        NeedleArtwork(song.mid, cover, renderer, ticker, Modifier.fillMaxSize(), RectangleShape,
                            visible = visible)
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(Brush.radialGradient(
                    listOf(ResonancePalette.fieldCenter, ResonancePalette.fieldOuter)))) {
                    Canvas(Modifier.matchParentSize()) {
                        val rule = ResonancePalette.signal.copy(alpha = .20f)
                        repeat(4) { step ->
                            drawCircle(rule, size.minDimension * (.13f + .13f * step), style = Stroke(Space.thin.toPx()))
                        }
                        drawLine(rule, Offset(center.x, 0f), Offset(center.x, size.height), Space.thin.toPx())
                        drawLine(rule, Offset(0f, center.y), Offset(size.width, center.y), Space.thin.toPx())
                    }
                    SandPlate(song?.mid.orEmpty(), Modifier.matchParentSize(), opacity = .76f, animated = true,
                        background = Color.Transparent, animation = SandAnimation(renderer, ticker, visible,
                            playing = playing, cold = cold, systemMotion = systemMotion, onFirstPattern = onFirstPattern,
                            priority = 2, sigmaDp = Motion.HomeSigmaDp, needleOrigin = Offset(.91f, .04f)))
                    if (current) Box(Modifier.align(Alignment.TopEnd).padding(Space.x1)) {
                        IconAction("展开完整播放器", onExpand, ResonancePalette.ink, filled = true) {
                            Glyph(ResonanceGlyph.EXPAND, Modifier.size(Space.x5), ResonancePalette.artTitle)
                        }
                    }
                    ResonanceText("共振波面", TypeSize.caption,
                        Modifier.align(Alignment.BottomEnd).padding(end = Space.x3, bottom = Space.x3),
                        color = ResonancePalette.sand)
                }
            }
        }
    }
}

@Composable
internal fun HomeHero(
    connected: Boolean, song: QqCatalogueTrack?,
    onLogin: () -> Unit, onSimilar: () -> Unit, onSurprise: () -> Unit, onIntent: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = Space.x1), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        if (song == null) {
            ResonanceText(if (connected) "收藏同步后可听相似推荐" else "从你的收藏开始", TypeSize.lead,
                lines = 2, weight = FontWeight.Bold)
        }
        if (!connected) PrimaryButton("连接我的 QQ 音乐", onLogin, Modifier.fillMaxWidth())
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (song != null) TextAction("相似推荐", onSimilar, ResonancePalette.sand)
            TextAction("随心听", onSurprise, ResonancePalette.muted)
            TextAction("调整这次选歌", onIntent, ResonancePalette.muted)
        }
    }
}

@Composable
internal fun HomeCatalogueStatus(syncing: Boolean, message: String, count: Int) {
    val reading = "正在自动整理曲风 · $count 首已有资料"
    Row(Modifier.fillMaxWidth().padding(vertical = Space.opticalInset), horizontalArrangement = Arrangement.spacedBy(Space.x2),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Space.statusDot).background(ResonancePalette.signal, CircleShape))
        if (syncing) ResonanceText(message, TypeSize.secondary, color = ResonancePalette.muted, english = true, lines = 2)
        else ResonanceText("正在整理曲风，$count 首已有资料", TypeSize.secondary,
            Modifier.clearAndSetSemantics { contentDescription = reading }, ResonancePalette.muted, english = true)
    }
}

@Composable
internal fun HomeScoreCards(scores: List<GenreScore>, ticker: FrameTicker, systemMotion: Boolean,
    onAll: () -> Unit, onClear: () -> Unit) {
    val maxAbs = (scores.firstOrNull()?.let { abs(it.combined) } ?: 0.0).coerceAtLeast(6.0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResonanceText("标签分数", TypeSize.title, Modifier.weight(1f), weight = FontWeight.Black)
            TextAction("全部 ${scores.size} 项", onAll, ResonancePalette.signal, "查看全部 ${scores.size} 项")
            TextAction("清除", onClear, ResonancePalette.muted, "清除当前所有分数")
        }
        if (scores.isEmpty()) ResonanceText("暂无曲风标签，取得资料后会在这里显示。", TypeSize.secondary,
            color = ResonancePalette.muted, lines = 2)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val textWidth = ((maxWidth - Space.x4) / 3 - Space.x6).coerceAtLeast(Space.thin)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                scores.take(3).forEach { score ->
                    androidx.compose.runtime.key(score.label) {
                        ScoreCard(score, maxAbs, textWidth, ticker, systemMotion, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                repeat(3 - scores.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun HomeRecommendationReason(plan: StylePlan, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResonanceText(plan.heading, TypeSize.action, Modifier.weight(1f), ResonancePalette.muted, lines = 2)
            TextAction("推荐理由", onToggle, ResonancePalette.sand)
        }
        if (expanded || plan.picks.isEmpty()) ResonanceText(plan.explanation, TypeSize.secondary, color = ResonancePalette.muted, lines = Int.MAX_VALUE)
    }
}
