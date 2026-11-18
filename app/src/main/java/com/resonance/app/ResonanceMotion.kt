package com.resonance.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** All timing is in milliseconds. A FrameTicker advances only while the window can draw. */
internal object Motion {
    const val Scatter = 220
    const val Gather = 700
    const val ParticleDelay = 300
    const val TrackChange = Scatter + Gather + ParticleDelay
    const val Cover = 200
    const val CoverInitialScale = .96f
    const val SandRest = 300
    const val LogoRest = 250
    const val LogoMinPeriod = 900
    const val LogoMaxPeriod = 1300
    const val Score = 250
    const val ScoreBar = 320
    const val Strike = 500
    const val StrikeStiffness = 180.0
    const val StrikeDamping = .6
    const val Clarity = 600
    const val DefaultMatch = .5f
    const val BatchExit = 200
    const val BatchEnter = 500
    const val BatchExitStagger = 50
    const val BatchEnterStagger = 60
    const val Press = 90
    const val Release = 160
    const val PressScale = .97f
    const val PlayMorph = 180
    const val Navigation = 240
    const val ProgressSample = 1000
    const val SeekThreshold = 500
    const val SlowFrameNanos = 14_000_000L
    const val SlowFrameCount = 6
    const val ReducedFrameNanos = 1_000_000_000L / 30
    const val NanosPerMs = 1_000_000L
    val accelerate = Easing { it * it }
    val settle = Easing { val rest = 1f - it; 1f - rest * rest * rest }
    val easeInOut = CubicBezierEasing(.42f, 0f, .58f, 1f)
    val responsive = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val release = CubicBezierEasing(.2f, 1.25f, .5f, 1f)
    val linear = LinearEasing

    fun progress(ageMs: Float, duration: Int): Float = (ageMs / duration).coerceIn(0f, 1f)
    fun mix(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction
    fun sigma(match: Float): Float = mix(2.4f, .6f, match.coerceIn(0f, 1f))
    fun knock(ageMs: Float): Float {
        if (ageMs < 0f || ageMs >= Strike) return 0f
        val t = ageMs / 1000.0
        val w = sqrt(StrikeStiffness)
        val wd = w * sqrt(1.0 - StrikeDamping * StrikeDamping)
        return (3 * exp(-StrikeDamping * w * t) *
            (cos(wd * t) + StrikeDamping * w / wd * sin(wd * t))).toFloat()
    }

    fun logoHeight(index: Int, timeMs: Float, envelope: Float): Float {
        val rest = logoRestHeights[index]
        if (envelope <= 0f) return rest
        val period = mix(LogoMinPeriod.toFloat(), LogoMaxPeriod.toFloat(), index / 3f)
        val phase = (timeMs / period + index * .23f) % 1f
        val cycle = if (phase < .5f) phase * 2f else (1f - phase) * 2f
        return mix(rest, mix(.4f, 1f, easeInOut.transform(cycle)), envelope)
    }

    fun sandAmplitude(timeSeconds: Float): Float {
        val level = .5 + .3 * sin(2 * PI * 1.7 * timeSeconds) + .2 * sin(2 * PI * .43 * timeSeconds + 1)
        return mix(.4f, 2f, level.toFloat().coerceIn(0f, 1f))
    }

    private val logoRestHeights = floatArrayOf(.19f, .55f, .35f, .69f)
}

internal val LocalResonanceMotion = staticCompositionLocalOf { false }

internal class FrameTicker {
    var time by mutableLongStateOf(0L)
        private set
    var sandTime by mutableLongStateOf(0L)
        private set
    private var requestedUntil by mutableLongStateOf(0L)
    private var sandUntil by mutableLongStateOf(0L)
    private var slowFrames = 0
    private var slowWindowFrames = 0
    var reducedQuality by mutableStateOf(false)
        private set

    fun request(durationMs: Int, sand: Boolean = false): Long = time.also {
        val end = it + durationMs * Motion.NanosPerMs
        if (sand) sandUntil = maxOf(sandUntil, end) else requestedUntil = maxOf(requestedUntil, end)
    }

    fun cancelSand() { sandUntil = 0L }
    fun hasSandWork(): Boolean = time < sandUntil

    fun reportFrame(costNanos: Long, windowFrame: Boolean = false) {
        if (reducedQuality) return
        val previous = if (windowFrame) slowWindowFrames else slowFrames
        val consecutive = if (costNanos > Motion.SlowFrameNanos) previous + 1 else 0
        if (windowFrame) slowWindowFrames = consecutive else slowFrames = consecutive
        if (consecutive >= Motion.SlowFrameCount) reducedQuality = true
    }

    suspend fun run(continuous: Boolean, drawSand: Boolean) {
        val infiniteAnimation = if (continuous) coroutineContext[InfiniteAnimationPolicy] else null
        snapshotFlow { maxOf(requestedUntil, if (drawSand) sandUntil else 0L) }.collectLatest { requested ->
            var lastFrame = 0L
            val advance: (Long) -> Unit = { frame ->
                if (lastFrame != 0L) time += (frame - lastFrame).coerceAtLeast(0)
                lastFrame = frame
                // Small tolerance keeps every other tick at a nominal 60 Hz.
                if (drawSand && (!reducedQuality || time - sandTime >= Motion.ReducedFrameNanos - Motion.NanosPerMs ||
                        (!continuous && time >= requested))) sandTime = time
            }
            while (continuous || time < requested) {
                if (infiniteAnimation == null || time < requested) withFrameNanos(advance)
                else infiniteAnimation.onInfiniteOperation { withFrameNanos(advance) }
            }
        }
    }

    fun age(startedAt: Long, sand: Boolean = false): Float =
        ((if (sand) sandTime else time) - startedAt).coerceAtLeast(0) / Motion.NanosPerMs.toFloat()

    suspend fun await(durationMs: Int, available: State<Boolean>) {
        val end = request(durationMs) + durationMs * Motion.NanosPerMs
        snapshotFlow { time >= end || !available.value }.first { it }
    }
}

@Composable
internal fun RunResonanceFrames(ticker: FrameTicker, enabled: Boolean, continuous: Boolean, drawSand: Boolean) {
    LaunchedEffect(ticker, enabled, continuous, drawSand) {
        if (enabled) ticker.run(continuous, drawSand)
    }
}

/** Observe actual Android frame work as well as the board's draw-submission cost. */
@Composable
internal fun MonitorResonanceFrames(ticker: FrameTicker, active: Boolean) {
    val context = LocalContext.current
    val window = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
    }
    DisposableEffect(window, active, ticker) {
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            ticker.reportFrame(metrics.getMetric(FrameMetrics.TOTAL_DURATION), windowFrame = true)
        }
        if (active && window != null) window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        onDispose { if (active && window != null) window.removeOnFrameMetricsAvailableListener(listener) }
    }
}

internal data class MotionEnvironment(val windowReady: Boolean, val systemEnabled: Boolean) {
    val enabled get() = windowReady && systemEnabled
}

@Composable
internal fun rememberMotionEnvironment(): MotionEnvironment {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    fun animationsAllowed(): Boolean = ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    var interactive by remember(power) { mutableStateOf(power?.isInteractive != false) }
    var systemAnimations by remember(context) { mutableStateOf(animationsAllowed()) }
    DisposableEffect(lifecycle, context, power) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            interactive = power?.isInteractive != false
            systemAnimations = animationsAllowed()
        }
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                interactive = intent.action != Intent.ACTION_SCREEN_OFF && power?.isInteractive != false
                systemAnimations = animationsAllowed()
            }
        }
        val scale = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemAnimations = animationsAllowed() }
        }
        lifecycle.addObserver(observer)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scale)
        onDispose {
            context.contentResolver.unregisterContentObserver(scale)
            context.unregisterReceiver(screen)
            lifecycle.removeObserver(observer)
        }
    }
    return MotionEnvironment(resumed && interactive && window.isWindowFocused, systemAnimations)
}

internal data class BatchSandMotion(val startedAt: Long, val entering: Boolean)

internal class MotionFloat(initial: Float) {
    private data class Change(val from: Float, val to: Float, val at: Long, val duration: Int, val ease: Easing)
    private var change by mutableStateOf(Change(initial, initial, 0L, 0, Motion.linear))

    fun update(target: Float, duration: Int, ease: Easing, ticker: FrameTicker, animate: Boolean, sand: Boolean = false) {
        if (target == change.to && (animate || change.duration == 0)) return
        val from = value(ticker.time)
        change = if (animate && from != target) Change(from, target, ticker.request(duration, sand), duration, ease)
            else Change(target, target, ticker.time, 0, ease)
    }

    fun value(time: Long): Float {
        val segment = change
        if (segment.duration == 0) return segment.to
        val t = Motion.progress((time - segment.at).coerceAtLeast(0) / Motion.NanosPerMs.toFloat(), segment.duration)
        return Motion.mix(segment.from, segment.to, segment.ease.transform(t))
    }
}

internal class SmoothPlayhead {
    private var mid = ""
    private var sample = -1L
    private var from = 0f
    private var to = 0f
    private var startedAt = 0L
    private var moving = false

    fun position(songId: String, sampleMs: Long, now: Long, advance: Boolean): Float {
        val t = Motion.progress((now - startedAt).coerceAtLeast(0) / Motion.NanosPerMs.toFloat(), Motion.ProgressSample)
        val previous = Motion.mix(from, to, t)
        if (!advance) {
            moving = false
            return sampleMs.toFloat()
        }
        if (!moving || mid != songId || sampleMs != sample) {
            from = if (!moving || mid != songId || kotlin.math.abs(previous - sampleMs) > Motion.SeekThreshold)
                sampleMs.toFloat() else previous
            to = maxOf(from, sampleMs.toFloat() + Motion.ProgressSample)
            sample = sampleMs
            mid = songId
            startedAt = now
            moving = true
            return from
        }
        return previous
    }
}
