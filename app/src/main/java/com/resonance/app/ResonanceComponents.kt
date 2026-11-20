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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun resonanceTextStyle(size: TextUnit, weight: FontWeight, english: Boolean) = TextStyle(
    fontFamily = if (english) LocalBricolage.current else FontFamily.SansSerif,
    fontSize = size,
    fontWeight = weight,
    fontFeatureSettings = "tnum"
)

@Composable
internal fun ResonanceText(
    value: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = ResonancePalette.sand,
    weight: FontWeight = FontWeight.Medium, english: Boolean = false, lines: Int = 1
) {
    Text(value, modifier, color = color, style = resonanceTextStyle(size, weight, english),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun TopBar(connected: Boolean, ticker: FrameTicker, logo: MotionFloat, visible: Boolean, onAccount: () -> Unit) {
    val animate = LocalResonanceMotion.current && visible
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(Space.x6)) {
            val time = if (animate) ticker.time else 0L
            val envelope = if (animate) logo.value(time) else 0f
            for (index in 0..3) {
                val height = Motion.logoHeight(index, time / Motion.NanosPerMs.toFloat(), envelope)
                val x = size.width * (.14f + index * .21f)
                val half = size.height * height / 2
                drawLine(ResonancePalette.warm, Offset(x, center.y - half), Offset(x, center.y + half),
                    strokeWidth = Space.logoStroke.toPx(), cap = StrokeCap.Round)
            }
        }
        Column(Modifier.weight(1f).padding(start = Space.wordmarkGap)) {
            Text("Resonance", color = ResonancePalette.sand,
                style = resonanceTextStyle(TypeSize.title, FontWeight.ExtraBold, english = true), letterSpacing = TypeSize.logoTracking,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            ResonanceText("by Ardolie", TypeSize.caption, color = ResonancePalette.muted, english = true)
        }
        Row(Modifier.heightIn(min = Space.touch).clickable(interaction, LocalIndication.current, role = Role.Button,
            onClick = onAccount).resonancePress(interaction),
            horizontalArrangement = Arrangement.spacedBy(Space.statusDot), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Space.connectionDot).background(ResonancePalette.signal, CircleShape))
            ResonanceText(if (connected) "QQ 音乐已连接" else "连接 QQ 音乐", TypeSize.secondary, color = ResonancePalette.muted, english = true)
        }
    }
}


@Composable
internal fun ArtworkTransition(cover: ArtworkLoad, modifier: Modifier, shape: Shape,
    dropShadow: Boolean = false, visible: Boolean = true) {
    val animate = LocalResonanceMotion.current && visible
    var lastReady by remember { mutableStateOf(cover.image) }
    LaunchedEffect(cover) { if (!cover.pending) lastReady = cover.image }
    val image = if (animate && cover.pending) lastReady else cover.image
    val shadow = if (dropShadow) Modifier.shadow(Space.opticalInset, shape) else Modifier
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
internal fun NeedleArtwork(trackId: String, cover: ArtworkLoad, renderer: HomeSandRenderer, ticker: FrameTicker,
    modifier: Modifier, shape: Shape, visible: Boolean, dropShadow: Boolean = false) {
    val animate = visible && LocalResonanceMotion.current
    var presented by remember { mutableStateOf(cover) }
    var presentedId by remember { mutableStateOf(trackId) }
    LaunchedEffect(trackId, cover, animate) {
        if (animate && cover.pending && presented.image != null) return@LaunchedEffect
        if (animate && presentedId != trackId && presented.image != null) {
            withTimeoutOrNull(Motion.Needle.toLong()) {
                snapshotFlow { renderer.artworkCueReached(trackId, ticker) }.first { it }
            }
        }
        presentedId = trackId
        presented = cover
    }
    ArtworkTransition(if (animate) presented else cover, modifier, shape, dropShadow, visible)
}

@Composable
internal fun resonanceAnimatedValue(target: Float, duration: Int, easing: Easing, label: String): State<Float> =
    if (LocalResonanceMotion.current) animateFloatAsState(target, tween(duration, easing = easing), label = label)
    else rememberUpdatedState(target)

@Composable
internal fun Modifier.resonancePress(interaction: MutableInteractionSource): Modifier {
    val animate = LocalResonanceMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale = resonanceAnimatedValue(if (animate && pressed) Motion.PressScale else 1f, if (pressed) Motion.Press else Motion.Release,
        if (pressed) Motion.accelerate else Motion.release, "button press")
    val highlight = resonanceAnimatedValue(
        if (pressed || hovered || focused) .16f else 0f, Motion.Press, Motion.responsive, "hover highlight")
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scaleX
    }.drawWithContent {
        drawContent()
        if (highlight.value > 0f) drawRoundRect(
            ResonancePalette.signal.copy(alpha = highlight.value), cornerRadius = CornerRadius(Space.x3.toPx())
        )
    }
}


@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier, solid: Boolean, enabled: Boolean) {
    ActionButton(onClick, modifier, solid, enabled) {
        ResonanceText(label, TypeSize.body, color = LocalContentColor.current, weight = FontWeight.Bold, lines = 2)
    }
}

@Composable
private fun ActionButton(onClick: () -> Unit, modifier: Modifier, solid: Boolean, enabled: Boolean,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val surface = if (solid) Modifier.background(if (enabled) ResonancePalette.signal else ResonancePalette.surface)
        else Modifier.border(Space.focus, if (enabled) ResonancePalette.sand else ResonancePalette.muted, CircleShape)
    Box(modifier.heightIn(min = Space.touch).clickable(interaction, LocalIndication.current, enabled = enabled,
        role = Role.Button, onClick = onClick)
        .resonancePress(interaction).clip(CircleShape).then(surface).padding(horizontal = Space.x3, vertical = Space.opticalInset),
        contentAlignment = Alignment.Center) {
        val textColor = if (!enabled) ResonancePalette.muted else if (solid) ResonancePalette.ink else ResonancePalette.sand
        CompositionLocalProvider(LocalContentColor provides textColor) {
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
internal fun TextAction(label: String, onClick: () -> Unit, color: Color, description: String = label, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.sizeIn(minWidth = Space.touch, minHeight = Space.touch)
        .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
        .resonancePress(interaction)
        .semantics { contentDescription = description }.padding(horizontal = Space.opticalInset), contentAlignment = Alignment.Center) {
        ResonanceText(label, TypeSize.action, color = color, english = true)
    }
}


@Composable
internal fun ScoreCard(score: GenreScore, maxAbs: Double, textWidth: Dp, ticker: FrameTicker,
    systemMotion: Boolean, modifier: Modifier, compact: Boolean = false) {
    Column(modifier.clip(Corners.card).background(ResonancePalette.surface)
        .padding(start = Space.x3, end = Space.x3, top = Space.x3, bottom = Space.scoreBottomInset),
        verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        ResonanceText(genreName(score.label), TypeSize.secondary, color = ResonancePalette.muted, english = true, lines = if (compact) 1 else 2)
        Box(Modifier.weight(1f))
        val number = scoreMotion(score.combined.toFloat(), Motion.Score, Motion.settle, ticker, systemMotion)
        ScoreNumber(number, maxAbs, textWidth)
        val bar = scoreMotion((score.combined / maxAbs).toFloat(), Motion.ScoreBar, Motion.responsive, ticker, systemMotion)
        SignedScoreBar(bar)
    }
}

@Composable
internal fun SignedScoreBar(bar: State<Float>, modifier: Modifier = Modifier) {
        Canvas(modifier.fillMaxWidth().height(Space.scoreTickHeight)) {
            val fraction = bar.value
            val color = if (fraction < 0f) ResonancePalette.signal else ResonancePalette.sand
            val barHeight = Space.x1.toPx()
            val barY = (size.height - barHeight) / 2
            val radius = CornerRadius(barHeight / 2)
            drawRoundRect(ResonancePalette.line, Offset(0f, barY), Size(size.width, barHeight), radius)
            val length = abs(fraction) * size.width / 2
            if (length > 0) drawRoundRect(color, Offset(center.x - if (fraction < 0f) length else 0f, barY),
                Size(length, barHeight), radius)
            val tick = Space.progressLine.toPx()
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
    val style = resonanceTextStyle(TypeSize.score, FontWeight.ExtraBold, english = true)
    val measurer = rememberTextMeasurer()
    val available = with(LocalDensity.current) { textWidth.toPx() }
    val width = remember(measurer, style, maxAbs) {
        maxOf(measurer.measure(String.format(Locale.ROOT, "−%.1f", maxAbs), style = style, maxLines = 1).size.width,
            measurer.measure(String.format(Locale.ROOT, "+%.1f", maxAbs), style = style, maxLines = 1).size.width)
    }
    val adjustedSize = if (width > available) TypeSize.score * ((available - 1).coerceAtLeast(1f) / width) else TypeSize.score
    Text(scoreLabel, color = if (tenths < 0) ResonancePalette.signal else ResonancePalette.sand,
        style = style.copy(fontSize = adjustedSize), maxLines = 1, softWrap = false)
}


@Composable
internal fun SectionHeader(title: String, onAction: (() -> Unit)?, action: String,
    onPlayBatch: (() -> Unit)? = null, actionColor: Color = ResonancePalette.muted) {
    Row(Modifier.fillMaxWidth().padding(top = Space.opticalInset), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
        ResonanceText(title, TypeSize.title, Modifier.weight(1f), weight = FontWeight.Black)
        if (onAction != null) TextAction(action, onAction, actionColor)
        if (onPlayBatch != null) OutlineButton("播放这批", onPlayBatch)
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    song: QqCatalogueTrack, reason: String, current: Boolean, onPlay: () -> Unit, onDetails: () -> Unit,
    unfamiliar: Boolean = false,
    subtitle: String = "", detailsButton: Boolean = false, quietDetails: Boolean = false,
    supporting: @Composable ColumnScope.() -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = Space.trackHeight),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
        Row(Modifier.weight(1f).heightIn(min = Space.trackHeight)
            .combinedClickable(interaction, indication = null, role = Role.Button, onClick = onPlay, onLongClick = onDetails,
                onLongClickLabel = "${song.name}的歌曲详情").resonancePress(interaction),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
            val cover = rememberArtworkLoad(song)
            Box(Modifier.size(Space.listCover)) {
                if (cover.image == null) SandPlate(song.mid, Modifier.matchParentSize())
                ArtworkTransition(cover, Modifier.matchParentSize(), Corners.cover)
            }
            Column(Modifier.weight(1f).padding(vertical = Space.x1), verticalArrangement = Arrangement.spacedBy(Space.thin)) {
                ResonanceText(song.name, TypeSize.track, color = if (current) ResonancePalette.signal else ResonancePalette.sand, weight = FontWeight.Bold, english = true)
                ResonanceText(subtitle.ifBlank { if (unfamiliar) "新歌候选 · ${song.artist}" else song.artist }, TypeSize.secondary,
                    color = ResonancePalette.muted, english = true)
                supporting()
                if (reason.isNotBlank()) ResonanceText(reason, TypeSize.secondary, color = ResonancePalette.signal,
                    english = true, lines = if (detailsButton) 3 else 1)
            }
        }
        if (detailsButton) {
            IconAction("${song.name}的歌曲详情", onDetails, ResonancePalette.sand, outline = !quietDetails) {
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
    onToggle: () -> Unit, onNext: () -> Unit,
    pickup: PlayerPickup, onCoverBounds: (Rect) -> Unit,
    artworkSlot: @Composable (Boolean, @Composable () -> Unit) -> Unit = { _, content -> content() }
) {
    val shape = Corners.dock
    val animate = LocalResonanceMotion.current
    val playhead = remember { SmoothPlayhead() }
    val dockGlass = remember {
        Brush.verticalGradient(listOf(ResonancePalette.raised.copy(alpha = .96f),
            ResonancePalette.surface.copy(alpha = .92f), ResonancePalette.ink.copy(alpha = .98f)))
    }
    val glassRim = remember {
        Brush.verticalGradient(listOf(ResonancePalette.signal.copy(alpha = .36f), ResonancePalette.line))
    }
    val tabGlass = remember {
        Brush.verticalGradient(listOf(ResonancePalette.signal.copy(alpha = .32f), ResonancePalette.raised))
    }
    Column(Modifier.navigationBarsPadding().padding(start = Space.x3, end = Space.x3, bottom = Space.x3, top = Space.x1)
        .fillMaxWidth().shadow(Space.x2, shape).clip(shape)
        .background(dockGlass).border(Space.thin, glassRim, shape)) {
        if (song != null) {
            Canvas(Modifier.fillMaxWidth().height(Space.progressLine)) {
                val frame = clock.value
                val advancing = animate && player.playing
                val progress = if (frame.mid == song.mid && player.durationMs > 0)
                    (playhead.position(song.mid, frame.positionMs, if (advancing) ticker.time else 0L, advancing) /
                        player.durationMs).coerceIn(0f, 1f) else 0f
                drawRect(ResonancePalette.line)
                drawRect(ResonancePalette.sand, size = Size(size.width * progress, size.height))
            }
            Row(Modifier.fillMaxWidth().height(Space.miniHeight).padding(horizontal = Space.x2), verticalAlignment = Alignment.CenterVertically) {
                val openInteraction = remember { MutableInteractionSource() }
                val pickupGesture = Modifier.pointerInput(song.mid, pickup) {
                    val tracker = VelocityTracker()
                    val travel = (viewConfiguration.touchSlop + size.height * 4f)
                        .coerceAtMost(Space.pickupMaxDistance.toPx())
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onVerticalDrag = { change, distance ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            pickup.move(-distance / travel)
                        },
                        onDragEnd = { pickup.release(-tracker.calculateVelocity().y / density) },
                        onDragCancel = { pickup.cancel() }
                    )
                }
                Row(Modifier.weight(1f).heightIn(min = Space.touch)
                    .then(pickupGesture)
                    .clickable(openInteraction, LocalIndication.current, onClickLabel = "展开播放器", onClick = onExpand)
                    .resonancePress(openInteraction),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                    Box(Modifier.size(Space.dockCover).onGloballyPositioned { onCoverBounds(it.boundsInRoot()) },
                        contentAlignment = Alignment.Center) {
                        artworkSlot(true) {
                            if (cover == null) Box(Modifier.fillMaxSize().clip(Corners.dockCover)) {
                                SandPlate(song.mid, Modifier.matchParentSize())
                            } else Box(Modifier.fillMaxSize().clip(Corners.dockCover).background(ResonancePalette.plate))
                        }
                        if (cover != null) artworkSlot(false) {
                            Image(cover, null, Modifier.fillMaxSize().clip(Corners.dockCover), contentScale = ContentScale.Crop)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.thin)) {
                        ResonanceText(song.name, TypeSize.body, weight = FontWeight.Bold, english = true)
                        val subtitle = when {
                            player.loading -> "正在连接音源…"
                            player.message.contains("无法") -> player.message
                            else -> song.artist
                        }
                        ResonanceText(subtitle, TypeSize.secondary, color = ResonancePalette.muted, english = true)
                    }
                }
                val mode = PlaybackMode.parse(player.playbackMode)
                val modeColor = if (mode == PlaybackMode.SHUFFLE) ResonancePalette.signal else ResonancePalette.muted
                IconAction("播放模式：${mode.label}，点按切换", { onMode(mode.next().name) }, modeColor) {
                    ShuffleGlyph(modeColor)
                }
                val playing = player.playing || (player.loading && player.playRequested)
                IconAction("播放或暂停", onToggle, ResonancePalette.line, outline = true) {
                    PlayPauseMorph(playing, color = ResonancePalette.sand)
                }
                IconAction("下一首", onNext, ResonancePalette.sand) {
                    Glyph(ResonanceGlyph.NEXT, Modifier.size(Space.nextGlyph), ResonancePalette.sand)
                }
            }
            Box(Modifier.fillMaxWidth().height(Space.thin).background(ResonancePalette.line))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(Space.listCover)) {
            val slotWidth = maxWidth / homeDestinations.size
            val selection = resonanceAnimatedValue(tab.toFloat(), Motion.Navigation, Motion.responsive, "navigation")
            Box(Modifier.offset {
                val position = if (animate) selection.value else tab.toFloat()
                IntOffset((slotWidth * (position + .5f) - Space.navHalfWidth).roundToPx(), Space.opticalInset.roundToPx())
            }.size(width = Space.listCover, height = Space.navPillHeight)
                .graphicsLayer {
                    val travel = if (animate) abs(selection.value - tab).coerceIn(0f, 1f) else 0f
                    scaleX = 1f + Motion.NavGlassStretch * travel
                }
                .background(tabGlass, CircleShape).border(Space.thin, glassRim, CircleShape))
            Row(Modifier.matchParentSize().selectableGroup()) {
                homeDestinations.forEachIndexed { index, (label, glyph) ->
                    val selected = index == tab
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    val focused by interaction.collectIsFocusedAsState()
                    val highlighted = hovered || focused
                    val iconScale = resonanceAnimatedValue(
                        if (selected || highlighted) Motion.NavIconScale else 1f,
                        Motion.Navigation, Motion.responsive, "navigation icon")
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(selected, interaction, indication = null,
                        role = Role.Tab, onClick = { onTab(index) }),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val hoverGlass = when {
                            focused -> Modifier.border(Space.thin, ResonancePalette.signal, CircleShape)
                            hovered && !selected -> Modifier.background(ResonancePalette.signal.copy(alpha = .12f), CircleShape)
                            else -> Modifier
                        }
                        Box(Modifier.padding(top = Space.opticalInset)
                            .size(width = Space.listCover, height = Space.navPillHeight).then(hoverGlass),
                            contentAlignment = Alignment.Center) {
                            Glyph(glyph, Modifier.size(Space.navGlyph).graphicsLayer {
                                scaleX = iconScale.value
                                scaleY = scaleX
                            }, if (selected || highlighted) ResonancePalette.signal else ResonancePalette.muted)
                        }
                        ResonanceText(label, TypeSize.secondary, color = if (selected) ResonancePalette.sand else ResonancePalette.muted,
                            weight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun IconAction(label: String, onClick: () -> Unit, color: Color, filled: Boolean = false, outline: Boolean = false,
    diameter: Dp = Space.x8 + Space.x3, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(maxOf(Space.touch, diameter)).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val surface = when {
            filled -> Modifier.background(color, CircleShape)
            outline -> Modifier.border(Space.focus, color, CircleShape)
            else -> Modifier
        }
        Box(Modifier.size(diameter).resonancePress(interaction).then(surface), contentAlignment = Alignment.Center) { content() }
    }
}

// The play triangle is split into two polygons so its silhouette can become two pause bars.
private val playOutline = floatArrayOf(.32f,.20f, .49f,.306f, .49f,.694f, .32f,.80f,
    .49f,.306f, .80f,.50f, .80f,.50f, .49f,.694f)
private val pauseOutline = floatArrayOf(.25f,.20f, .41f,.20f, .41f,.80f, .25f,.80f,
    .59f,.20f, .75f,.20f, .75f,.80f, .59f,.80f)

@Composable
internal fun PlayPauseMorph(playing: Boolean, modifier: Modifier = Modifier.size(Space.x6),
    color: Color = ResonancePalette.ink) {
    val animate = LocalResonanceMotion.current
    val target = if (playing) 1f else 0f
    val phase = resonanceAnimatedValue(target, Motion.PlayMorph, Motion.easeInOut, "play pause")
    Canvas(modifier.drawWithCache {
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
            drawPath(path, color)
        }
    }) {}
}

@Composable
private fun ShuffleGlyph(color: Color) {
    Canvas(Modifier.size(Space.shuffleGlyph)) {
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
        drawPath(path, color, style = Stroke(Space.shuffleStroke.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    ActionButton(label, onClick, modifier, solid = true, enabled = enabled)

@Composable
internal fun OutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    ActionButton(label, onClick, modifier, solid = false, enabled = enabled)

@Composable
internal fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) =
    ActionButton(onClick, modifier, solid = true, enabled = enabled, content = content)

@Composable
internal fun OutlineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) =
    ActionButton(onClick, modifier, solid = false, enabled = enabled, content = content)

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
internal fun LoadingState(message: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.x2), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
        SandPlate(message, Modifier.size(Space.x8), density = .3f)
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
internal fun ResonanceChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    FilterChip(selected, onClick, { Text(text, fontSize = TypeSize.caption) },
        modifier = Modifier.heightIn(min = Space.touch).resonancePress(interaction), enabled = enabled,
        shape = CircleShape, interactionSource = interaction)
}

@Composable
internal fun CatalogueDotGrid(total: Int, identified: Int) {
    if (total <= 0) return
    val filled = identified.coerceIn(0, total)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / Space.catalogueDotPitch).toInt().coerceAtLeast(1)
        val lines = (total - 1) / columns + 1
        Canvas(Modifier.fillMaxWidth().height(Space.catalogueDotPitch * lines).drawWithCache {
            val pitch = Space.catalogueDotPitch.toPx()
            val points = FloatArray(total * 2)
            for (i in 0 until total) {
                points[i * 2] = (i % columns + .5f) * pitch
                points[i * 2 + 1] = (i / columns + .5f) * pitch
            }
            val known = sandPaint(Space.catalogueDot.toPx() / 2f, 255)
            val missing = sandPaint(Space.catalogueDot.toPx() / 2f, 255).apply { color = ResonancePalette.line.toArgb() }
            onDrawBehind {
                val canvas = drawContext.canvas.nativeCanvas
                if (filled > 0) canvas.drawPoints(points, 0, filled * 2, known)
                if (filled < total) canvas.drawPoints(points, filled * 2, (total - filled) * 2, missing)
            }
        }) {}
    }
}
