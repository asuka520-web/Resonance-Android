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
internal fun HomeHeader(connected: Boolean, ticker: FrameTicker, logo: MotionFloat, visible: Boolean, onAccount: () -> Unit) {
    val animate = LocalResonanceMotion.current && visible
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(24.dp)) {
            val time = if (animate) ticker.time else 0L
            val envelope = if (animate) logo.value(time) else 0f
            for (index in 0..3) {
                val height = Motion.logoHeight(index, time / Motion.NanosPerMs.toFloat(), envelope)
                val x = size.width * (.14f + index * .21f)
                val half = size.height * height / 2
                drawLine(HomePalette.signal, Offset(x, center.y - half), Offset(x, center.y + half),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("Resonance", Modifier.weight(1f).padding(start = 9.dp), color = HomePalette.sand,
            style = homeStyle(22.sp, FontWeight.ExtraBold, english = true), letterSpacing = (-.02).em,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.heightIn(min = 48.dp).clickable(interaction, LocalIndication.current, role = Role.Button,
            onClick = onAccount).homePress(interaction),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(HomePalette.signal, CircleShape))
            HomeText(if (connected) "QQ 音乐已连接" else "连接 QQ 音乐", 12, color = HomePalette.muted, english = true)
        }
    }
}

@Composable
internal fun HomePlate(song: QqCatalogueTrack?, cover: ArtworkLoad, renderer: HomeSandRenderer, ticker: FrameTicker,
    visible: Boolean, playing: Boolean, cold: Boolean, systemMotion: Boolean,
    match: Float = Motion.DefaultMatch, onFirstPattern: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(350f / 224f), contentAlignment = Alignment.Center) {
        AnimatedSandPlate(song?.mid.orEmpty(), renderer, ticker, visible, playing, match, cold, systemMotion,
            onFirstPattern, Modifier.matchParentSize())
        val shape = RoundedCornerShape(18.dp)
        HomeArtwork(cover, Modifier.size(96.dp), shape, dropShadow = true, visible = visible)
    }
}

@Composable
internal fun HomeHero(
    connected: Boolean, song: QqCatalogueTrack?,
    onLogin: () -> Unit, onSimilar: () -> Unit, onSurprise: () -> Unit, onIntent: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun HomeArtwork(cover: ArtworkLoad, modifier: Modifier, shape: RoundedCornerShape,
    dropShadow: Boolean = false, visible: Boolean = true) {
    val animate = LocalResonanceMotion.current && visible
    var lastReady by remember { mutableStateOf(cover.image) }
    LaunchedEffect(cover) { if (!cover.pending) lastReady = cover.image }
    val image = if (animate && cover.pending) lastReady else cover.image
    val shadow = if (dropShadow) Modifier.shadow(5.dp, shape) else Modifier
    if (animate) {
        AnimatedContent(image, modifier = modifier, contentAlignment = Alignment.Center, transitionSpec = {
            val spec = tween<Float>(Motion.Cover, easing = Motion.easeInOut)
            (fadeIn(spec) + scaleIn(spec, Motion.CoverInitialScale)) togetherWith fadeOut(spec)
        }, label = "cover") { bitmap ->
            if (bitmap == null) Box(Modifier.fillMaxSize())
            else Image(bitmap, null, Modifier.fillMaxSize().then(shadow).clip(shape), contentScale = ContentScale.Crop)
        }
    } else if (image != null) {
        Image(image, null, modifier.then(shadow).clip(shape), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun homeAnimatedValue(target: Float, duration: Int, easing: Easing, label: String): State<Float> =
    if (LocalResonanceMotion.current) animateFloatAsState(target, tween(duration, easing = easing), label = label)
    else rememberUpdatedState(target)

@Composable
private fun Modifier.homePress(interaction: MutableInteractionSource): Modifier {
    if (!LocalResonanceMotion.current) return this
    val pressed by interaction.collectIsPressedAsState()
    val scale = homeAnimatedValue(if (pressed) Motion.PressScale else 1f, if (pressed) Motion.Press else Motion.Release,
        if (pressed) Motion.accelerate else Motion.release, "button press")
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scaleX
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
    val interaction = remember { MutableInteractionSource() }
    val surface = if (solid) Modifier.background(HomePalette.signal) else Modifier.border(1.5.dp, HomePalette.sand, CircleShape)
    Box(modifier.heightIn(min = 48.dp).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .homePress(interaction).clip(CircleShape).then(surface).padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        HomeText(label, 14, color = if (solid) HomePalette.ink else HomePalette.sand, weight = FontWeight.Bold, lines = 2)
    }
}

@Composable
private fun HomeTextAction(label: String, onClick: () -> Unit, color: Color, description: String = label) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick).homePress(interaction)
        .semantics { contentDescription = description }.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        HomeText(label, 13, color = color, english = true)
    }
}

@Composable
internal fun HomeScoreCards(scores: List<GenreScore>, ticker: FrameTicker, systemMotion: Boolean,
    onAll: () -> Unit, onClear: () -> Unit) {
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
private fun ScoreCard(score: GenreScore, maxAbs: Double, textWidth: Dp, ticker: FrameTicker,
    systemMotion: Boolean, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(HomePalette.surface)
        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HomeText(genreName(score.label), 12, color = HomePalette.muted, english = true, lines = 2)
        Box(Modifier.weight(1f))
        val number = scoreMotion(score.combined.toFloat(), Motion.Score, Motion.settle, ticker, systemMotion)
        ScoreNumber(number, maxAbs, textWidth)
        val bar = scoreMotion((score.combined / maxAbs).toFloat(), Motion.ScoreBar, Motion.responsive, ticker, systemMotion)
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val fraction = bar.value
            val color = if (fraction < 0f) HomePalette.signal else HomePalette.sand
            val barHeight = 4.dp.toPx()
            val barY = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(HomePalette.line, Offset(0f, barY), Size(size.width, barHeight), radius)
            val length = abs(fraction) * size.width / 2
            if (length > 0) drawRoundRect(color, Offset(center.x - if (fraction < 0f) length else 0f, barY),
                Size(length, barHeight), radius)
            val tick = 2.dp.toPx()
            drawRect(HomePalette.muted, Offset(center.x - tick / 2, 0f), Size(tick, size.height))
        }
    }
}

@Composable
private fun scoreMotion(target: Float, duration: Int, easing: Easing, ticker: FrameTicker, enabled: Boolean): State<Float> {
    val motion = remember { MotionFloat(target) }
    LaunchedEffect(target, ticker, enabled) { motion.update(target, duration, easing, ticker, enabled) }
    return remember(target, ticker, enabled) { derivedStateOf { if (enabled) motion.value(ticker.time) else target } }
}

@Composable
private fun ScoreNumber(value: State<Float>, maxAbs: Double, textWidth: Dp) {
    val tenths by remember(value) { derivedStateOf { (value.value * 10).roundToInt() } }
    val scoreLabel = remember(tenths) { String.format(Locale.ROOT, "%+.1f", tenths / 10f).replace('-', '−') }
    val style = homeStyle(30.sp, FontWeight.ExtraBold, english = true)
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { textWidth.toPx() }
    val width = remember(measurer, style, maxAbs) {
        maxOf(measurer.measure(String.format(Locale.ROOT, "−%.1f", maxAbs), style = style, maxLines = 1).size.width,
            measurer.measure(String.format(Locale.ROOT, "+%.1f", maxAbs), style = style, maxLines = 1).size.width)
    }
    val adjustedSize = if (width > available) (30f * (available - 1).coerceAtLeast(1f) / width).sp else 30.sp
    Text(scoreLabel, color = if (tenths < 0) HomePalette.signal else HomePalette.sand,
        style = style.copy(fontSize = adjustedSize), maxLines = 1, softWrap = false)
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
    unfamiliar: Boolean = false, ticker: FrameTicker? = null, batch: BatchSandMotion? = null, row: Int = 0
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 68.dp)
            .combinedClickable(role = Role.Button, onClick = onPlay, onLongClick = onDetails,
                onLongClickLabel = "${song.name}的歌曲详情"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cover = rememberArtworkLoad(song)
            Box(Modifier.size(56.dp)) {
                if (cover.image == null) SandPlate(song.mid, Modifier.matchParentSize(), ticker = ticker, batch = batch, row = row)
                HomeArtwork(cover, Modifier.matchParentSize(), RoundedCornerShape(14.dp))
            }
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
    ticker: FrameTicker, onTab: (Int) -> Unit, onExpand: () -> Unit, onMode: (String) -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val animate = LocalResonanceMotion.current
    val playhead = remember { SmoothPlayhead() }
    Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp)
        .fillMaxWidth().clip(shape).background(HomePalette.surface).border(1.dp, HomePalette.line, shape)) {
        if (song != null) {
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val frame = clock.value
                val advancing = tab == 0 && animate && player.playing
                val progress = if (frame.mid == song.mid && player.durationMs > 0)
                    (playhead.position(song.mid, frame.positionMs, if (advancing) ticker.time else 0L, advancing) /
                        player.durationMs).coerceIn(0f, 1f) else 0f
                drawRect(HomePalette.line)
                drawRect(HomePalette.sand, size = Size(size.width * progress, size.height))
            }
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val openInteraction = remember { MutableInteractionSource() }
                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(openInteraction, LocalIndication.current, onClickLabel = "展开播放器", onClick = onExpand)
                    .homePress(openInteraction),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val coverShape = RoundedCornerShape(12.dp)
                    if (cover != null) Image(cover, null, Modifier.size(42.dp).clip(coverShape), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(42.dp).clip(coverShape)) {
                        SandPlate(song.mid, Modifier.matchParentSize())
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
                    PlayPauseMorph(playing)
                }
                HomeIconButton("下一首", onNext, HomePalette.sand) {
                    Glyph(ResonanceGlyph.NEXT, Modifier.size(22.dp), HomePalette.sand)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(HomePalette.line))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val slotWidth = maxWidth / homeDestinations.size
            val selection = homeAnimatedValue(tab.toFloat(), Motion.Navigation, Motion.responsive, "navigation")
            Box(Modifier.offset {
                val position = if (animate) selection.value else tab.toFloat()
                IntOffset((slotWidth * (position + .5f) - 28.dp).roundToPx(), 5.dp.roundToPx())
            }.size(width = 56.dp, height = 30.dp).background(HomePalette.sand, CircleShape))
            Row(Modifier.matchParentSize().selectableGroup()) {
                homeDestinations.forEachIndexed { index, (label, glyph) ->
                    val selected = index == tab
                    val interaction = remember { MutableInteractionSource() }
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, interaction, LocalIndication.current,
                        role = Role.Tab, onClick = { onTab(index) }).homePress(interaction),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 5.dp).size(width = 56.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                            Glyph(glyph, Modifier.size(21.dp), if (selected) HomePalette.ink else HomePalette.muted)
                        }
                        HomeText(label, 12, color = if (selected) HomePalette.sand else HomePalette.muted,
                            weight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeIconButton(label: String, onClick: () -> Unit, color: Color, filled: Boolean = false, outline: Boolean = false,
    content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val surface = when {
            filled -> Modifier.background(color, CircleShape)
            outline -> Modifier.border(1.5.dp, color, CircleShape)
            else -> Modifier
        }
        Box(Modifier.size(44.dp).homePress(interaction).then(surface), contentAlignment = Alignment.Center) { content() }
    }
}

// The play triangle is split into two polygons so its silhouette can become two pause bars.
private val playOutline = floatArrayOf(.32f,.20f, .49f,.306f, .49f,.694f, .32f,.80f,
    .49f,.306f, .80f,.50f, .80f,.50f, .49f,.694f)
private val pauseOutline = floatArrayOf(.25f,.20f, .41f,.20f, .41f,.80f, .25f,.80f,
    .59f,.20f, .75f,.20f, .75f,.80f, .59f,.80f)

@Composable
private fun PlayPauseMorph(playing: Boolean) {
    val animate = LocalResonanceMotion.current
    val target = if (playing) 1f else 0f
    val phase = homeAnimatedValue(target, Motion.PlayMorph, Motion.easeInOut, "play pause")
    Canvas(Modifier.size(24.dp).drawWithCache {
        val path = Path()
        onDrawBehind {
            val t = if (animate) phase.value else target
            path.reset()
            for (index in playOutline.indices step 2) {
                val x = size.width * Motion.mix(playOutline[index], pauseOutline[index], t)
                val y = size.height * Motion.mix(playOutline[index + 1], pauseOutline[index + 1], t)
                if (index % 8 == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (index % 8 == 6) path.close()
            }
            drawPath(path, HomePalette.ink)
        }
    }) {}
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
