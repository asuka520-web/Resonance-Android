package com.resonance.app

import android.graphics.Paint
import android.os.Trace
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val ParticleStride = 11
private const val MainGrains = 1500
private const val LargeGrains = 320
private const val LooseGrains = 240
private const val SmallGrains = 230
private const val SineTableSize = 2048
private val sineTable = FloatArray(SineTableSize) { sin(2 * PI * it / SineTableSize).toFloat() }
private fun wave(cycles: Float): Float = sineTable[(cycles * SineTableSize).toInt() and (SineTableSize - 1)]

private class CachedPlate(val full: List<FloatArray>? = null, val small: List<FloatArray>? = null)

private val sandCache = LruCache<String, CachedPlate>(20)

private suspend fun loadSand(trackId: String, compact: Boolean): List<FloatArray> {
    val cached = sandCache.get(trackId)?.let { if (compact) it.small else it.full }
    if (cached != null) return cached
    return withContext(Dispatchers.Default) {
        generateSand(trackId, compact, coroutineContext).also { generated ->
            synchronized(sandCache) {
                val entry = sandCache.get(trackId)
                sandCache.put(trackId, if (compact) CachedPlate(entry?.full, generated) else CachedPlate(generated, entry?.small))
            }
        }
    }
}

@Composable
internal fun SandPlate(trackId: String, modifier: Modifier = Modifier,
    ticker: FrameTicker? = null, batch: BatchSandMotion? = null, row: Int = 0) {
    var layers by remember(trackId) { mutableStateOf(sandCache.get(trackId)?.small) }
    LaunchedEffect(trackId) { if (layers == null) layers = loadSand(trackId, compact = true) }
    val points = remember { FloatArray(SmallGrains * 2) }
    val animate = LocalResonanceMotion.current && ticker != null && batch != null
    val exitFade = if (animate && batch?.entering == false) Modifier.graphicsLayer {
        alpha = 1f - Motion.progress(ticker!!.age(batch.startedAt) - row * Motion.BatchExitStagger, Motion.BatchExit)
    } else Modifier
    Canvas(
        modifier.then(exitFade).clip(RoundedCornerShape(14.dp)).background(HomePalette.plate)
            .drawWithCache {
                val jitter = 1.2.dp.toPx()
                val drift = 6.dp.toPx()
                val particles = layers?.single()
                val paint = sandPaint(.5.dp.toPx(), 255)
                val width = size.width
                val height = size.height
                fun project(entry: Float, exit: Float) {
                    if (particles == null) return
                    for (index in 0 until SmallGrains) {
                        val offset = index * ParticleStride
                        val x = particles[offset]
                        val y = particles[offset + 1]
                        val dx = (x - .5f) * width
                        val dy = (y - .5f) * height
                        val radial = if (exit != 0f) drift * exit / sqrt(dx * dx + dy * dy).coerceAtLeast(1f) else 0f
                        points[index * 2] = Motion.mix(particles[offset + 7], x, entry) * width +
                            particles[offset + 2] * jitter + dx * radial
                        points[index * 2 + 1] = Motion.mix(particles[offset + 8], y, entry) * height +
                            particles[offset + 3] * jitter + dy * radial
                    }
                }
                if (!animate) project(1f, 0f)
                onDrawBehind {
                    if (particles != null) {
                        if (animate) {
                            val age = ticker.age(batch.startedAt)
                            if (batch.entering) project(Motion.settle.transform(
                                Motion.progress(age - row * Motion.BatchEnterStagger, Motion.BatchEnter)), 0f)
                            else project(1f, Motion.accelerate.transform(
                                Motion.progress(age - row * Motion.BatchExitStagger, Motion.BatchExit)))
                        }
                        drawContext.canvas.nativeCanvas.drawPoints(points, paint)
                    }
                }
            }
    ) {}
}

internal class HomeSandRenderer {
    // These buffers belong to the board, so replacing a track only changes its immutable target pattern.
    private class Buffer(count: Int) {
        val from = FloatArray(count * 2)
        val points = FloatArray(count * 2)
    }
    private data class Pattern(val trackId: String, val layers: List<FloatArray>, val began: Long?)

    private val buffers = arrayOf(Buffer(MainGrains), Buffer(LargeGrains), Buffer(LooseGrains))
    private var pattern by mutableStateOf<Pattern?>(null)
    private var lastSize = Size.Zero
    private var strikeAt by mutableStateOf<Long?>(null)
    private val clarity = MotionFloat(Motion.sigma(Motion.DefaultMatch))
    private val amplitude = MotionFloat(0f)
    private var playStartedAt by mutableStateOf(0L)

    fun strike(ticker: FrameTicker) { strikeAt = ticker.request(Motion.Strike, sand = true) }

    fun update(playing: Boolean, match: Float, ticker: FrameTicker, animate: Boolean) {
        if (playing && amplitude.value(ticker.time) == 0f) playStartedAt = ticker.time
        amplitude.update(if (playing && animate) 1f else 0f, Motion.SandRest, Motion.settle, ticker, animate, sand = true)
        clarity.update(Motion.sigma(match), Motion.Clarity, Motion.responsive, ticker, animate, sand = true)
    }

    fun setPattern(trackId: String, layers: List<FloatArray>, cold: Boolean, ticker: FrameTicker, animate: Boolean) {
        val before = pattern
        if (before?.trackId == trackId && !(cold && animate && before.began == null)) return
        for (layer in buffers.indices) {
            val buffer = buffers[layer]
            val particles = layers[layer]
            for (index in 0 until buffer.from.size / 2) {
                val point = index * 2
                val offset = index * ParticleStride
                buffer.from[point] = when {
                    cold -> particles[offset + 7]
                    before != null && lastSize.width > 0f -> buffer.points[point] / lastSize.width
                    else -> particles[offset]
                }
                buffer.from[point + 1] = when {
                    cold -> 0f
                    before != null && lastSize.height > 0f -> buffer.points[point + 1] / lastSize.height
                    else -> particles[offset + 1]
                }
            }
        }
        pattern = Pattern(trackId, layers, if (animate && (cold || before != null))
            ticker.request(Motion.TrackChange, sand = true) else null)
    }

    fun settle() {
        val current = pattern
        if (current?.began != null) pattern = current.copy(began = null)
        if (strikeAt != null) strikeAt = null
    }

    fun draw(canvas: android.graphics.Canvas, size: Size, density: Float, ticker: FrameTicker,
        paints: Array<Paint>, animate: Boolean) {
        val current = pattern ?: return
        val begin = current.began
        val age = if (animate && begin != null) ticker.age(begin, sand = true) else Motion.TrackChange.toFloat()
        val scatter = if (age < Motion.Scatter) Motion.accelerate.transform(Motion.progress(age, Motion.Scatter)) else 1f
        val now = if (animate) ticker.sandTime else 0L
        val strike = strikeAt
        val displacement = if (animate && strike != null) Motion.knock(ticker.age(strike, sand = true)) * density else 0f
        val sigma = (if (animate) clarity.value(now) else clarity.value(Long.MAX_VALUE)) * density
        val playTime = (now - playStartedAt).coerceAtLeast(0) / 1_000_000_000f
        val vibration = if (animate) amplitude.value(now) * Motion.sandAmplitude(playTime) * density else 0f
        val reduce = animate && ticker.reducedQuality
        lastSize = size

        for (layerIndex in buffers.indices) {
            val points = buffers[layerIndex].points
            val from = buffers[layerIndex].from
            val particles = current.layers[layerIndex]
            val count = points.size / if (reduce) 4 else 2
            for (index in 0 until count) {
                val offset = index * ParticleStride
                val point = index * 2
                val targetX = particles[offset]
                val targetY = particles[offset + 1]
                val scatteredX = particles[offset + 7]
                val scatteredY = particles[offset + 8]
                var x = targetX
                var y = targetY
                val shake = if (vibration > 0f) vibration * wave(particles[offset + 6] * playTime + particles[offset + 4]) else 0f
                var noise = 1f
                if (age < Motion.Scatter) {
                    x = Motion.mix(from[point], scatteredX, scatter)
                    y = Motion.mix(from[point + 1], scatteredY, scatter)
                    noise = 0f
                } else if (age < Motion.TrackChange) {
                    val delay = particles[offset + 5] * Motion.ParticleDelay
                    noise = Motion.settle.transform(Motion.progress(age - Motion.Scatter - delay, Motion.Gather))
                    x = Motion.mix(scatteredX, targetX, noise)
                    y = Motion.mix(scatteredY, targetY, noise)
                }
                val radialX = (x - .5f) * size.width
                val radialY = (y - .5f) * size.height
                val radial = if (displacement != 0f)
                    displacement / sqrt(radialX * radialX + radialY * radialY).coerceAtLeast(1f) else 0f
                points[point] = x * size.width + particles[offset + 2] * sigma * noise +
                    particles[offset + 9] * shake * noise + radialX * radial
                points[point + 1] = y * size.height + particles[offset + 3] * sigma * noise +
                    particles[offset + 10] * shake * noise + radialY * radial
            }
            canvas.drawPoints(points, 0, count * 2, paints[layerIndex])
        }
    }
}

@Composable
internal fun AnimatedSandPlate(trackId: String, renderer: HomeSandRenderer, ticker: FrameTicker,
    visible: Boolean, playing: Boolean, match: Float, cold: Boolean, systemMotion: Boolean,
    onFirstPattern: () -> Unit, modifier: Modifier = Modifier) {
    val animate = LocalResonanceMotion.current && visible
    var loaded by remember(trackId) { mutableStateOf(sandCache.get(trackId)?.full) }
    LaunchedEffect(trackId) { if (loaded == null) loaded = loadSand(trackId, compact = false) }
    LaunchedEffect(trackId, loaded, animate, cold, systemMotion) {
        loaded?.let { layers ->
            renderer.setPattern(trackId, layers, cold, ticker, animate)
            if (cold && (animate || !systemMotion)) onFirstPattern()
        }
    }
    LaunchedEffect(playing, match, animate) {
        if (!animate) {
            renderer.settle()
            ticker.cancelSand()
        }
        renderer.update(playing, match, ticker, animate)
    }
    Canvas(modifier.clip(RoundedCornerShape(32.dp)).background(HomePalette.plate).drawWithCache {
        val paints = arrayOf(sandPaint(.85.dp.toPx(), 255), sandPaint(1.3.dp.toPx(), 255), sandPaint(.6.dp.toPx(), 89))
        val scale = density
        onDrawBehind {
            if (animate) {
                val startedAt = System.nanoTime()
                Trace.beginSection("ResonanceSandPlate")
                try {
                    renderer.draw(drawContext.canvas.nativeCanvas, size, scale, ticker, paints, true)
                } finally {
                    Trace.endSection()
                }
                ticker.reportFrame(System.nanoTime() - startedAt)
            } else {
                renderer.draw(drawContext.canvas.nativeCanvas, size, scale, ticker, paints, false)
            }
        }
    }) {}
}

private fun sandPaint(radius: Float, opacity: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = HomePalette.sand.toArgb()
    alpha = opacity
    strokeWidth = radius * 2
    strokeCap = Paint.Cap.ROUND
}

private fun generateSand(trackId: String, compact: Boolean, context: CoroutineContext): List<FloatArray> {
    val hash = trackId.hashCode() and Int.MAX_VALUE
    val n = 1 + hash % 5
    val mCandidate = 1 + (hash shr 8) % 6
    val m = if (mCandidate == n) mCandidate % 6 + 1 else mCandidate
    val random = Random(hash.toLong())
    val dynamics = Random(hash.toLong() xor 0x517cc1b7L)
    val nPi = n * PI
    val mPi = m * PI

    fun layer(count: Int, epsilon: Double?): FloatArray {
        val particles = FloatArray(count * ParticleStride)
        for (index in 0 until count) {
            if (index % 128 == 0) context.ensureActive()
            var x: Double
            var y: Double
            do {
                x = random.nextDouble()
                y = random.nextDouble()
            } while (epsilon != null &&
                abs(cos(nPi * x) * cos(mPi * y) - cos(mPi * x) * cos(nPi * y)) >= epsilon * random.nextDouble())
            val offset = index * ParticleStride
            particles[offset] = x.toFloat()
            particles[offset + 1] = y.toFloat()
            // Offsets in standard deviations, phase in cycles, and normalized delay.
            particles[offset + 2] = random.nextGaussian().toFloat()
            particles[offset + 3] = random.nextGaussian().toFloat()
            particles[offset + 4] = random.nextDouble().toFloat()
            particles[offset + 5] = random.nextFloat()
            particles[offset + 6] = 6f + 3f * dynamics.nextFloat()
            particles[offset + 7] = dynamics.nextFloat()
            particles[offset + 8] = dynamics.nextFloat()
            val direction = 2 * PI * dynamics.nextDouble()
            particles[offset + 9] = cos(direction).toFloat()
            particles[offset + 10] = sin(direction).toFloat()
        }
        return particles
    }

    return if (compact) listOf(layer(SmallGrains, .40)) else listOf(
        layer(MainGrains, .40),
        layer(LargeGrains, .12),
        layer(LooseGrains, null)
    )
}
