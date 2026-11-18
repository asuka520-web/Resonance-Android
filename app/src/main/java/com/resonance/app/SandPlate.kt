package com.resonance.app

import android.graphics.Paint
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

private const val ParticleStride = 6

private class SandLayer(val particles: FloatArray, val radiusDp: Float, val opacity: Int)
private class CachedPlate(val full: List<SandLayer>? = null, val small: List<SandLayer>? = null)

private val sandCache = LruCache<String, CachedPlate>(20)

@Composable
internal fun SandPlate(trackId: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    var layers by remember(trackId, compact) {
        mutableStateOf(sandCache.get(trackId)?.let { if (compact) it.small else it.full })
    }
    LaunchedEffect(trackId, compact) {
        if (layers == null) {
            layers = withContext(Dispatchers.Default) {
                generateSand(trackId, compact, coroutineContext).also { generated ->
                    synchronized(sandCache) {
                        val cached = sandCache.get(trackId)
                        val updated = if (compact) CachedPlate(cached?.full, generated)
                            else CachedPlate(generated, cached?.small)
                        sandCache.put(trackId, updated)
                    }
                }
            }
        }
    }
    Canvas(
        modifier.clip(RoundedCornerShape(if (compact) 14.dp else 32.dp)).background(HomePalette.plate)
            .drawWithCache {
                val jitter = 1.2.dp.toPx()
                val batches = layers.orEmpty().map { layer ->
                    val particles = layer.particles
                    val points = FloatArray(particles.size / ParticleStride * 2)
                    for (index in 0 until particles.size / ParticleStride) {
                        val offset = index * ParticleStride
                        points[index * 2] = particles[offset] * size.width + particles[offset + 2] * jitter
                        points[index * 2 + 1] = particles[offset + 1] * size.height + particles[offset + 3] * jitter
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = HomePalette.sand.toArgb()
                        alpha = layer.opacity
                        strokeWidth = layer.radiusDp.dp.toPx() * 2
                        strokeCap = Paint.Cap.ROUND
                    }
                    points to paint
                }
                onDrawBehind {
                    val canvas = drawContext.canvas.nativeCanvas
                    for (index in batches.indices) {
                        val (points, paint) = batches[index]
                        canvas.drawPoints(points, paint)
                    }
                }
            }
    ) {}
}

private fun generateSand(trackId: String, compact: Boolean, context: CoroutineContext): List<SandLayer> {
    val hash = trackId.hashCode() and Int.MAX_VALUE
    val n = 1 + hash % 5
    val mCandidate = 1 + (hash shr 8) % 6
    val m = if (mCandidate == n) mCandidate % 6 + 1 else mCandidate
    val random = Random(hash.toLong())
    val nPi = n * PI
    val mPi = m * PI

    fun layer(count: Int, radius: Float, opacity: Int, epsilon: Double?): SandLayer {
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
            // Standard-normal x/y offsets, phase in radians, and normalized delay.
            particles[offset + 2] = random.nextGaussian().toFloat()
            particles[offset + 3] = random.nextGaussian().toFloat()
            particles[offset + 4] = (random.nextDouble() * 2 * PI).toFloat()
            particles[offset + 5] = random.nextFloat()
        }
        return SandLayer(particles, radius, opacity)
    }

    return if (compact) listOf(layer(230, .5f, 255, .40)) else listOf(
        layer(1500, .85f, 255, .40),
        layer(320, 1.3f, 255, .12),
        layer(240, .6f, 89, null)
    )
}
