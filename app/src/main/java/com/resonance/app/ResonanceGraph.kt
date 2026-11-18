package com.resonance.app

import android.os.Trace
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val FieldSize = 65
private const val FieldEdge = FieldSize - 1
private const val FieldStride = 3
internal const val GraphMaxGrains = 1200
private const val GraphMinGrains = 200
private const val GrainsPerCompletion = 3

internal fun graphGrainCount(minutes: Long): Int =
    GraphMinGrains + (minutes.coerceIn(0, 500) * 2).toInt()

internal data class GraphMode(val seed: String, val weight: Float)

internal fun graphModes(scores: List<GenreScore>): List<GraphMode> {
    val selected = scores.filter { it.combined.isFinite() && it.combined != 0.0 }
        .sortedWith(compareByDescending<GenreScore> { abs(it.combined) }.thenBy { it.label }).take(5)
    val strongest = selected.firstOrNull()?.let { abs(it.combined) } ?: return emptyList()
    return selected.map { GraphMode(it.label, (it.combined / strongest).toFloat()) }
}

/** Grid samples contain the analytic field value and its two partial derivatives. */
internal class ChladniField private constructor(private val data: FloatArray) {
    fun sample(x: Int, y: Int, dx: Float, dy: Float, component: Int): Float {
        val first = (y * FieldSize + x) * FieldStride + component
        val second = first + FieldSize * FieldStride
        return Motion.mix(Motion.mix(data[first], data[first + FieldStride], dx),
            Motion.mix(data[second], data[second + FieldStride], dx), dy)
    }

    fun blendFrom(start: ChladniField, target: ChladniField, fraction: Float) {
        for (i in data.indices) data[i] = Motion.mix(start.data[i], target.data[i], fraction)
    }

    companion object {
        fun empty() = ChladniField(FloatArray(FieldSize * FieldSize * FieldStride))

        fun compose(modes: List<GraphMode>): ChladniField {
            val field = empty()
            for ((seed, weight) in modes) {
                val (n, m) = chladniMode(seed)
                val a = n * PI
                val b = m * PI
                val ca = DoubleArray(FieldSize) { cos(a * it / FieldEdge) }
                val cb = DoubleArray(FieldSize) { cos(b * it / FieldEdge) }
                val da = DoubleArray(FieldSize) { -a * sin(a * it / FieldEdge) }
                val db = DoubleArray(FieldSize) { -b * sin(b * it / FieldEdge) }
                for (y in 0 until FieldSize) for (x in 0 until FieldSize) {
                    val i = (y * FieldSize + x) * FieldStride
                    field.data[i] += ((ca[x] * cb[y] - cb[x] * ca[y]) * weight).toFloat()
                    field.data[i + 1] += ((da[x] * cb[y] - db[x] * ca[y]) * weight).toFloat()
                    field.data[i + 2] += ((ca[x] * db[y] - cb[x] * da[y]) * weight).toFloat()
                }
            }
            return field
        }
    }
}

private class ChladniFlow {
    var scores = emptyGraphField
    var track = emptyGraphField
    var previous = track
    var userShare = 0f
    var songShare = 0f
    var blend = 1f

    fun move(source: FloatArray, offset: Int, step: Float, nx: Float = 0f, ny: Float = 0f) {
        val x = source[offset]
        val y = source[offset + 1]
        val sx = x * FieldEdge
        val sy = y * FieldEdge
        val ix = sx.toInt().coerceAtMost(FieldEdge - 1)
        val iy = sy.toInt().coerceAtMost(FieldEdge - 1)
        val dx = sx - ix
        val dy = sy - iy
        val potential = component(ix, iy, dx, dy, 0) * step
        val gx = component(ix, iy, dx, dy, 1)
        val gy = component(ix, iy, dx, dy, 2)
        source[offset] = (x - (potential * gx).coerceIn(-.06f, .06f) + nx).coerceIn(0f, 1f)
        source[offset + 1] = (y - (potential * gy).coerceIn(-.06f, .06f) + ny).coerceIn(0f, 1f)
    }

    private fun component(x: Int, y: Int, dx: Float, dy: Float, axis: Int): Float {
        val own = if (userShare > 0f) scores.sample(x, y, dx, dy, axis) * userShare else 0f
        if (songShare == 0f) return own
        val new = track.sample(x, y, dx, dy, axis)
        val active = if (blend >= 1f) new else Motion.mix(previous.sample(x, y, dx, dy, axis), new, blend)
        return own + songShare * active
    }
}

private val emptyGraphField = ChladniField.empty()

internal class GraphSandRenderer {
    private var rng = 0x29b7911
    private val positions = FloatArray(GraphMaxGrains * 2) { random() }
    private val points = FloatArray(GraphMaxGrains * 2)
    private val drops = LongArray(GraphMaxGrains) { -1L }
    private val previousTrack = ChladniField.empty()
    private val flow = ChladniFlow()
    private var seenCompletions = 0
    private var scores by mutableStateOf(emptyGraphField)
    private var track by mutableStateOf(emptyGraphField)
    private var scoreWeight by mutableFloatStateOf(0f)
    private var trackWeight by mutableFloatStateOf(0f)
    private var blendingSince by mutableLongStateOf(-1L)
    private var lastFrame = -1L
    private var nextDrop = 0
    private var paintVersion by mutableIntStateOf(0)

    private fun random(): Float {
        rng = rng xor (rng shl 13)
        rng = rng xor (rng ushr 17)
        rng = rng xor (rng shl 5)
        return (rng ushr 8) / 16_777_216f
    }

    fun useScores(field: ChladniField, weight: Float, ticker: FrameTicker, motion: Boolean) {
        scores = field
        scoreWeight = weight
        arrange(ticker, motion)
    }

    fun useTrack(field: ChladniField, trackPresent: Boolean, ticker: FrameTicker, motion: Boolean) {
        if (field === track) return
        val fraction = blend(ticker.sandTime)
        previousTrack.blendFrom(previousTrack, track, fraction)
        track = field
        trackWeight = if (trackPresent) Motion.GraphSongWeight else 0f
        blendingSince = if (motion) ticker.request(Motion.GraphTrackBlend, sand = true) else -1L
        arrange(ticker, motion)
    }

    private fun arrange(ticker: FrameTicker, motion: Boolean) {
        lastFrame = -1L
        if (motion) ticker.request(Motion.GraphTrackBlend, sand = true)
        paintVersion++
    }

    fun drop(totalCompletions: Int, capacity: Int, ticker: FrameTicker) {
        if (capacity == 0) return
        val completions = totalCompletions - seenCompletions
        if (completions <= 0) return
        seenCompletions = totalCompletions
        val firstFrame = ticker.request(Motion.GraphDrop, sand = true)
        val count = (completions.toLong() * GrainsPerCompletion).coerceAtMost(capacity.toLong()).toInt()
        repeat(count) {
            val slot = nextDrop++ % capacity
            positions[slot * 2] = random()
            positions[slot * 2 + 1] = 0f
            drops[slot] = firstFrame
        }
        paintVersion++
    }

    private fun blend(now: Long): Float = if (blendingSince < 0L) 1f else
        Motion.progress((now - blendingSince).coerceAtLeast(0L) / Motion.NanosPerMs.toFloat(), Motion.GraphTrackBlend)

    fun draw(canvas: android.graphics.Canvas, size: Size, pixelScale: Float, ticker: FrameTicker,
        paint: android.graphics.Paint, count: Int, animated: Boolean, envelope: Float, eta: Float,
        resting: FloatArray?) {
        @Suppress("UNUSED_VARIABLE")
        val redraw = paintVersion
        val now = if (animated) ticker.sandTime else -1L
        val interval = if (lastFrame < 0L) 0f else ((now - lastFrame) / Motion.NanosPerMs.toFloat()).coerceIn(0f, 40f)
        val timeStep = interval / (Motion.NominalFrameMs)
        val moving = animated && timeStep > 0f
        val fraction = if (animated) blend(now) else 1f
        val intensity = if (moving) Motion.mix(.05f, .2f + 1.8f * ResonanceEngine.level, envelope) else 0f
        val totalWeight = (scoreWeight + trackWeight).takeIf { it > 0f } ?: 1f
        val source = if (!animated && resting != null) resting else positions
        if (moving) {
            flow.scores = scores
            flow.track = track
            flow.previous = previousTrack
            flow.userShare = if (scoreWeight > 0f) 1f / totalWeight else 0f
            flow.songShare = trackWeight / totalWeight
            flow.blend = fraction
            val move = eta * timeStep
            val nx = 2f * intensity * pixelScale / size.width
            val ny = 2f * intensity * pixelScale / size.height
            for (i in 0 until count) {
                val point = i * 2
                val fallen = drops[i]
                if (fallen >= 0L) {
                    val age = (now - fallen).coerceAtLeast(0L) / Motion.NanosPerMs.toFloat()
                    if (age >= Motion.GraphDrop) drops[i] = -1L
                    else positions[point + 1] = maxOf(positions[point + 1],
                        Motion.accelerate.transform(Motion.progress(age, Motion.GraphDrop)) * .24f)
                }
                flow.move(positions, point, move, (random() - .5f) * nx, (random() - .5f) * ny)
            }
        }
        for (i in 0 until count * 2 step 2) {
            points[i] = source[i] * size.width
            points[i + 1] = source[i + 1] * size.height
        }
        lastFrame = now
        canvas.drawPoints(points, 0, count * 2, paint)
    }

}

private fun restingGraph(scoreField: ChladniField, songField: ChladniField, user: Float,
    song: Float, eta: Float): FloatArray {
    val random = java.util.Random(1775L)
    val flow = ChladniFlow().apply {
        scores = scoreField
        track = songField
        userShare = user
        songShare = song
    }
    return FloatArray(GraphMaxGrains * 2) { random.nextFloat() }.also { positions ->
        for (offset in positions.indices step 2) repeat(42) { flow.move(positions, offset, eta) }
    }
}

@Composable
internal fun ResonanceGraph(renderer: GraphSandRenderer, scores: List<GenreScore>, minutes: Long,
    completedTracks: Int, visible: Boolean, modifier: Modifier = Modifier, eta: Float = Motion.GraphFlow) {
    val engine = ResonanceEngine
    val ticker = engine.clock
    val seed = engine.trackSeed
    val motion = LocalResonanceMotion.current
    val count = graphGrainCount(minutes)
    val modes = remember(scores) { graphModes(scores) }
    val userWeight = remember(modes) { modes.sumOf { abs(it.weight).toDouble() }.toFloat() }
    var scoreField by remember { mutableStateOf(emptyGraphField) }
    LaunchedEffect(modes) {
        scoreField = withContext(Dispatchers.Default) { ChladniField.compose(modes) }
    }
    var songField by remember { mutableStateOf(emptyGraphField) }
    LaunchedEffect(seed) {
        songField = if (seed.isBlank()) emptyGraphField else withContext(Dispatchers.Default) {
            ChladniField.compose(listOf(GraphMode(seed, 1f)))
        }
    }
    val step = eta.coerceIn(Motion.GraphFlowMin, Motion.GraphFlowMax)
    val staticPoints by produceState<FloatArray?>(null, motion, scoreField, songField, step) {
        if (!motion) {
            val song = if (seed.isBlank()) 0f else Motion.GraphSongWeight
            val sum = (userWeight + song).takeIf { it > 0f } ?: 1f
            value = withContext(Dispatchers.Default) {
                restingGraph(scoreField, songField, 1f / sum, song / sum, step)
            }
        }
    }

    WithSandBudget(modifier, count, priority = 1) { placed, grant ->
        val active = motion && visible
        val owner = remember { Any() }
        val envelope = remember { MotionFloat(0f) }
        DisposableEffect(owner, active) {
            if (active) engine.subscribe(owner, true)
            onDispose { if (active) engine.subscribe(owner, false) }
        }
        LaunchedEffect(engine.isPlaying, active) {
            envelope.update(if (active && engine.isPlaying) 1f else 0f,
                Motion.SandRest, Motion.settle, ticker, active, sand = true)
        }
        LaunchedEffect(scoreField, active) {
            renderer.useScores(scoreField, userWeight, ticker, active)
        }
        LaunchedEffect(songField, active) {
            renderer.useTrack(songField, seed.isNotBlank(), ticker, active)
        }
        LaunchedEffect(completedTracks, active) {
            if (active) renderer.drop(completedTracks, minOf(grant(), if (ticker.reducedQuality) count / 2 else count), ticker)
        }
        Canvas(placed.clip(Corners.plate).background(ResonancePalette.plate).drawWithCache {
            val paint = sandPaint(Space.mainGrain.toPx(), 255)
            val scale = density
            onDrawBehind {
                val limit = minOf(grant(), if (active && ticker.reducedQuality) count / 2 else count)
                if (limit == 0) return@onDrawBehind
                val started = if (active) System.nanoTime() else 0L
                if (active) Trace.beginSection("ResonanceGraph")
                try {
                    renderer.draw(drawContext.canvas.nativeCanvas, size, scale, ticker, paint, limit, active,
                        if (active) envelope.value(ticker.sandTime) else 0f, step, staticPoints)
                } finally {
                    if (active) Trace.endSection()
                }
                if (active) ticker.reportFrame(System.nanoTime() - started)
            }
        }) {}
    }
}
