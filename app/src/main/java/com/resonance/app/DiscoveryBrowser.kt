package com.resonance.app

import java.util.Locale

data class DiscoveryEntry(val song: QqCatalogueTrack, val seedMids: List<String>, val locallyHeard: Boolean, val reason: String)

/** Browsing order uses real feedback and direct relationships; it never creates genre labels. */
object DiscoveryBrowser {
    fun entries(state: LibraryState, query: String = "", unheardOnly: Boolean = false, genre: String? = null): List<DiscoveryEntry> {
        val favoriteIds = state.favorites.map { it.mid }.toSet()
        val favoriteRecordings = state.favorites.mapNotNull { state.genres[it.mid]?.recordingId?.takeIf(String::isNotBlank) }.toSet()
        val heard = state.listening.map { it.mediaId.removePrefix("qq:") }.toSet()
        val recent = state.scoreSignals.takeLast(20)
        val rejected = state.listening.takeLast(10).filter { it.category != "completed" }.map { it.mediaId.removePrefix("qq:") }.toSet()
        val liked = recent.filter { it.category == "completed" }.map { it.mediaId.removePrefix("qq:") }
        val scores=state.genreScores().associateBy {it.label}
        val remaining = state.discoveries.distinctBy { it.mid }.filter { song ->
            val info = state.genres[song.mid]
            state.intent.accepts(info?.labels.orEmpty()) && song.mid !in favoriteIds && song.mid !in rejected &&
                (info?.recordingId.isNullOrBlank() || info?.recordingId !in favoriteRecordings) &&
                (!unheardOnly || song.mid !in heard) &&
                (query.isBlank() || listOf(song.name,song.artist,song.album).any { it.contains(query.trim(),true) }) &&
                (genre == null || genre in info?.labels.orEmpty())
        }.distinctBy { state.genres[it.mid]?.recordingId?.takeIf(String::isNotBlank) ?: "qq:${it.mid}" }.toMutableList()
        val chosen = mutableListOf<DiscoveryEntry>()
        val artistCounts = mutableMapOf<String, Int>()
        val titles=state.allTracks.associate {it.mid to it.name}
        fun artist(song: QqCatalogueTrack) = song.artist.trim().lowercase(Locale.ROOT).ifBlank { song.mid }
        while (remaining.isNotEmpty()) {
            val next = remaining.maxBy { song ->
                val labels=state.genres[song.mid]?.labels.orEmpty()
                val taste=(if(labels.isEmpty())0.0 else labels.sumOf {scores[it]?.combined?:0.0}/labels.size).coerceIn(-10.0,10.0)*3
                val positiveSeed = if (state.discoverySources[song.mid].orEmpty().any { it in liked }) 4 else 0
                taste + positiveSeed - (if (song.mid in heard) 2 else 0) - (artistCounts[artist(song)] ?: 0)*4 -
                    (if (chosen.lastOrNull()?.song?.let(::artist) == artist(song)) 6 else 0)
            }
            remaining.remove(next)
            val seeds = state.discoverySources[next.mid].orEmpty()
            val title = seeds.firstNotNullOfOrNull { titles[it] }
            val reason = when {
                title != null -> "来自「$title」${if(seeds.size>1)"等 ${seeds.size} 首歌" else ""}的相关推荐"
                next.mid in state.discoveryPlaylists -> "来自 QQ 歌单「${state.discoveryPlaylists.getValue(next.mid)}」"
                else -> "曲库候选 · 暂无可显示的关联来源"
            }
            chosen.add(DiscoveryEntry(next,seeds,next.mid in heard,reason))
            artistCounts[artist(next)] = (artistCounts[artist(next)] ?: 0) + 1
        }
        return chosen
    }
}
