package com.resonance.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs

/** The two sides still edit the same wanted/excluded sets, only when the draft is applied. */
internal fun ListeningIntent.tagPosition(label: String): Float = when (label) {
    in excluded -> -1f
    in wanted -> 1f
    else -> 0f
}

internal fun ListeningIntent.withTagPosition(label: String, position: Float): ListeningIntent = copy(
    wanted = if (position >= .5f) wanted + label else wanted - label,
    excluded = if (position <= -.5f) excluded + label else excluded - label
)

@Composable
internal fun IntentEditor(genres: Map<String, CatalogueGenre>, draft: ListeningIntent,
    onDraft: (ListeningIntent) -> Unit, onApply: () -> Unit, onClear: () -> Unit) {
    val labels = remember(genres) {
        (genres.values.flatMap { it.labels } + listOf("pop", "rock", "folk", "jazz", "electronic", "r&b", "hip hop"))
            .distinct().sorted()
    }
    val ticker = remember { FrameTicker() }
    val renderer = remember { HomeSandRenderer() }
    val animate = LocalResonanceMotion.current
    var dragging by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val editing = dragging
    val balance = if (editing == null) {
        ((draft.wanted.size - draft.excluded.size) / (draft.wanted.size + draft.excluded.size).coerceAtLeast(1).toFloat())
    } else editing.second
    RunResonanceFrames(ticker, animate, continuous = false, drawSand = true)
    SandPlate("intent-preview", Modifier.fillMaxWidth().aspectRatio(3f), density = .22f,
        animation = SandAnimation(renderer, ticker, visible = true, playing = false,
            match = (balance + 1f) / 2, liveClarity = true, priority = 2))
    Text("检查歌曲的全部已有标签。想听可多选，避开项优先；条件不会替歌曲添加标签。",
        fontSize = TypeSize.secondary, color = ResonancePalette.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
        Text("暂时避开（多选）", fontSize = TypeSize.secondary, color = ResonancePalette.muted, modifier = Modifier.weight(1f))
        Text("想听（多选）", fontSize = TypeSize.secondary, color = ResonancePalette.muted)
    }
    labels.forEach { label ->
        key(label) {
            val tag = genreName(label)
            val position = draft.tagPosition(label)
            val shown = editing?.takeIf { it.first == label }?.second
            val value = scoreMotion(position, Motion.Score, Motion.settle, ticker, animate)
            val line = scoreMotion(position, Motion.ScoreBar, Motion.responsive, ticker, animate)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction("避开$tag", { onDraft(draft.withTagPosition(label, (position - 1).coerceAtLeast(-1f))) },
                    ResonancePalette.signal, outline = true) { Text("−", color = ResonancePalette.signal, fontSize = TypeSize.title) }
                ScoreRow(tag, scoreDisplay(shown ?: value.value), line, Modifier.weight(1f), bar = {
                    IntentSlider(shown ?: line.value,
                        { dragging = label to it },
                        { next -> dragging = null; onDraft(draft.withTagPosition(label, next)) })
                })
                IconAction("想听$tag", { onDraft(draft.withTagPosition(label, (position + 1).coerceAtMost(1f))) },
                    ResonancePalette.sand, outline = true) { Text("+", fontSize = TypeSize.title) }
            }
            HorizontalDivider(color = ResonancePalette.line)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("同时满足所有想听标签", fontSize = TypeSize.body, fontWeight = FontWeight.Bold)
            Text("关闭时命中任意一个即可；开启时必须全部命中。", fontSize = TypeSize.caption, color = ResonancePalette.muted)
        }
        Switch(draft.matchAll, { onDraft(draft.copy(matchAll = it)) })
    }
    Text("有筛选条件时，未知曲风暂不进入推荐或惊喜抽取。只有“流行”等大类时，仍无法据此区分情绪与节奏。手动从收藏点歌不受这些推荐条件限制。",
        fontSize = TypeSize.caption, color = ResonancePalette.muted)
    PrimaryButton("应用并推荐", onApply, Modifier.fillMaxWidth())
    TextAction("清除本次条件", onClear, ResonancePalette.signal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntentSlider(value: Float, onDrag: (Float) -> Unit, onDrop: (Float) -> Unit) {
    val latest by rememberUpdatedState(value)
    Slider(value, onValueChange = onDrag, valueRange = -1f..1f,
        onValueChangeFinished = { onDrop(latest) },
        thumb = {
            androidx.compose.foundation.Canvas(Modifier.size(Space.x2, Space.x4)) {
                drawRect(ResonancePalette.signal)
            }
        },
        track = { state -> SignedScoreBar(rememberUpdatedState(state.value)) })
}

internal fun scoreDisplay(value: Float): String =
    String.format(java.util.Locale.ROOT, "%+.1f", if (abs(value) < .05f) 0f else value).replace('-', '−')
