package com.resonance.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val WeekDays = 7
private const val MillisPerMinute = 60_000L
private const val DailyGrainLimit = 40
private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal data class ProfileDay(val day: DayOfWeek, val minutes: Long)
internal data class CompletedTrack(val mediaId: String, val timestamp: Long)
internal data class ProfileTimeline(
    val week: List<ProfileDay> = emptyList(),
    val recentCompletions: List<CompletedTrack>? = null,
    val newCompletions: Int = 0
)

private fun loadProfileTimeline(context: Context, scope: String, now: Instant, zone: ZoneId): ProfileTimeline {
    val end = now.atZone(zone).toLocalDate()
    val start = end.minusDays((WeekDays - 1).toLong())
    val heard = LongArray(WeekDays)
    val completed = mutableListOf<CompletedTrack>()
    val events = CatalogueStore.events(context, scope)
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        val timestamp = event.optLong("timestamp")
        if (timestamp <= 0L) continue
        val heardDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val day = (heardDate.toEpochDay() - start.toEpochDay()).toInt()
        if (day in heard.indices) heard[day] += event.optLong("playedMs").coerceAtLeast(0L)
        if (event.optString("category") == "completed") completed.add(CompletedTrack(event.optString("mediaId"), timestamp))
    }
    return ProfileTimeline(heard.mapIndexed { day, ms -> ProfileDay(start.plusDays(day.toLong()).dayOfWeek, ms / MillisPerMinute) }, completed)
}

@Composable
internal fun rememberProfileTimeline(library: LibraryState, active: Boolean, trackId: String): ProfileTimeline {
    val context = LocalContext.current
    var history by remember(library.accountScope) { mutableStateOf(ProfileTimeline()) }
    LaunchedEffect(library.accountScope, active, library.listening, trackId) {
        if (!active) return@LaunchedEffect
        while (true) {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val next = withContext(Dispatchers.IO) { loadProfileTimeline(context, library.accountScope, now, zone) }
            val previous = history.recentCompletions
            val fresh = next.recentCompletions.orEmpty()
            val added = when {
                previous == null -> 0
                previous.isEmpty() -> fresh.size
                else -> {
                    val last = previous.last()
                    val index = fresh.lastIndexOf(last)
                    if (index >= 0) fresh.lastIndex - index else fresh.count { it.timestamp > last.timestamp }
                }
            }
            history = next.copy(newCompletions = history.newCompletions + added)
            val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            delay((midnight.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(MillisPerMinute / 60))
        }
    }
    return history
}

@Composable
internal fun profileMinutes(savedMs: Long, clock: State<PlaybackClock>): State<Long> =
    remember(clock, savedMs) { derivedStateOf { maxOf(savedMs / MillisPerMinute, clock.value.listeningMinutes) } }

@Composable
internal fun ProfileHours(minutes: Long, week: List<ProfileDay>) {
    val font = LocalBricolage.current
    val time = remember(minutes, font) {
        buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(fontSize = TypeSize.companionNumber, fontWeight = FontWeight.ExtraBold,
                fontFamily = font, fontFeatureSettings = "tnum")) { append(minutes.toString()) }
            append(" ")
            withStyle(androidx.compose.ui.text.SpanStyle(fontSize = TypeSize.title, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif)) { append("分钟") }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = Space.x3), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        Text("陪伴时长", fontSize = TypeSize.action, color = ResonancePalette.signal, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(time, Modifier.weight(1f, fill = false), color = ResonancePalette.sand)
            Text("累计听歌时间", Modifier.padding(bottom = Space.x1), fontSize = TypeSize.secondary, color = ResonancePalette.muted)
        }
        val dates = if (week.isNotEmpty()) week else {
            val today = LocalDate.now().dayOfWeek
            List(WeekDays) { ProfileDay(today.minus((WeekDays - 1 - it).toLong()), 0) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val needed = Space.weekTileWidth * WeekDays + Space.progressLine * (WeekDays - 1)
            val fits = maxWidth >= needed
            Row(if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = if (fits) Arrangement.SpaceBetween else Arrangement.spacedBy(Space.progressLine)) {
                dates.forEachIndexed { index, day ->
                    val mine = index == dates.lastIndex
                    val label = weekdays[day.day.value - 1]
                    val border = if (mine) Modifier.border(Space.focus, ResonancePalette.sand, Corners.dockCover) else Modifier
                    Box(Modifier.size(Space.weekTileWidth, Space.miniHeight).clip(Corners.dockCover)
                        .background(ResonancePalette.surface).then(border)
                        .semantics { contentDescription = (if (mine) "今天，" else "") + label + "，听歌 " + day.minutes + " 分钟" }) {
                        if (day.minutes > 0) Box(Modifier.fillMaxSize().padding(bottom = Space.x5)) {
                            SandPlate("week:" + day.day, Modifier.matchParentSize(), grainCount = day.minutes.coerceAtMost(DailyGrainLimit.toLong()).toInt(),
                                background = ResonancePalette.surface)
                        }
                        Text(label, Modifier.align(Alignment.BottomCenter).padding(bottom = Space.x1),
                            fontSize = TypeSize.caption, color = if (mine) ResonancePalette.sand else ResonancePalette.muted,
                            fontWeight = if (mine) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}
