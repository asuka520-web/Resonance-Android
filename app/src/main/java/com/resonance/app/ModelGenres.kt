package com.resonance.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

internal enum class ModelGenreAssessment { AGREEMENT, INSUFFICIENT, DIFFERENT }

internal data class ModelGenreResult(val assessment: ModelGenreAssessment, val labels: List<String> = emptyList())

internal data class ModelGenreWork(val plan: GenreSamplePlan, val manual: String? = null)

internal data class ModelGenreEntry(val labels: List<String>, val status: String, val requested: Boolean)

internal object ModelGenres {
    const val SETTING = "playback_model_genres"
    const val RATE = 16_000
    private const val INPUT_SIZE = 15_600
    private const val CLASSES = 521
    private const val VERSION = "yamnet-v1"
    private const val RETRY_PREFIX = "@retry/"
    private const val FINISHED_PREFIX = "@finished/"
    private const val DISMISSED = "@dismissed"
    private const val MODEL_SIZE = 4_126_810
    private const val SHA256 = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"
    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
    private val genreIndices = linkedMapOf(
        211 to "流行", 212 to "嘻哈", 214 to "摇滚", 215 to "金属", 216 to "朋克摇滚",
        221 to "R&B", 222 to "灵魂乐", 223 to "雷鬼", 224 to "乡村",
        227 to "放克", 228 to "民谣", 230 to "爵士", 231 to "迪斯科", 232 to "古典",
        234 to "电子", 235 to "House", 236 to "Techno", 237 to "Dubstep",
        238 to "Drum and Bass", 239 to "Electronica", 241 to "氛围音乐", 242 to "Trance",
        244 to "萨尔萨", 246 to "布鲁斯", 252 to "Afrobeat", 254 to "福音音乐", 258 to "Ska"
    )
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private fun preferences(context: Context) = context.getSharedPreferences("resonance_model_genres", Context.MODE_PRIVATE)
    private fun key(scope: String, song: QqCatalogueTrack) = "$VERSION/$scope/${song.mid}/${listOf(song.name, song.artist, song.album, song.durationMs).hashCode()}"

    private fun entry(context: Context, scope: String, song: QqCatalogueTrack) =
        preferences(context).getString(key(scope, song), null)

    private fun labels(entry: String?): List<String> =
        entry.orEmpty().takeUnless { it.startsWith('@') }.orEmpty().split('|').filter { it.isNotBlank() }

    private fun requestKey(scope: String, song: QqCatalogueTrack) = key(scope, song) + "/manual"

    fun manualRequest(context: Context, scope: String, song: QqCatalogueTrack): String? =
        preferences(context).getString(requestKey(scope, song), null)

    fun sampleWork(context: Context, scope: String, song: QqCatalogueTrack, durationMs: Long,
                   auto: Boolean): ModelGenreWork? {
        val manual = manualRequest(context, scope, song)
        val saved = entry(context, scope, song)
        val plan = when {
            manual != null -> if (manual.endsWith(":LATER")) GenreSamplePlan.LATER else GenreSamplePlan.PRIMARY
            !auto -> return null
            saved == null -> GenreSamplePlan.PRIMARY
            saved.startsWith(RETRY_PREFIX) -> GenreSamplePlan.LATER
            else -> return null
        }
        return ModelGenreWork(plan, manual).takeIf { durationMs >= plan.endMs }
    }

    fun read(context: Context, scope: String, song: QqCatalogueTrack, hasCatalogueGenres: Boolean = false): ModelGenreEntry {
        val saved = entry(context, scope, song)
        val manual = manualRequest(context, scope, song)
        val tags = labels(saved)
        val status = when {
            manual?.endsWith(":LATER") == true -> "待分析后段，播放至 1 分 35 秒"
            manual != null -> "已安排，从头播放时分析"
            tags.isNotEmpty() -> "有模型结果"
            saved == DISMISSED -> "已移除模型标签"
            saved?.startsWith(RETRY_PREFIX) == true -> "待分析后段"
            saved?.endsWith(ModelGenreAssessment.DIFFERENT.name) == true -> "片段曲风不同"
            saved != null -> "暂无模型结果"
            song.durationMs in 1 until GenreSamplePlan.PRIMARY.endMs -> "时长不足"
            hasCatalogueGenres -> "可手动分析"
            else -> "待分析"
        }
        return ModelGenreEntry(tags, status, manual != null)
    }

    fun request(context: Context, scope: String, song: QqCatalogueTrack) {
        if (manualRequest(context, scope, song) != null) return
        preferences(context).edit().putString(requestKey(scope, song), "${UUID.randomUUID()}:PRIMARY").apply()
        changes.value++
    }

    fun record(context: Context, scope: String, song: QqCatalogueTrack, work: ModelGenreWork,
               result: ModelGenreResult, retryLater: Boolean): Boolean {
        val manualKey = requestKey(scope, song)
        val prefs = preferences(context)
        if (prefs.getString(manualKey, null) != work.manual || entry(context, scope, song) == DISMISSED && work.manual == null) return false
        val saved = entry(context, scope, song)
        val value = if (result.labels.isNotEmpty()) result.labels.joinToString("|")
            else if (work.manual != null && labels(saved).isNotEmpty()) saved
            else (if (retryLater) RETRY_PREFIX else FINISHED_PREFIX) + result.assessment.name
        val nextManual = work.manual?.substringBefore(':')?.takeIf { retryLater }?.plus(":LATER")
        prefs.edit().putString(key(scope, song), value).putString(manualKey, nextManual).apply()
        changes.value++
        return true
    }

    fun dismiss(context: Context, scope: String, song: QqCatalogueTrack) {
        preferences(context).edit().putString(key(scope, song), DISMISSED).remove(requestKey(scope, song)).apply()
        changes.value++
    }

    fun ensureModel(filesDir: File): File {
        val model = File(filesDir, "$VERSION.tflite")
        fun valid(file: File): Boolean = file.length() == MODEL_SIZE.toLong() && file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count == -1) break
                digest.update(chunk, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) } == SHA256
        }
        if (valid(model)) return model
        val temporary = File.createTempFile("yamnet-", ".tflite", filesDir)
        try {
            val connection = URL(MODEL_URL).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output ->
                    val chunk = ByteArray(8192)
                    var remaining = MODEL_SIZE
                    while (true) {
                        val count = input.read(chunk)
                        if (count == -1) break
                        remaining -= count
                        if (remaining < 0) throw IOException("Unexpected model size")
                        output.write(chunk, 0, count)
                    }
                }
            }
            if (!valid(temporary) || !temporary.renameTo(model)) throw IOException("Could not verify audio model")
            return model
        } finally {
            temporary.delete()
        }
    }

    fun classify(file: File, audio: PlaybackGenreAudio): ModelGenreResult =
        Interpreter(file, Interpreter.Options().setNumThreads(1)).use { model ->
            val input = model.getInputTensor(0)
            val output = model.getOutputTensor(0)
            check(input.dataType() == DataType.FLOAT32 && input.numElements() == INPUT_SIZE)
            check(output.dataType() == DataType.FLOAT32 && output.numElements() == CLASSES)
            val waveform = ByteBuffer.allocateDirect(INPUT_SIZE * 4).order(ByteOrder.nativeOrder())
            val scores = ByteBuffer.allocateDirect(CLASSES * 4).order(ByteOrder.nativeOrder())
            fun windows(samples: ShortArray) = listOf(1, 4, 7, 10, 13).map { second ->
                waveform.clear()
                val start = second * RATE
                for (i in start until start + INPUT_SIZE) waveform.putFloat(samples[i] / 32768f)
                waveform.rewind()
                scores.clear()
                model.run(waveform, scores)
                FloatArray(CLASSES) { scores.getFloat(it * 4) }
            }
            assess(windows(audio.first), windows(audio.second))
        }

    internal fun assess(first: List<FloatArray>, second: List<FloatArray>): ModelGenreResult {
        fun scores(windows: List<FloatArray>) = genreIndices.mapNotNull { (index, label) ->
            val matches = windows.filter { it[132] >= .5f && it[index] >= .4f }
            if (matches.size < 3) null else label to matches.map { it[index] }.average()
        }.toMap()
        val a = scores(first)
        val b = scores(second)
        if (a.isEmpty() || b.isEmpty()) return ModelGenreResult(ModelGenreAssessment.INSUFFICIENT)
        val shared = a.keys.intersect(b.keys).sortedByDescending { minOf(a.getValue(it), b.getValue(it)) }.take(3)
        return if (shared.isEmpty()) ModelGenreResult(ModelGenreAssessment.DIFFERENT)
            else ModelGenreResult(ModelGenreAssessment.AGREEMENT, shared)
    }
}
