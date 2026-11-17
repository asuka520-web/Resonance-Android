package com.resonance.app

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class AutomaticGenreMatch(val recording: RecordingMatch?, val reason: String)

/** Exact metadata evidence, not the search engine's relevance score or an AI confidence percentage. */
object AutomaticGenreMatcher {
    const val VERSION = "automatic-exact-v1"
    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private val englishVersions = listOf("live", "remix", "karaoke", "instrumental", "demo", "acoustic",
        "unplugged", "edit", "remaster", "remastered", "rerecorded")
    private val chineseVersions = listOf("现场", "混音", "伴奏", "纯音乐", "重录", "重新录", "翻唱")
    private fun versions(value: String): Set<String> {
        val text = normalized(value)
        return englishVersions.filter { Regex("\\b$it\\b").containsMatchIn(text) }.toSet() +
            chineseVersions.filter { it in text }
    }

    fun match(song: SongInfo, durationMs: Long, result: RecordingSearchResult): AutomaticGenreMatch {
        if (durationMs <= 0 || song.name.isBlank() || song.artist.isBlank())
            return AutomaticGenreMatch(null, "等待完整歌名、歌手和播放时长，下次播放时自动补齐。")
        if (!result.complete) return AutomaticGenreMatch(null, "曲库候选未完整返回，暂不自动选择版本。")
        val expectedVersions = versions(song.name + " " + song.album)
        val candidates = result.recordings.distinctBy { it.id }.filter { recording ->
            !recording.video && normalized(song.name) == normalized(recording.title) &&
                (normalized(song.artist) == normalized(recording.artist) ||
                    recording.primaryArtistNames.any { normalized(it) == normalized(song.artist) }) &&
                recording.durationMs > 0 && abs(recording.durationMs - durationMs) <= 3000 &&
                (song.album.isBlank() || recording.albumTitles.any { normalized(it) == normalized(song.album) }) &&
                (versions(recording.title + " " + recording.version) - expectedVersions).isEmpty()
        }
        if (candidates.size != 1) return AutomaticGenreMatch(null,
            if (candidates.isEmpty()) "没有唯一可靠的版本匹配，可继续听歌或手动核对。" else "有 ${candidates.size} 个相符录音，版本有歧义，保留待核对。")
        return AutomaticGenreMatch(candidates.single(), "歌名、歌手、时长（±3 秒）" +
            (if (song.album.isNotBlank()) "及专辑" else "") + "相符，返回候选中唯一匹配。")
    }
}
