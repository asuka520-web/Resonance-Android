package com.resonance.app

import android.content.Context
import kotlinx.coroutines.*

data class LibraryState(
    val accountScope: String = "", val favorites: List<QqCatalogueTrack> = emptyList(),
    val discoveries: List<QqCatalogueTrack> = emptyList(), val playlists: List<QqCataloguePlaylist> = emptyList(),
    val genres: Map<String, CatalogueGenre> = emptyMap(), val syncing: Boolean = false,
    val analyzing: Boolean = false, val message: String = "连接 QQ 音乐，让收藏变成懂你的推荐。",
    val recommendation: StylePlan? = null, val revision: Long = 0,
    val openedPlaylist: QqCataloguePlaylist? = null, val playlistTracks: List<QqCatalogueTrack> = emptyList()
) {
    val allTracks get() = (favorites + discoveries).distinctBy { it.mid }
    val taggedCount get() = favorites.count { genres[it.mid]?.labels?.isNotEmpty() == true }
}

object QqLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private var session: QqAccountSession? = null
    private var syncJob: Job? = null
    private var genreJob: Job? = null
    private var discoveryJob: Job? = null
    private val discoveredSeeds = mutableSetOf<String>()
    private val priority = linkedSetOf<String>()
    private var recommendationAnchor: String? = null
    private var generation = 0L
    @Volatile private var value = LibraryState()
    private val listeners = mutableSetOf<(LibraryState) -> Unit>()
    val state get() = value
    fun subscribe(listener: (LibraryState) -> Unit) { listeners.add(listener); listener(value) }
    fun unsubscribe(listener: (LibraryState) -> Unit) { listeners.remove(listener) }
    private fun update(next: LibraryState) { value = next.copy(revision = value.revision + 1); listeners.toList().forEach { it(value) } }
    fun notice(message: String) = update(value.copy(message = message))

    fun activate(context: Context, account: QqAccountSession?) {
        app = context.applicationContext
        session = account
        val key = account?.let { CatalogueStore.scope(it.userId) }.orEmpty()
        if (key != value.accountScope) {
            generation++
            syncJob?.cancel(); genreJob?.cancel(); discoveryJob?.cancel()
            discoveredSeeds.clear(); priority.clear(); recommendationAnchor = null
            val saved = if (key.isBlank()) emptyList() else CatalogueStore.songs(context, key)
            val genres = saved.mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
            update(LibraryState(accountScope = key, favorites = saved, genres = genres,
                message = if (key.isBlank()) "连接 QQ 音乐，让收藏变成懂你的推荐。" else "QQ 音乐已连接，正在准备你的收藏。"))
        }
        if (account != null) {
            if (value.favorites.isEmpty() || System.currentTimeMillis() - CatalogueStore.syncedAt(context, key) > 6 * 60 * 60 * 1000L) syncFavorites()
            else analyze()
        }
    }

    fun syncFavorites() {
        val context = app ?: return
        val account = session ?: return
        if (syncJob?.isActive == true) return
        val token = generation
        val key = value.accountScope
        val previous = value.favorites
        syncJob = scope.launch {
            val accumulator = CataloguePageAccumulator()
            var complete = false
            update(value.copy(syncing = true, message = "正在同步 QQ 收藏…"))
            try {
                var offset = 0
                for (pageNumber in 0 until 100) {
                    val page = QqAccountClient.trackPage(account, null, offset)
                    accumulator.append(page)
                    if (token != generation) return@launch
                    val loaded = accumulator.tracks
                    val cachedGenres = loaded.mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
                    update(value.copy(favorites = loaded, genres = value.genres + cachedGenres,
                        message = "已同步 ${loaded.size}${page.total?.let { " / $it" }.orEmpty()} 首收藏"))
                    if (!page.hasMore) { complete = page.total == null || page.nextOffset >= page.total; break }
                    if (page.nextOffset <= offset) throw QqConnectionException("曲库分页未前进，已停止本轮同步。")
                    offset = page.nextOffset
                    delay(350)
                }
                if (token == generation) {
                    val retained = if (complete) accumulator.tracks else (accumulator.tracks + previous).distinctBy { it.mid }
                    CatalogueStore.save(context, key, retained, complete)
                    update(value.copy(favorites = retained, message = if (complete) "收藏已同步，曲风资料自动补齐中。" else "已同步本轮收藏，剩余部分可稍后继续。"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) {
                    val retained = (accumulator.tracks + previous).distinctBy { it.mid }
                    if (retained.isNotEmpty()) CatalogueStore.save(context, key, retained, false)
                    update(value.copy(favorites = retained, message = (e as? QqConnectionException)?.publicReason ?: "收藏同步暂未完成，已有资料已保留。"))
                }
            } finally {
                if (token == generation) { update(value.copy(syncing = false)); analyze() }
            }
        }
    }

    fun loadPlaylists() {
        val account = session ?: return
        val token = generation
        scope.launch {
            try {
                val playlists = QqAccountClient.playlists(account)
                if (token == generation) update(value.copy(playlists = playlists))
            } catch (_: Exception) { if (token == generation) notice("歌单暂时未能读取，可稍后再试。") }
        }
    }
    fun openPlaylist(playlist: QqCataloguePlaylist) {
        val account = session ?: return
        val context = app ?: return
        val token = generation
        scope.launch {
            try {
                val tracks = QqAccountClient.tracks(account, playlist.id)
                if (token != generation) return@launch
                val cached = tracks.mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
                update(value.copy(discoveries = (tracks + value.discoveries).distinctBy { it.mid }, genres = value.genres + cached,
                    openedPlaylist = playlist, playlistTracks = tracks,
                    message = "已载入「${playlist.name}」的首批歌曲。"))
                analyze()
            } catch (_: Exception) { if (token == generation) notice("歌单内容暂时不可用。") }
        }
    }

    fun closePlaylist() = update(value.copy(openedPlaylist = null, playlistTracks = emptyList()))

    fun analyze() {
        val context = app ?: return
        if (value.accountScope.isBlank() || genreJob?.isActive == true) return
        val token = generation
        genreJob = scope.launch {
            update(value.copy(analyzing = true))
            var errors = 0
            try {
                val processed = mutableSetOf<String>()
                while (token == generation) {
                    val now = System.currentTimeMillis()
                    val candidates = (priority.mapNotNull(::track) + value.allTracks).distinctBy { it.mid }
                    val song = candidates.firstOrNull {
                        it.mid !in processed && (value.genres[it.mid]?.retryAt ?: 0L) <= now
                    } ?: break
                    processed.add(song.mid)
                    try {
                        val labels = QqAccountClient.songGenres(song.mid).map(CatalogueCodec::normalizeGenre).toSet()
                        var result = CatalogueGenre(song.mid, labels, "qq-song-detail", "QQ 单曲流派", now,
                            now + (if (labels.isEmpty()) 7 else 30) * 24 * 60 * 60 * 1000L)
                        if (labels.isEmpty() && context.getSharedPreferences("resonance_settings", Context.MODE_PRIVATE)
                                .getBoolean("catalogue_external_tags", true) && song.durationMs > 0 && song.artist.isNotBlank()) {
                            val info = SongInfo("qq:${song.mid}", song.name, song.artist, song.album, "qq-catalogue")
                            val match = AutomaticGenreMatcher.match(info, song.durationMs, MusicBrainzClient.searchAutomatic(info, song.durationMs))
                            match.recording?.let { recording ->
                                val extra = MusicBrainzClient.genres(recording.id).map(CatalogueCodec::normalizeGenre).toSet()
                                if (extra.isNotEmpty()) result = CatalogueGenre(song.mid, extra, "musicbrainz-recording", match.reason,
                                    now, now + 30 * 24 * 60 * 60 * 1000L, recording.id)
                            }
                        }
                        if (token != generation) break
                        CatalogueStore.saveGenre(context, result)
                        update(value.copy(genres = value.genres + (song.mid to result)))
                        refreshRecommendation()
                        errors = 0
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        errors++
                        if (token != generation) break
                        val old = value.genres[song.mid]
                        val retry = old?.copy(retryAt = now + 15 * 60 * 1000L) ?: CatalogueGenre(song.mid, emptySet(),
                            "pending", "暂时无法读取曲风", now, now + 15 * 60 * 1000L)
                        CatalogueStore.saveGenre(context, retry)
                        update(value.copy(genres = value.genres + (song.mid to retry)))
                        if (errors >= 3) { notice("曲库暂时不可用，分析已暂停；下次打开继续。" ); break }
                    }
                    delay(1100)
                }
            } finally { if (token == generation) update(value.copy(analyzing = false)) }
        }
    }

    fun styleTracks(): List<StyleTrack> = value.allTracks.mapNotNull { song ->
        val genre = value.genres[song.mid]?.takeIf { it.labels.isNotEmpty() } ?: return@mapNotNull null
        StyleTrack("qq:${song.mid}", song.name, song.artist, genre.recordingId.ifBlank { "qq:${song.mid}" }, genre.labels)
    }
    fun signals(): List<ListeningSignal> = app?.let { StyleRecommendations.signals(CatalogueStore.events(it, value.accountScope)) }.orEmpty()
    fun similar(mid: String): StylePlan {
        recommendationAnchor = mid
        val plan = StyleRecommendations.similar("qq:$mid", styleTracks(), signals(), value.favorites.map { "qq:${it.mid}" }.toSet())
        update(value.copy(recommendation = plan))
        discover(mid)
        return plan
    }
    fun direction(mid: String): StylePlan {
        recommendationAnchor = null
        val plan = StyleRecommendations.changeDirection("qq:$mid", styleTracks(), signals(), value.favorites.map { "qq:${it.mid}" }.toSet())
        update(value.copy(recommendation = plan))
        return plan
    }
    fun track(mid: String) = value.allTracks.firstOrNull { it.mid == mid }
    fun prioritize(mid: String) { priority.add(mid); analyze() }
    private fun refreshRecommendation() {
        recommendationAnchor?.let { mid ->
            val plan = StyleRecommendations.similar("qq:$mid", styleTracks(), signals(), value.favorites.map { "qq:${it.mid}" }.toSet())
            update(value.copy(recommendation = plan))
        }
    }
    private fun discover(mid: String) {
        val seed = track(mid) ?: return
        if (seed.songId.isBlank() || mid in discoveredSeeds || discoveryJob?.isActive == true) return
        val context = app ?: return
        val token = generation
        discoveredSeeds.add(mid)
        discoveryJob = scope.launch {
            try {
                val songs = QqAccountClient.similarSongs(seed.songId).filter { it.mid != mid }
                if (token != generation) return@launch
                val cached = songs.mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
                songs.forEach { priority.add(it.mid) }
                update(value.copy(discoveries = (value.discoveries + songs).distinctBy { it.mid }, genres = value.genres + cached))
                refreshRecommendation()
                analyze()
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) { if (token == generation) notice("新歌候选暂时不可用，仍可从收藏推荐。") }
        }
    }
}
