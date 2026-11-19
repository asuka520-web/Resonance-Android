package com.resonance.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    onPlayPause: () -> Unit, onExpand: () -> Unit, onFirstPattern: () -> Unit
) {
    val shape = Corners.plate
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = (maxWidth * .65f).coerceIn(Space.heroMinHeight, Space.heroMaxHeight)
        Box(Modifier.fillMaxWidth().height(height).clip(shape)
            .background(ResonancePalette.plate).border(Space.thin, ResonancePalette.line, shape)) {
            ArtworkTransition(cover, Modifier.matchParentSize(), shape, visible = visible)
            SandPlate(song?.mid.orEmpty(), Modifier.matchParentSize(), opacity = if (cover.image == null) .9f else .58f,
                animated = true, background = Color.Transparent, animation = SandAnimation(renderer, ticker, visible,
                    playing = playing, cold = cold, systemMotion = systemMotion, onFirstPattern = onFirstPattern,
                    priority = 2, sigmaDp = Motion.HomeSigmaDp))
            if (song != null) {
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    0f to ResonancePalette.ink.copy(alpha = .68f),
                    .38f to Color.Transparent,
                    1f to ResonancePalette.ink.copy(alpha = .94f)
                )))
                if (current) {
                    Row(Modifier.align(Alignment.TopStart).padding(start = Space.x4, top = Space.x3)
                        .clip(Corners.tag).background(ResonancePalette.ink.copy(alpha = .88f))
                        .padding(horizontal = Space.x2, vertical = Space.x1),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
                        Box(Modifier.size(Space.statusDot).background(ResonancePalette.signal, CircleShape))
                        ResonanceText(if (playing) "正在播放" else "已暂停", TypeSize.caption, color = ResonancePalette.artTitle)
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(top = Space.x1, end = Space.x2)) {
                        IconAction("展开完整播放器", onExpand, ResonancePalette.ink.copy(alpha = .88f), filled = true) {
                            Glyph(ResonanceGlyph.EXPAND, Modifier.size(Space.x5), ResonancePalette.artTitle)
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(Space.x4),
                    horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                        Text(song.name, style = resonanceTextStyle(TypeSize.song, FontWeight.ExtraBold, english = true),
                            color = ResonancePalette.artTitle, lineHeight = TypeSize.songLine, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        ResonanceText(song.artist, TypeSize.body, color = ResonancePalette.artTitle, english = true)
                    }
                    IconAction(if (current && playing) "暂停当前歌曲" else "播放当前歌曲",
                        onPlayPause, ResonancePalette.sand, filled = true) {
                        PlayPauseMorph(current && playing, Modifier.size(Space.x6))
                    }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            if (!connected) PrimaryButton("连接我的 QQ 音乐", onLogin, Modifier.weight(1f))
            else {
                if (song != null) OutlineButton("相似推荐", onSimilar, Modifier.weight(1f))
                OutlineButton("随心听", onSurprise, Modifier.weight(1f))
            }
        }
        if (connected) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
