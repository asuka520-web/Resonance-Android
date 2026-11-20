package com.resonance.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

internal class PlayerPickup {
    var active by mutableStateOf(false)
        private set
    var fraction by mutableFloatStateOf(0f)
    var settleOpen by mutableStateOf<Boolean?>(null)
        private set
    var origin by mutableStateOf<Rect?>(null)
    var destination by mutableStateOf<Rect?>(null)

    fun move(amount: Float) {
        if (settleOpen != null || (!active && amount <= 0f)) return
        active = true
        fraction = (fraction + amount).coerceIn(0f, 1f)
    }

    fun release(upwardVelocityDp: Float) {
        if (active) settleOpen = Motion.pickupCommits(fraction, upwardVelocityDp)
    }

    fun cancel() {
        if (active) settleOpen = false
    }

    fun close() {
        active = true
        fraction = 1f
        settleOpen = false
    }

    fun reset() {
        active = false
        fraction = 0f
        settleOpen = null
    }
}

@Composable
internal fun PickupArtwork(image: ImageBitmap, pickup: PlayerPickup, host: Offset) {
    val from = pickup.origin ?: return
    val to = pickup.destination ?: return
    if (from.isEmpty || to.isEmpty) return
    val fraction = pickup.fraction
    val density = LocalDensity.current
    val width = with(density) { Motion.mix(from.width, to.width, fraction).toDp() }
    val height = with(density) { Motion.mix(from.height, to.height, fraction).toDp() }
    Image(image, null, Modifier.offset {
        IntOffset((Motion.mix(from.left, to.left, fraction) - host.x).roundToInt(),
            (Motion.mix(from.top, to.top, fraction) - host.y).roundToInt())
    }.size(width, height).clip(Corners.card), contentScale = ContentScale.Crop)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.playerArtwork(
    song: String, plate: Boolean, visible: AnimatedVisibilityScope, source: String = "dock"
): Modifier = Modifier.sharedElement(
    rememberSharedContentState(if (plate) "resonance-board:$song" else "resonance-cover:$source:$song"),
    animatedVisibilityScope = visible,
    boundsTransform = { _, _ -> tween(Motion.SharedArtwork, easing = Motion.responsive) }
)

@Composable
internal fun NowPlaying(
    open: Boolean, song: QqCatalogueTrack, cover: ArtworkLoad, player: PlayerStatus, clock: State<PlaybackClock>,
    plateTransition: Modifier, coverTransition: Modifier, labels: Set<String>, genreStatus: String,
    notice: DirectionNotice?, onClose: () -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (Float) -> Unit, onRetry: () -> Unit, onSimilar: () -> Unit, onMore: () -> Unit, onLess: () -> Unit,
    onHistory: () -> Unit, favorite: @Composable () -> Unit, queueControls: @Composable () -> Unit,
    pickup: PlayerPickup? = null, showLiftedCover: Boolean = false
) {
    BackHandler(open, onBack = onClose)
    val motion = LocalResonanceMotion.current && open
    val ticker = ResonanceEngine.clock
    val renderer = remember { HomeSandRenderer() }
    var boardVisible by remember { mutableStateOf(false) }
    val boardActive = boardVisible && motion
    var firstPattern by remember { mutableStateOf(true) }
    val hasWork by remember { derivedStateOf { ticker.hasSandWork() } }
    MonitorResonanceFrames(ticker, boardActive && (player.playing || hasWork) && !ticker.reducedQuality)
    var panelOffset by remember { mutableFloatStateOf(0f) }
    val dragLimit = with(LocalDensity.current) { (Space.x6 * 3).toPx() }
    val scroll = rememberScrollState()

    val duringPickup = pickup?.active == true && !open
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (duringPickup) pickup?.fraction ?: 0f else 1f }
        .then(if (duringPickup) Modifier.clearAndSetSemantics {} else Modifier)) {
        Box(Modifier.matchParentSize().background(ResonancePalette.ink.copy(alpha = .72f))
            .clickable(onClick = onClose).semantics { contentDescription = "关闭播放器" })
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.94f)
            .offset { IntOffset(0, panelOffset.roundToInt()) }
            .clip(Corners.sheet).background(ResonancePalette.ink)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .semantics { paneTitle = "播放器" }) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(Space.touch)
                    .draggable(rememberDraggableState { panelOffset = (panelOffset + it).coerceAtLeast(0f) },
                        orientation = Orientation.Vertical, onDragStopped = { velocity ->
                            if (panelOffset >= dragLimit || velocity >= dragLimit * 8) onClose()
                            panelOffset = 0f
                        }), contentAlignment = Alignment.Center) {
                    Box(Modifier.align(Alignment.CenterStart).padding(start = Space.x3)) {
                        IconAction("收起播放器", onClose, ResonancePalette.sand) {
                            Glyph(ResonanceGlyph.DOWN, Modifier.size(Space.x5))
                        }
                    }
                    ResonanceText("正在播放", TypeSize.secondary, color = ResonancePalette.muted)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = Space.x5),
                    verticalArrangement = Arrangement.spacedBy(Space.x3)) {
                    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.12f)
                        .onGloballyPositioned { boardVisible = !it.boundsInWindow().isEmpty }, contentAlignment = Alignment.Center) {
                        SandPlate(song.mid, Modifier.matchParentSize().then(plateTransition), animated = true,
                            background = ResonancePalette.fieldOuter,
                            animation = SandAnimation(renderer, ticker, boardActive, player.playing, cold = firstPattern,
                                onFirstPattern = { firstPattern = false }, priority = 3,
                                needleOrigin = Offset(.5f, .94f)))
                        val artworkSize = (maxWidth * .68f).coerceAtMost(Space.playerCoverMax)
                        Box(Modifier.size(artworkSize).onGloballyPositioned { pickup?.destination = it.boundsInRoot() }
                            .then(coverTransition).graphicsLayer { alpha = if (showLiftedCover) 0f else 1f }) {
                            NeedleArtwork(song.mid, cover, renderer, ticker, Modifier.matchParentSize(), Corners.card,
                                dropShadow = true, visible = boardVisible)
                        }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Space.touch),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(song.name, color = ResonancePalette.sand, style = resonanceTextStyle(TypeSize.song, FontWeight.ExtraBold, true),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            ResonanceText(song.artist, TypeSize.body, color = ResonancePalette.muted, english = true)
                        }
                        Box(Modifier.align(Alignment.CenterEnd)) { favorite() }
                    }
                    TuningRuler(song.mid, player.durationMs, clock, onSeek)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconAction("上一首", onPrevious, ResonancePalette.sand) {
                            Glyph(ResonanceGlyph.PREVIOUS, Modifier.size(Space.x6))
                        }
                        IconAction("播放或暂停", onPlayPause, ResonancePalette.sand, filled = true, diameter = Space.miniHeight) {
                            PlayPauseMorph(player.playing || (player.loading && player.playRequested), Modifier.size(Space.x8))
                        }
                        IconAction("下一首", onNext, ResonancePalette.sand) {
                            Glyph(ResonanceGlyph.NEXT, Modifier.size(Space.x6))
                        }
                    }
                    if (player.message.contains("无法播放")) {
                        ErrorState("这首歌暂时无法播放", player.message, "重试", onRetry)
                    }
                    if (labels.isNotEmpty()) CompleteTagRow(labels)
                    else Text(genreStatus, fontSize = TypeSize.secondary, color = ResonancePalette.muted)
                    PrimaryButton("相似推荐", onSimilar, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        TextAction("多来这种", onMore, ResonancePalette.signal)
                        TextAction("暂时少放", onLess, ResonancePalette.muted)
                    }
                    HorizontalDivider(color = ResonancePalette.line)
                    queueControls()
                    TextAction("最近换向记录", onHistory, ResonancePalette.signal)
                    Text(if (player.excerpt) "试听片段 · 暂不纳入偏好" else "QQ 音乐音源 · 后台播放",
                        fontSize = TypeSize.caption, color = ResonancePalette.muted, modifier = Modifier.padding(bottom = Space.x5))
                }
            }
            DirectionChangeBanner(notice, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.x8))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TuningRuler(mid: String, durationMs: Long, clock: State<PlaybackClock>, onSeek: (Float) -> Unit) {
    val live = clock.value.takeIf { it.mid == mid }?.positionMs ?: 0L
    var drag by remember(mid) { mutableStateOf<Float?>(null) }
    val fraction = drag ?: if (durationMs > 0) (live.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column {
        Slider(fraction, onValueChange = { drag = it }, onValueChangeFinished = { drag?.let(onSeek); drag = null },
            modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch),
            thumb = { Box(Modifier.size(Space.x1, Space.x5).background(ResonancePalette.signal, CircleShape)) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(Space.x4)) {
                    val lineY = size.height / 2
                    val activeX = state.value * size.width
                    drawLine(ResonancePalette.line, Offset(0f, lineY), Offset(size.width, lineY), Space.thin.toPx())
                    drawLine(ResonancePalette.sand, Offset(0f, lineY), Offset(activeX, lineY), Space.thin.toPx())
                    for (tick in 0..24) {
                        val x = tick * size.width / 24
                        val half = if (tick % 4 == 0) Space.x1.toPx() else Space.thin.toPx()
                        drawLine(if (x <= activeX) ResonancePalette.sand else ResonancePalette.muted,
                            Offset(x, lineY - half), Offset(x, lineY + half), Space.thin.toPx())
                    }
                }
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(drag?.let { (durationMs * it).toLong() } ?: live),
                fontSize = TypeSize.caption, color = ResonancePalette.muted)
            Text(formatPlaybackTime(durationMs), fontSize = TypeSize.caption, color = ResonancePalette.muted)
        }
    }
}

internal fun formatPlaybackTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
