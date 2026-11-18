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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.semantics.liveRegion
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

@Composable
internal fun resonanceTextStyle(size: TextUnit, weight: FontWeight, english: Boolean) = TextStyle(
    fontFamily = if (english) LocalBricolage.current else FontFamily.SansSerif,
    fontSize = size,
    fontWeight = weight,
    fontFeatureSettings = "tnum"
)

@Composable
internal fun ResonanceText(
    value: String, size: Int, modifier: Modifier = Modifier, color: Color = ResonancePalette.sand,
    weight: FontWeight = FontWeight.Medium, english: Boolean = false, lines: Int = 1
) {
    Text(value, modifier, color = color, style = resonanceTextStyle(size.sp, weight, english),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun TopBar(connected: Boolean, ticker: FrameTicker, logo: MotionFloat, visible: Boolean, onAccount: () -> Unit) {
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
                drawLine(ResonancePalette.signal, Offset(x, center.y - half), Offset(x, center.y + half),
                    strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("Resonance", Modifier.weight(1f).padding(start = 9.dp), color = ResonancePalette.sand,
            style = resonanceTextStyle(22.sp, FontWeight.ExtraBold, english = true), letterSpacing = (-.02).em,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.heightIn(min = 48.dp).clickable(interaction, LocalIndication.current, role = Role.Button,
            onClick = onAccount).resonancePress(interaction),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(ResonancePalette.signal, CircleShape))
            ResonanceText(if (connected) "QQ 音乐已连接" else "连接 QQ 音乐", 12, color = ResonancePalette.muted, english = true)
        }
    }
}


@Composable
internal fun ArtworkTransition(cover: ArtworkLoad, modifier: Modifier, shape: RoundedCornerShape,
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
internal fun resonanceAnimatedValue(target: Float, duration: Int, easing: Easing, label: String): State<Float> =
    if (LocalResonanceMotion.current) animateFloatAsState(target, tween(duration, easing = easing), label = label)
    else rememberUpdatedState(target)

@Composable
internal fun Modifier.resonancePress(interaction: MutableInteractionSource): Modifier {
    if (!LocalResonanceMotion.current) return this
    val pressed by interaction.collectIsPressedAsState()
    val scale = resonanceAnimatedValue(if (pressed) Motion.PressScale else 1f, if (pressed) Motion.Press else Motion.Release,
        if (pressed) Motion.accelerate else Motion.release, "button press")
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scaleX
    }
}


@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier, solid: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val surface = if (solid) Modifier.background(ResonancePalette.signal) else Modifier.border(1.5.dp, ResonancePalette.sand, CircleShape)
    Box(modifier.heightIn(min = 48.dp).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .resonancePress(interaction).clip(CircleShape).then(surface).padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        ResonanceText(label, 14, color = if (solid) ResonancePalette.ink else ResonancePalette.sand, weight = FontWeight.Bold, lines = 2)
    }
}

@Composable
internal fun TextAction(label: String, onClick: () -> Unit, color: Color, description: String = label) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick).resonancePress(interaction)
        .semantics { contentDescription = description }.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        ResonanceText(label, 13, color = color, english = true)
    }
}


@Composable
internal fun ScoreCard(score: GenreScore, maxAbs: Double, textWidth: Dp, ticker: FrameTicker,
    systemMotion: Boolean, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(ResonancePalette.surface)
        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ResonanceText(genreName(score.label), 12, color = ResonancePalette.muted, english = true, lines = 2)
        Box(Modifier.weight(1f))
        val number = scoreMotion(score.combined.toFloat(), Motion.Score, Motion.settle, ticker, systemMotion)
        ScoreNumber(number, maxAbs, textWidth)
        val bar = scoreMotion((score.combined / maxAbs).toFloat(), Motion.ScoreBar, Motion.responsive, ticker, systemMotion)
        SignedScoreBar(bar)
    }
}

@Composable
internal fun SignedScoreBar(bar: State<Float>, modifier: Modifier = Modifier) {
        Canvas(modifier.fillMaxWidth().height(10.dp)) {
            val fraction = bar.value
            val color = if (fraction < 0f) ResonancePalette.signal else ResonancePalette.sand
            val barHeight = 4.dp.toPx()
            val barY = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(ResonancePalette.line, Offset(0f, barY), Size(size.width, barHeight), radius)
            val length = abs(fraction) * size.width / 2
            if (length > 0) drawRoundRect(color, Offset(center.x - if (fraction < 0f) length else 0f, barY),
                Size(length, barHeight), radius)
            val tick = 2.dp.toPx()
            drawRect(ResonancePalette.muted, Offset(center.x - tick / 2, 0f), Size(tick, size.height))
        }
}

@Composable
internal fun ScoreRow(label: String, value: String, fraction: State<Float>,
    modifier: Modifier = Modifier, detail: String = "", trailing: @Composable (() -> Unit)? = null,
    bar: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = TypeSize.body, fontWeight = FontWeight.Bold)
                if (detail.isNotBlank()) Text(detail, fontSize = TypeSize.caption, color = ResonancePalette.muted)
            }
            Text(value, color = if (value.startsWith("−") || value.startsWith("-")) ResonancePalette.signal else ResonancePalette.sand,
                style = resonanceTextStyle(TypeSize.track, FontWeight.Bold, true))
            trailing?.invoke()
        }
        if (bar == null) SignedScoreBar(fraction) else bar()
    }
}

@Composable
internal fun scoreMotion(target: Float, duration: Int, easing: Easing, ticker: FrameTicker, enabled: Boolean): State<Float> {
    val motion = remember { MotionFloat(target) }
    LaunchedEffect(target, ticker, enabled) { motion.update(target, duration, easing, ticker, enabled) }
    return remember(target, ticker, enabled) { derivedStateOf { if (enabled) motion.value(ticker.time) else target } }
}

@Composable
private fun ScoreNumber(value: State<Float>, maxAbs: Double, textWidth: Dp) {
    val tenths by remember(value) { derivedStateOf { (value.value * 10).roundToInt() } }
    val scoreLabel = remember(tenths) { String.format(Locale.ROOT, "%+.1f", tenths / 10f).replace('-', '−') }
    val style = resonanceTextStyle(30.sp, FontWeight.ExtraBold, english = true)
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { textWidth.toPx() }
    val width = remember(measurer, style, maxAbs) {
        maxOf(measurer.measure(String.format(Locale.ROOT, "−%.1f", maxAbs), style = style, maxLines = 1).size.width,
            measurer.measure(String.format(Locale.ROOT, "+%.1f", maxAbs), style = style, maxLines = 1).size.width)
    }
    val adjustedSize = if (width > available) (30f * (available - 1).coerceAtLeast(1f) / width).sp else 30.sp
    Text(scoreLabel, color = if (tenths < 0) ResonancePalette.signal else ResonancePalette.sand,
        style = style.copy(fontSize = adjustedSize), maxLines = 1, softWrap = false)
}


@Composable
internal fun SectionHeader(title: String, onAction: (() -> Unit)?, action: String,
    onPlayBatch: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResonanceText(title, 22, Modifier.weight(1f), weight = FontWeight.Black)
        if (onAction != null) TextAction(action, onAction, ResonancePalette.muted)
        if (onPlayBatch != null) OutlineButton("播放这批", onPlayBatch)
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    song: QqCatalogueTrack, reason: String, current: Boolean, onPlay: () -> Unit, onDetails: () -> Unit,
    unfamiliar: Boolean = false, ticker: FrameTicker? = null, batch: BatchSandMotion? = null, row: Int = 0,
    subtitle: String = "", detailsButton: Boolean = false,
    supporting: @Composable ColumnScope.() -> Unit = {}
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
                ArtworkTransition(cover, Modifier.matchParentSize(), RoundedCornerShape(14.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                ResonanceText(song.name, 16, color = if (current) ResonancePalette.signal else ResonancePalette.sand, weight = FontWeight.Bold, english = true)
                ResonanceText(subtitle.ifBlank { if (unfamiliar) "新歌候选 · ${song.artist}" else song.artist }, 12,
                    color = ResonancePalette.muted, english = true)
                supporting()
                if (reason.isNotBlank()) ResonanceText(reason, 12, color = ResonancePalette.signal,
                    english = true, lines = if (detailsButton) 3 else 1)
            }
        }
        if (detailsButton) {
            IconAction("${song.name}的歌曲详情", onDetails, ResonancePalette.sand, outline = true) {
                Glyph(ResonanceGlyph.DETAILS, Modifier.size(Space.x4), ResonancePalette.sand)
            }
        } else IconAction("播放 ${song.name}", onPlay, ResonancePalette.sand, outline = true) {
            Glyph(ResonanceGlyph.PLAY, Modifier.size(Space.x5), ResonancePalette.sand)
        }
    }
}

private val homeDestinations = listOf("为你" to ResonanceGlyph.HOME, "发现" to ResonanceGlyph.SEARCH,
    "收藏" to ResonanceGlyph.LIBRARY, "我的" to ResonanceGlyph.PERSON)

@Composable
internal fun Dock(
    tab: Int, song: QqCatalogueTrack?, player: PlayerStatus, clock: State<PlaybackClock>, cover: ImageBitmap?,
    ticker: FrameTicker, onTab: (Int) -> Unit, onExpand: () -> Unit, onMode: (String) -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val animate = LocalResonanceMotion.current
    val playhead = remember { SmoothPlayhead() }
    Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp)
        .fillMaxWidth().clip(shape).background(ResonancePalette.surface).border(1.dp, ResonancePalette.line, shape)) {
        if (song != null) {
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val frame = clock.value
                val advancing = animate && player.playing
                val progress = if (frame.mid == song.mid && player.durationMs > 0)
                    (playhead.position(song.mid, frame.positionMs, if (advancing) ticker.time else 0L, advancing) /
                        player.durationMs).coerceIn(0f, 1f) else 0f
                drawRect(ResonancePalette.line)
                drawRect(ResonancePalette.sand, size = Size(size.width * progress, size.height))
            }
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val openInteraction = remember { MutableInteractionSource() }
                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(openInteraction, LocalIndication.current, onClickLabel = "展开播放器", onClick = onExpand)
                    .resonancePress(openInteraction),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val coverShape = RoundedCornerShape(12.dp)
                    if (cover != null) Image(cover, null, Modifier.size(42.dp).clip(coverShape), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(42.dp).clip(coverShape)) {
                        SandPlate(song.mid, Modifier.matchParentSize())
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        ResonanceText(song.name, 14, weight = FontWeight.Bold, english = true)
                        val subtitle = when {
                            player.loading -> "正在连接音源…"
                            player.message.contains("无法") -> player.message
                            else -> song.artist
                        }
                        ResonanceText(subtitle, 12, color = ResonancePalette.muted, english = true)
                    }
                }
                val mode = PlaybackMode.parse(player.playbackMode)
                val modeColor = if (mode == PlaybackMode.SHUFFLE) ResonancePalette.signal else ResonancePalette.muted
                IconAction("播放模式：${mode.label}，点按切换", { onMode(mode.next().name) }, modeColor) {
                    ShuffleGlyph(modeColor)
                }
                val playing = player.playing || (player.loading && player.playRequested)
                IconAction("播放或暂停", onToggle, ResonancePalette.signal, filled = true) {
                    PlayPauseMorph(playing)
                }
                IconAction("下一首", onNext, ResonancePalette.sand) {
                    Glyph(ResonanceGlyph.NEXT, Modifier.size(22.dp), ResonancePalette.sand)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(ResonancePalette.line))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val slotWidth = maxWidth / homeDestinations.size
            val selection = resonanceAnimatedValue(tab.toFloat(), Motion.Navigation, Motion.responsive, "navigation")
            Box(Modifier.offset {
                val position = if (animate) selection.value else tab.toFloat()
                IntOffset((slotWidth * (position + .5f) - 28.dp).roundToPx(), 5.dp.roundToPx())
            }.size(width = 56.dp, height = 30.dp).background(ResonancePalette.sand, CircleShape))
            Row(Modifier.matchParentSize().selectableGroup()) {
                homeDestinations.forEachIndexed { index, (label, glyph) ->
                    val selected = index == tab
                    val interaction = remember { MutableInteractionSource() }
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, interaction, LocalIndication.current,
                        role = Role.Tab, onClick = { onTab(index) }).resonancePress(interaction),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 5.dp).size(width = 56.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                            Glyph(glyph, Modifier.size(21.dp), if (selected) ResonancePalette.ink else ResonancePalette.muted)
                        }
                        ResonanceText(label, 12, color = if (selected) ResonancePalette.sand else ResonancePalette.muted,
                            weight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun IconAction(label: String, onClick: () -> Unit, color: Color, filled: Boolean = false, outline: Boolean = false,
    content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val surface = when {
            filled -> Modifier.background(color, CircleShape)
            outline -> Modifier.border(1.5.dp, color, CircleShape)
            else -> Modifier
        }
        Box(Modifier.size(44.dp).resonancePress(interaction).then(surface), contentAlignment = Alignment.Center) { content() }
    }
}

// The play triangle is split into two polygons so its silhouette can become two pause bars.
private val playOutline = floatArrayOf(.32f,.20f, .49f,.306f, .49f,.694f, .32f,.80f,
    .49f,.306f, .80f,.50f, .80f,.50f, .49f,.694f)
private val pauseOutline = floatArrayOf(.25f,.20f, .41f,.20f, .41f,.80f, .25f,.80f,
    .59f,.20f, .75f,.20f, .75f,.80f, .59f,.80f)

@Composable
internal fun PlayPauseMorph(playing: Boolean) {
    val animate = LocalResonanceMotion.current
    val target = if (playing) 1f else 0f
    val phase = resonanceAnimatedValue(target, Motion.PlayMorph, Motion.easeInOut, "play pause")
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
            drawPath(path, ResonancePalette.ink)
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

@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    ActionButton(label, onClick, modifier, solid = true)

@Composable
internal fun OutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    ActionButton(label, onClick, modifier, solid = false)

@Composable
internal fun EmptyState(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.x6),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.x3)) {
        SandPlate(message, Modifier.size(Space.listCover), density = .16f)
        Text(message, color = ResonancePalette.muted, fontSize = TypeSize.action)
        if (action != null && onAction != null) PrimaryButton(action, onAction)
    }
}

@Composable
internal fun LoadingState(message: String, ticker: FrameTicker, visible: Boolean) {
    val renderer = remember { HomeSandRenderer() }
    Row(Modifier.fillMaxWidth().padding(vertical = Space.x2), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
        SandPlate(message, Modifier.size(Space.x8), density = .035f,
            animation = SandAnimation(renderer, ticker, visible, false, priority = 0, repeat = true))
        Text(message, fontSize = TypeSize.secondary, color = ResonancePalette.muted,
            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
    }
}

@Composable
internal fun ErrorState(title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.x3), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        Text(title, fontSize = TypeSize.track, color = ResonancePalette.signal, fontWeight = FontWeight.Bold)
        if (message.isNotBlank()) Text(message, fontSize = TypeSize.secondary, color = ResonancePalette.muted)
        if (action != null && onAction != null) OutlineButton(action, onAction)
    }
}

@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, hint: String) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) Modifier.border(Space.focus, ResonancePalette.sand, Corners.search) else Modifier
    BasicTextField(value, onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch).onFocusChanged { focused = it.isFocused }
            .clip(Corners.search).background(ResonancePalette.surface).then(border)
            .semantics { contentDescription = hint },
        singleLine = true, cursorBrush = SolidColor(ResonancePalette.signal),
        textStyle = resonanceTextStyle(TypeSize.body, FontWeight.Medium, true).copy(color = ResonancePalette.sand),
        decorationBox = { field ->
            Row(Modifier.padding(horizontal = Space.x4, vertical = Space.x3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                Glyph(ResonanceGlyph.SEARCH, Modifier.size(Space.x5), ResonancePalette.muted)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(hint, fontSize = TypeSize.action, color = ResonancePalette.muted)
                    field()
                }
            }
        })
}

@Composable
internal fun GenreTile(label: String, seed: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val selectedLine = if (selected) Modifier.border(Space.focus, ResonancePalette.signal, Corners.cover) else Modifier
    Column(modifier.heightIn(min = Space.touch).selectable(selected, interaction, LocalIndication.current, role = Role.Button,
        onClick = onClick).resonancePress(interaction),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        SandPlate("genre:$seed", Modifier.fillMaxWidth().aspectRatio(1f).then(selectedLine), density = .55f)
        Text(label, color = if (selected) ResonancePalette.signal else ResonancePalette.sand,
            fontSize = TypeSize.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
