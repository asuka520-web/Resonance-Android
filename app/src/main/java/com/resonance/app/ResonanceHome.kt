package com.resonance.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

internal object HomePalette {
    val ink = Color(0xFF070B24)
    val surface = Color(0xFF0F1642)
    val line = Color(0xFF1E2866)
    val plate = Color(0xFF1A33D6)
    val sand = Color(0xFFF2EBDD)
    val signal = Color(0xFFFF6B3D)
    val muted = Color(0xFF8F9BD0)
}

internal data class PlaybackClock(val mid: String, val positionMs: Long, val listeningMinutes: Long)
internal fun PlayerStatus.clock() = PlaybackClock(mid, positionMs, listeningTimeMs / 60_000)
internal fun PlayerStatus.withoutClock() = copy(positionMs = 0, listeningTimeMs = 0)

private const val BricolageAsset = "fonts/BricolageGrotesque.ttf"
private val LocalBricolage = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

@Composable
internal fun HomeFonts(content: @Composable () -> Unit) {
    val assets = LocalContext.current.assets
    val family = remember(assets) {
        FontFamily(
            Font(BricolageAsset, assets, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
            Font(BricolageAsset, assets, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
            Font(BricolageAsset, assets, weight = FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
        )
    }
    CompositionLocalProvider(LocalBricolage provides family, content = content)
}

@Composable
private fun homeStyle(size: TextUnit, weight: FontWeight, english: Boolean) = TextStyle(
    fontFamily = if (english) LocalBricolage.current else FontFamily.SansSerif,
    fontSize = size,
    fontWeight = weight,
    fontFeatureSettings = "tnum"
)

@Composable
private fun HomeText(
    value: String, size: Int, modifier: Modifier = Modifier, color: Color = HomePalette.sand,
    weight: FontWeight = FontWeight.Medium, english: Boolean = false, lines: Int = 1
) {
    Text(value, modifier, color = color, style = homeStyle(size.sp, weight, english),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun HomeHeader(connected: Boolean, onAccount: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(24.dp)) {
            val heights = listOf(.19f, .55f, .35f, .69f)
            heights.forEachIndexed { index, height ->
                val x = size.width * (.14f + index * .21f)
                val half = size.height * height / 2
                drawLine(HomePalette.signal, Offset(x, center.y - half), Offset(x, center.y + half),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("Resonance", Modifier.weight(1f).padding(start = 9.dp), color = HomePalette.sand,
            style = homeStyle(22.sp, FontWeight.ExtraBold, english = true), letterSpacing = (-.02).em,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAccount),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(HomePalette.signal, CircleShape))
            HomeText(if (connected) "QQ 音乐已连接" else "连接 QQ 音乐", 12, color = HomePalette.muted, english = true)
        }
    }
}

@Composable
internal fun HomeHero(
    connected: Boolean, song: QqCatalogueTrack?, cover: ImageBitmap?,
    onLogin: () -> Unit, onSimilar: () -> Unit, onSurprise: () -> Unit, onIntent: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(350f / 224f), contentAlignment = Alignment.Center) {
            SandPlate(song?.mid.orEmpty(), Modifier.matchParentSize())
            if (cover != null) {
                val shape = RoundedCornerShape(18.dp)
                Image(cover, contentDescription = null, modifier = Modifier.size(96.dp).shadow(5.dp, shape).clip(shape),
                    contentScale = ContentScale.Crop)
            }
        }
        if (connected && song != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.name, style = homeStyle(26.sp, FontWeight.ExtraBold, english = true),
                    color = HomePalette.sand, lineHeight = 28.08.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                HomeText(song.artist, 14, color = HomePalette.muted, english = true)
            }
        } else {
            HomeText(if (connected) "收藏同步后可听相似推荐" else "从你的收藏开始", 18, lines = 2, weight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!connected) HomeButton("连接我的 QQ 音乐", onLogin, Modifier.weight(1f), solid = true)
            else {
                if (song != null) HomeButton("相似推荐", onSimilar, Modifier.weight(1f), solid = true)
                HomeButton("随心听", onSurprise, if (song == null) Modifier.weight(1f) else Modifier)
                HomeButton("调整这次选歌", onIntent)
            }
        }
    }
}

@Composable
internal fun HomeCatalogueStatus(syncing: Boolean, message: String, count: Int) {
    val reading = "正在自动整理曲风 · $count 首已有资料"
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(HomePalette.signal, CircleShape))
        if (syncing) HomeText(message, 12, color = HomePalette.muted, english = true, lines = 2)
        else HomeText("正在整理曲风，$count 首已有资料", 12,
            Modifier.clearAndSetSemantics { contentDescription = reading }, HomePalette.muted, english = true)
    }
}

@Composable
private fun HomeButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false) {
    val surface = if (solid) Modifier.background(HomePalette.signal) else Modifier.border(1.5.dp, HomePalette.sand, CircleShape)
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).then(surface)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        HomeText(label, 14, color = if (solid) HomePalette.ink else HomePalette.sand, weight = FontWeight.Bold, lines = 2)
    }
}

@Composable
private fun HomeTextAction(label: String, onClick: () -> Unit, color: Color, description: String = label) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        HomeText(label, 13, color = color, english = true)
    }
}

@Composable
internal fun HomeScoreCards(library: LibraryState, onAll: () -> Unit, onClear: () -> Unit) {
    val scores = remember(library.favorites, library.discoveries, library.genres, library.scoreSignals, library.manualScores) {
        library.genreScores().sortedByDescending { abs(it.combined) }
    }
    val maxAbs = (scores.firstOrNull()?.let { abs(it.combined) } ?: 0.0).coerceAtLeast(6.0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HomeText("标签分数", 22, Modifier.weight(1f), weight = FontWeight.Black)
            HomeTextAction("全部 ${scores.size} 项", onAll, HomePalette.signal, "查看全部 ${scores.size} 项")
            HomeTextAction("清除", onClear, HomePalette.muted, "清除当前所有分数")
        }
        if (scores.isEmpty()) HomeText("暂无曲风标签，取得资料后会在这里显示。", 12,
            color = HomePalette.muted, lines = 2)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val textWidth = ((maxWidth - 16.dp) / 3 - 24.dp).coerceAtLeast(1.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scores.take(3).forEach { score ->
                    ScoreCard(score, maxAbs, textWidth, Modifier.weight(1f).fillMaxHeight())
                }
                repeat(3 - scores.size.coerceAtMost(3)) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ScoreCard(score: GenreScore, maxAbs: Double, textWidth: Dp, modifier: Modifier) {
    val value = score.combined
    val scoreLabel = remember(value) { String.format(Locale.ROOT, "%+.1f", value).replace('-', '−') }
    val color = if (value < 0) HomePalette.signal else HomePalette.sand
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(HomePalette.surface)
        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HomeText(genreName(score.label), 12, color = HomePalette.muted, english = true, lines = 2)
        Box(Modifier.weight(1f))
        val style = homeStyle(30.sp, FontWeight.ExtraBold, english = true)
        val measurer = rememberTextMeasurer()
        val available = with(LocalDensity.current) { textWidth.toPx() }
        val width = measurer.measure(scoreLabel, style = style, maxLines = 1).size.width
        val adjustedSize = if (width > available) (30f * (available - 1).coerceAtLeast(1f) / width).sp else 30.sp
        Text(scoreLabel, color = color, style = style.copy(fontSize = adjustedSize), maxLines = 1, softWrap = false)
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val barHeight = 4.dp.toPx()
            val barY = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(HomePalette.line, Offset(0f, barY), Size(size.width, barHeight), radius)
            val length = (abs(value) / maxAbs).toFloat() * size.width / 2
            if (length > 0) drawRoundRect(color, Offset(center.x - if (value < 0) length else 0f, barY),
                Size(length, barHeight), radius)
            val tick = 2.dp.toPx()
            drawRect(HomePalette.muted, Offset(center.x - tick / 2, 0f), Size(tick, size.height))
        }
    }
}

@Composable
internal fun HomeRecommendationHeader(title: String, onAction: (() -> Unit)?, action: String,
    onPlayBatch: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeText(title, 22, Modifier.weight(1f), weight = FontWeight.Black)
        if (onAction != null) HomeTextAction(action, onAction, HomePalette.muted)
        if (onPlayBatch != null) HomeButton("播放这批", onPlayBatch)
    }
}

@Composable
internal fun HomeRecommendationReason(plan: StylePlan, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HomeText(plan.heading, 13, Modifier.weight(1f), HomePalette.muted, lines = 2)
            HomeTextAction("推荐理由", onToggle, HomePalette.sand)
        }
        if (expanded || plan.picks.isEmpty()) HomeText(plan.explanation, 12, color = HomePalette.muted, lines = Int.MAX_VALUE)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeTrackRow(
    song: QqCatalogueTrack, reason: String, current: Boolean, onPlay: () -> Unit, onDetails: () -> Unit,
    unfamiliar: Boolean = false
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 68.dp)
            .combinedClickable(role = Role.Button, onClick = onPlay, onLongClick = onDetails,
                onLongClickLabel = "${song.name}的歌曲详情"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cover = rememberCoverArtwork(song)
            if (cover != null) Image(cover, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            else SandPlate(song.mid, Modifier.size(56.dp), compact = true)
            Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                HomeText(song.name, 16, color = if (current) HomePalette.signal else HomePalette.sand, weight = FontWeight.Bold, english = true)
                HomeText(if (unfamiliar) "新歌候选 · ${song.artist}" else song.artist, 12,
                    color = HomePalette.muted, english = true)
                if (reason.isNotBlank()) HomeText(reason, 12, color = HomePalette.signal, english = true)
            }
        }
        HomeIconButton("播放 ${song.name}", onPlay, HomePalette.sand, outline = true) {
            Glyph(ResonanceGlyph.PLAY, Modifier.size(20.dp), HomePalette.sand)
        }
    }
}

private val homeDestinations = listOf("为你" to ResonanceGlyph.HOME, "发现" to ResonanceGlyph.SEARCH,
    "收藏" to ResonanceGlyph.LIBRARY, "我的" to ResonanceGlyph.PERSON)

@Composable
internal fun HomeBottomBar(
    tab: Int, song: QqCatalogueTrack?, player: PlayerStatus, clock: State<PlaybackClock>, cover: ImageBitmap?,
    onTab: (Int) -> Unit, onExpand: () -> Unit, onMode: (String) -> Unit, onToggle: () -> Unit, onNext: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp)
        .fillMaxWidth().clip(shape).background(HomePalette.surface).border(1.dp, HomePalette.line, shape)) {
        if (song != null) {
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val frame = clock.value
                val progress = if (frame.mid == song.mid && player.durationMs > 0)
                    (frame.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
                drawRect(HomePalette.line)
                drawRect(HomePalette.sand, size = Size(size.width * progress, size.height))
            }
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "展开播放器", onClick = onExpand),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val coverShape = RoundedCornerShape(12.dp)
                    if (cover != null) Image(cover, null, Modifier.size(42.dp).clip(coverShape), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(42.dp).clip(coverShape)) {
                        SandPlate(song.mid, Modifier.matchParentSize(), compact = true)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        HomeText(song.name, 14, weight = FontWeight.Bold, english = true)
                        val subtitle = when {
                            player.loading -> "正在连接音源…"
                            player.message.contains("无法") -> player.message
                            else -> song.artist
                        }
                        HomeText(subtitle, 12, color = HomePalette.muted, english = true)
                    }
                }
                val mode = PlaybackMode.parse(player.playbackMode)
                val modeColor = if (mode == PlaybackMode.SHUFFLE) HomePalette.signal else HomePalette.muted
                HomeIconButton("播放模式：${mode.label}，点按切换", { onMode(mode.next().name) }, modeColor) {
                    ShuffleGlyph(modeColor)
                }
                val playing = player.playing || (player.loading && player.playRequested)
                HomeIconButton("播放或暂停", onToggle, HomePalette.signal, filled = true) {
                    Glyph(if (playing) ResonanceGlyph.PAUSE else ResonanceGlyph.PLAY, Modifier.size(24.dp), HomePalette.ink)
                }
                HomeIconButton("下一首", onNext, HomePalette.sand) {
                    Glyph(ResonanceGlyph.NEXT, Modifier.size(22.dp), HomePalette.sand)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HomePalette.line))
        }
        Row(Modifier.fillMaxWidth().height(56.dp).selectableGroup()) {
            homeDestinations.forEachIndexed { index, (label, glyph) ->
                val selected = index == tab
                Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab, onClick = { onTab(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(width = 56.dp, height = 30.dp)
                        .background(if (selected) HomePalette.sand else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Glyph(glyph, Modifier.size(21.dp), if (selected) HomePalette.ink else HomePalette.muted)
                    }
                    HomeText(label, 12, color = if (selected) HomePalette.sand else HomePalette.muted,
                        weight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun HomeIconButton(label: String, onClick: () -> Unit, color: Color, filled: Boolean = false, outline: Boolean = false,
    content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val surface = when {
            filled -> Modifier.background(color, CircleShape)
            outline -> Modifier.border(1.5.dp, color, CircleShape)
            else -> Modifier
        }
        Box(Modifier.size(44.dp).then(surface), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ShuffleGlyph(color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .12f, h * .72f)
            cubicTo(w * .48f, h * .72f, w * .40f, h * .28f, w * .82f, h * .28f)
            moveTo(w * .12f, h * .28f)
            cubicTo(w * .26f, h * .28f, w * .31f, h * .32f, w * .37f, h * .40f)
            moveTo(w * .56f, h * .60f)
            cubicTo(w * .61f, h * .68f, w * .70f, h * .72f, w * .82f, h * .72f)
            for (y in listOf(.28f, .72f)) {
                moveTo(w * .70f, h * (y - .12f))
                lineTo(w * .84f, h * y)
                lineTo(w * .70f, h * (y + .12f))
            }
        }
        drawPath(path, color, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round))
    }
}
