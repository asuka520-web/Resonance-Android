package com.resonance.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PlaybackClock(val mid: String, val positionMs: Long, val listeningMinutes: Long)
internal fun PlayerStatus.clock() = PlaybackClock(mid, positionMs, listeningTimeMs / 60_000)
internal fun PlayerStatus.withoutClock() = copy(positionMs = 0, listeningTimeMs = 0)

@Composable
internal fun HomePlate(song: QqCatalogueTrack?, cover: ArtworkLoad, renderer: HomeSandRenderer, ticker: FrameTicker,
    visible: Boolean, playing: Boolean, cold: Boolean, systemMotion: Boolean,
    match: Float = Motion.DefaultMatch, onFirstPattern: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(350f / 224f), contentAlignment = Alignment.Center) {
        SandPlate(song?.mid.orEmpty(), Modifier.matchParentSize(), animation =
            SandAnimation(renderer, ticker, visible, playing, match, cold, systemMotion, onFirstPattern))
        val shape = Corners.card
        ArtworkTransition(cover, Modifier.size(Space.heroCover), shape, dropShadow = true, visible = visible)
    }
}

@Composable
internal fun HomeHero(
    connected: Boolean, song: QqCatalogueTrack?,
    onLogin: () -> Unit, onSimilar: () -> Unit, onSurprise: () -> Unit, onIntent: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = Space.x1), verticalArrangement = Arrangement.spacedBy(Space.x3)) {
        if (connected && song != null) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                Text(song.name, style = resonanceTextStyle(TypeSize.song, FontWeight.ExtraBold, english = true),
                    color = ResonancePalette.sand, lineHeight = TypeSize.songLine, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ResonanceText(song.artist, TypeSize.body, color = ResonancePalette.muted, english = true)
            }
        } else {
            ResonanceText(if (connected) "收藏同步后可听相似推荐" else "从你的收藏开始", TypeSize.lead, lines = 2, weight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            if (!connected) PrimaryButton("连接我的 QQ 音乐", onLogin, Modifier.weight(1f))
            else {
                if (song != null) PrimaryButton("相似推荐", onSimilar, Modifier.weight(1f))
                OutlineButton("随心听", onSurprise, if (song == null) Modifier.weight(1f) else Modifier)
                OutlineButton("调整这次选歌", onIntent)
            }
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
