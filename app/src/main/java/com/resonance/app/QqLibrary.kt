package com.resonance.app

import android.content.Context
import kotlinx.coroutines.*

data class LibraryState(
    val accountScope: String = "", val favorites: List<QqCatalogueTrack> = emptyList(),
    val discoveries: List<QqCatalogueTrack> = emptyList(), val playlists: List<QqCataloguePlaylist> = emptyList(),
    val genres: Map<String, CatalogueGenre> = emptyMap(), val syncing: Boolean = false,
    val analyzing: Boolean = false, val message: String = "连接 QQ 音乐，让收藏变成懂你的推荐。",
    val recommendation: StylePlan? = null, val revision: Long = 0,
    val openedPlaylist: QqCataloguePlaylist? = null, val playlistTracks: List<QqCatalogueTrack> = emptyList(),
    val discovering: Boolean = false, val activeGenreMid: String = "", val discoveryMessage: String = ""
) {
    val allTracks get() = (favorites + discoveries).distinctBy { it.mid }
    val taggedCount get() = favorites.count { genres[it.mid]?.labels?.isNotEmpty() == true }
    fun statusCount(status: GenreLookupStatus) = favorites.count { GenreLookupPolicy.status(genres[it.mid]) == status }
    val outsideCount get() = discoveries.count { song -> favorites.none { it.mid == song.mid } }
}

object QqLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private var session: QqAccountSession? = null
    private var syncJob: Job? = null
    private var genreJob: Job? = null
    private var discoveryJob: Job? = null
    private var discoveryPool = DiscoveryPool()
    private var activeDiscoveryMid = ""
    private val pendingSeeds = linkedSetOf<String>()
    private val discoveryRetryAt = mutableMapOf<String, Long>()
    private val automaticSeeds = mutableSetOf<String>()
    private val forcedGenres = mutableSetOf<String>()
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
            pendingSeeds.clear(); discoveryRetryAt.clear(); automaticSeeds.clear(); forcedGenres.clear(); activeDiscoveryMid = ""
            priority.clear(); recommendationAnchor = null
            val saved = if (key.isBlank()) emptyList() else CatalogueStore.songs(context, key)
            discoveryPool = if (key.isBlank()) DiscoveryPool() else CatalogueStore.discovery(context, key)
            val genres = (saved + discoveryPool.tracks).mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
            update(LibraryState(accountScope = key, favorites = saved, discoveries = discoveryPool.tracks, genres = genres,
                message = if (key.isBlank()) "连接 QQ 音乐，让收藏变成懂你的推荐。" else "QQ 音乐已连接，正在准备你的收藏。"))
        }
        if (account != null) {
            if (value.favorites.isEmpty() || System.currentTimeMillis() - CatalogueStore.syncedAt(context, key) > 6 * 60 * 60 * 1000L) syncFavorites()
            else { analyze(); warmDiscovery() }
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
                    update(value.copy(favorites = retained, message = if (complete) "收藏已同步，曲风资料自动补齐中。" else "已保存本轮收藏，部分收藏尚未读取。"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) {
                    val retained = (accumulator.tracks + previous).distinctBy { it.mid }
                    if (retained.isNotEmpty()) CatalogueStore.save(context, key, retained, false)
                    update(value.copy(favorites = retained, message = (e as? QqConnectionException)?.publicReason ?: "收藏同步暂未完成，已有资料已保留。"))
                }
            } finally {
                if (token == generation) { update(value.copy(syncing = false)); analyze(); warmDiscovery() }
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

    fun retryMissing() {
        val context = app ?: return
        if (value.accountScope.isBlank()) return
        val missing = value.favorites.filter { value.genres[it.mid]?.labels.isNullOrEmpty() }
        val changed = missing.associate { song ->
            forcedGenres.add(song.mid); priority.add(song.mid)
            val old = value.genres[song.mid] ?: CatalogueGenre(song.mid, emptySet(), "pending", "等待查询", 0, 0)
            val retry = GenreLookupPolicy.retry(old)
            CatalogueStore.saveGenre(context, retry)
            song.mid to retry
        }
        update(value.copy(genres = value.genres + changed, message = "已安排重试 ${missing.size} 首，已取得的曲风保留。"))
        analyze()
    }

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
                        it.mid in forcedGenres || (it.mid !in processed && (value.genres[it.mid]?.retryAt ?: 0L) <= now)
                    } ?: break
                    forcedGenres.remove(song.mid)
                    processed.add(song.mid)
                    update(value.copy(activeGenreMid = song.mid))
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
                            if (result.labels.isEmpty()) result = result.copy(evidence = match.reason)
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
                        val retry = old?.copy(retryAt = now + 15 * 60 * 1000L,
                            status = if (old.labels.isEmpty()) GenreLookupStatus.RETRY else GenreLookupStatus.READY) ?: CatalogueGenre(song.mid, emptySet(),
                            "pending", "暂时无法读取曲风", now, now + 15 * 60 * 1000L)
                        CatalogueStore.saveGenre(context, retry)
                        update(value.copy(genres = value.genres + (song.mid to retry)))
                        if (errors >= 3) { notice("曲库暂时不可用，分析已暂停；下次打开继续。" ); break }
                    }
                    delay(1100)
                }
            } finally { if (token == generation) { update(value.copy(analyzing = false, activeGenreMid = "")); warmDiscovery() } }
        }
    }

    fun styleTracks(includeUnknown: Boolean = false): List<StyleTrack> = value.allTracks.mapNotNull { song ->
        val genre = value.genres[song.mid]
        if (!includeUnknown && genre?.labels.isNullOrEmpty()) return@mapNotNull null
        StyleTrack("qq:${song.mid}", song.name, song.artist, genre?.recordingId?.ifBlank { "qq:${song.mid}" } ?: "qq:${song.mid}", genre?.labels.orEmpty())
    }
    fun signals(): List<ListeningSignal> = app?.let { StyleRecommendations.signals(CatalogueStore.events(it, value.accountScope)) }.orEmpty()
    fun similar(mid: String): StylePlan {
        recommendationAnchor = mid
        val plan = similarPlan(mid)
        update(value.copy(recommendation = plan))
        enqueueDiscovery(mid)
        return plan
    }
    fun direction(mid: String): StylePlan {
        recommendationAnchor = null
        val plan = StyleRecommendations.changeDirection("qq:$mid", styleTracks(), signals(), value.favorites.map { "qq:${it.mid}" }.toSet())
        update(value.copy(recommendation = plan))
        return plan
    }
    fun track(mid: String) = value.allTracks.firstOrNull { it.mid == mid }
    fun prioritize(mid: String) { priority.add(mid); analyze(); enqueueDiscovery(mid) }
    private fun similarPlan(mid: String) = StyleRecommendations.similar("qq:$mid", styleTracks(true), signals(),
        value.favorites.map { "qq:${it.mid}" }.toSet(), discoveryPool.related(mid).map { "qq:$it" }.toSet())
    private fun refreshRecommendation() {
        recommendationAnchor?.let { mid ->
            val plan = similarPlan(mid)
            update(value.copy(recommendation = plan))
        }
    }
    fun warmDiscovery(manual: Boolean = false) {
        if (value.accountScope.isBlank()) return
        val now = System.currentTimeMillis()
        val budget = if (manual) 6 else (6 - automaticSeeds.size).coerceAtLeast(0)
        // A fresh installation initially takes two seeds; after genre analysis it can spread across styles.
        val limit = if (!manual && value.taggedCount == 0) minOf(2, budget) else budget
        val seeds = value.favorites.filter { !discoveryPool.fresh(it.mid, now) &&
            (discoveryRetryAt[it.mid] ?: 0) <= now && it.mid !in pendingSeeds && it.mid != activeDiscoveryMid && (manual || it.mid !in automaticSeeds) }
        DiscoverySeedPolicy.select(seeds, value.genres, signals(), limit).forEach { seed ->
            if (!manual) automaticSeeds.add(seed.mid)
            enqueueDiscovery(seed.mid)
        }
        if (manual && pendingSeeds.isEmpty() && discoveryJob?.isActive != true)
            update(value.copy(discoveryMessage = "候选资料已缓存，暂无需要更新的收藏种子。"))
    }
    private fun enqueueDiscovery(mid: String) {
        val seed = track(mid) ?: return
        val now = System.currentTimeMillis()
        if (session == null || seed.songId.isBlank() || discoveryPool.fresh(mid, now) ||
            (discoveryRetryAt[mid] ?: 0) > now || pendingSeeds.size >= 12 || activeDiscoveryMid == mid) return
        pendingSeeds.add(mid)
        drainDiscovery()
    }
    private fun drainDiscovery() {
        if (discoveryJob?.isActive == true || pendingSeeds.isEmpty()) return
        val context = app ?: return
        val token = generation
        discoveryJob = scope.launch {
            update(value.copy(discovering = true, discoveryMessage = "正在从收藏发现新歌…"))
            try {
                while (token == generation && pendingSeeds.isNotEmpty()) {
                    val mid = pendingSeeds.first()
                    pendingSeeds.remove(mid)
                    val seed = track(mid) ?: continue
                    activeDiscoveryMid = mid
                    try {
                        val songs = QqAccountClient.similarSongs(seed.songId).filter { it.mid != mid }
                        if (token != generation) return@launch
                        discoveryPool = discoveryPool.add(DiscoveryBatch(mid, System.currentTimeMillis(), songs))
                        CatalogueStore.saveDiscovery(context, value.accountScope, discoveryPool)
                        discoveryRetryAt.remove(mid)
                        val cached = songs.mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
                        if (recommendationAnchor == mid) songs.forEach { priority.add(it.mid) }
                        update(value.copy(discoveries = (value.discoveries + songs).distinctBy { it.mid }, genres = value.genres + cached,
                            discoveryMessage = "收藏外候选已更新，曲风资料会自动补齐。"))
                        refreshRecommendation(); analyze()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (token != generation) return@launch
                        discoveryRetryAt[mid] = System.currentTimeMillis() + 15 * 60 * 1000L
                        update(value.copy(discoveryMessage = "部分新歌候选读取失败，已有候选保留，可稍后更新。"))
                    } finally { if (token == generation) activeDiscoveryMid = "" }
                    delay(1200)
                }
            } finally { if (token == generation) update(value.copy(discovering = false)) }
        }
    }
}
