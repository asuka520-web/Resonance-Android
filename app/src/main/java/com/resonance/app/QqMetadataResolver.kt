package com.resonance.app

data class ParsedQqSong(val mediaId: String, val name: String, val artist: String, val album: String)
data class MetadataResolution(
    val song: ParsedQqSong? = null,
    val invalidateMediaId: String? = null,
    val status: String
)

/** Device-validated ARTIST format only. TITLE is deliberately not an input. */
object QqCompositeArtistParser {
    private val separators = setOf('-', '－', '–', '—')
    private val credits = Regex("^(作词|作曲|编曲|词|曲|制作人|演唱|歌词|lyrics|composer)\\s*[:：]", RegexOption.IGNORE_CASE)

    fun parse(mediaId: String?, compositeArtist: String, album: String): ParsedQqSong? {
        val id = mediaId?.trim().orEmpty()
        val raw = compositeArtist.trim()
        if (id.isBlank() || id.any(Char::isISOControl) || raw.length > 300 || album.length > 300 ||
            raw.any(Char::isISOControl) || album.any(Char::isISOControl)) return null
        val positions = raw.indices.filter { raw[it] in separators }
        if (positions.size != 1) return null // Multiple hyphens cannot be split reliably.
        val name = raw.substring(0, positions[0]).trim()
        val artist = raw.substring(positions[0] + 1).trim()
        if (name.isBlank() || artist.isBlank() || credits.containsMatchIn(name) || credits.containsMatchIn(artist)) return null
        return ParsedQqSong(id, name, artist, album.trim())
    }
}

class QqMetadataResolver {
    private var activeId: String? = null
    private var candidate: ParsedQqSong? = null
    private var startedAt = 0L
    private var samples = 0
    private var accepted: ParsedQqSong? = null
    private var blocked = false

    fun reset() {
        activeId = null
        candidate = null
        samples = 0
        accepted = null
        blocked = false
    }

    fun observe(mediaId: String?, compositeArtist: String, album: String, nowMs: Long): MetadataResolution {
        val id = mediaId?.takeIf { it.isNotBlank() }
        if (id == null) {
            reset()
            return MetadataResolution(status = "缺少媒体 ID，无法自动关联歌曲")
        }
        if (activeId != id) {
            reset()
            activeId = id
        }
        if (blocked) return MetadataResolution(status = "同一 ID 内歌曲字段有变化，请人工核实")
        val parsed = QqCompositeArtistParser.parse(id, compositeArtist, album)
        val old = accepted
        if (old != null && (parsed == null || parsed.name != old.name || parsed.artist != old.artist)) {
            blocked = true
            return MetadataResolution(invalidateMediaId = id, status = "歌曲字段不再稳定，已停用自动缓存，请人工核实")
        }
        if (parsed == null) {
            candidate = null
            samples = 0
            return MetadataResolution(invalidateMediaId = id, status = "ARTIST 格式缺失或有歧义，请人工确认")
        }
        if (candidate != parsed || nowMs < startedAt) {
            candidate = parsed
            startedAt = nowMs
            samples = 1
        } else samples++
        if (samples < 3 || nowMs - startedAt < 2400L) {
            return MetadataResolution(status = "正在观察 QQ 音乐歌曲信息的稳定性…")
        }
        accepted = parsed
        return MetadataResolution(song = parsed, status = "已从 QQ 音乐稳定的 ARTIST 字段读取歌曲信息")
    }
}
