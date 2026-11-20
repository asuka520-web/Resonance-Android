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
    val discovering: Boolean = false, val activeGenreMid: String = "", val discoveryMessage: String = "",
    val discoverySources: Map<String,List<String>> = emptyMap(), val listening: List<ListeningSignal> = emptyList(),
    val retryScheduled: Boolean = false,val intent:ListeningIntent=ListeningIntent(),
    val favoriteStates:Map<String,Boolean> = emptyMap(),val favoriteBusy:Set<String> = emptySet(),val favoriteUncertain:Set<String> = emptySet(),
    val feedback:DirectionNotice?=null,val directionHistory:List<DirectionSwitch> = emptyList(),
    val enriching:Boolean=false,val enrichedProgress:Int=0,val enrichmentTotal:Int=0,
    val scoreListening:List<ListeningSignal>?=null,val scoreEpoch:Long=0,val listeningTimeMs:Long=0,val manualScores:Map<String,Double> = emptyMap(),
    val discoveryPlaylists: Map<String, String> = emptyMap()
) {
    val scoreSignals get()=scoreListening?:listening
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
    private var enrichmentJob:Job?=null
    private var automaticEnriched=false
    private val genreLookup=CatalogueGenreLookup()
    private var discoveryPool = DiscoveryPool()
    private var playlistDiscoveryPool = PlaylistDiscoveryPool()
    private var playlistDiscoveryRequested = false
    private var activeDiscoveryMid = ""
    private val pendingSeeds = linkedSetOf<String>()
    private val discoveryRetryAt = mutableMapOf<String, Long>()
    private val automaticSeeds = mutableSetOf<String>()
    private val forcedGenres = mutableSetOf<String>()
    private val supplementOnly = mutableSetOf<String>()
    private val priority = linkedSetOf<String>()
    private var recommendationAnchor: String? = null
    private var surpriseId:String?=null
    private val favoriteOverrides=mutableMapOf<String,Pair<QqCatalogueTrack,Boolean>>()
    private val favoriteOverrideTimes=mutableMapOf<String,Long>()
    private val favoriteJobs=mutableMapOf<String,Job>()
    private var generation = 0L
    @Volatile private var value = LibraryState()
    private val listeners = mutableSetOf<(LibraryState) -> Unit>()
    val state get() = value
    fun subscribe(listener: (LibraryState) -> Unit) { listeners.add(listener); listener(value) }
    fun unsubscribe(listener: (LibraryState) -> Unit) { listeners.remove(listener) }
    private fun update(next: LibraryState) { value = next.copy(revision = value.revision + 1); listeners.toList().forEach { it(value) } }
    fun notice(message: String) = update(value.copy(message = message))
    fun feedback(message:String){val now=android.os.SystemClock.elapsedRealtime();update(value.copy(message=message,feedback=DirectionNotice(System.nanoTime(),now,emptySet(),"INFO",message)))}
    fun beginDirection(from:String,target:String?,plan:StylePlan):Long {
        val context=app?:return 0
        val id=System.nanoTime()
        val mid = target.orEmpty()
        val row=DirectionSwitch(id,System.currentTimeMillis(),from,mid,track(mid)?.name.orEmpty(),plan.directionKind,plan.explanation,
            state = if (target == null) "NO_CANDIDATE" else "PREPARING",
            detail = if (target == null) plan.heading else "",
            scores=GenrePreferenceScores.calculate(styleTracks(),value.scoreSignals,value.manualScores).associate {it.label to it.combined},
            targetRecordingId=value.genres[mid]?.recordingId.orEmpty())
        val rows=(value.directionHistory+row).takeLast(20)
        CatalogueStore.saveDirectionHistory(context,value.accountScope,rows);update(value.copy(directionHistory=rows))
        return id
    }
    fun updateDirection(id:Long,state:String,detail:String) {
        val context=app?:return
        if(value.directionHistory.none {it.id==id})return
        val rows=value.directionHistory.map {if(it.id==id)it.copy(state=state,detail=detail) else it}
        CatalogueStore.saveDirectionHistory(context,value.accountScope,rows);update(value.copy(directionHistory=rows))
    }
    private fun applyFavoriteOverrides(songs:List<QqCatalogueTrack>):List<QqCatalogueTrack> {
        favoriteOverrideTimes.filterValues {System.currentTimeMillis()-it>60000}.keys.toList().forEach {favoriteOverrides.remove(it);favoriteOverrideTimes.remove(it)}
        var result=songs
        favoriteOverrides.values.forEach {(song,liked)->result=if(liked)(result+song).distinctBy {it.mid} else result.filter {it.mid!=song.mid}}
        return result
    }
    private fun applyFavorite(song:QqCatalogueTrack,liked:Boolean) {
        val context=app?:return
        favoriteOverrides[song.mid]=song to liked
        favoriteOverrideTimes[song.mid]=System.currentTimeMillis()
        val songs=applyFavoriteOverrides(value.favorites)
        CatalogueStore.save(context,value.accountScope,songs,false)
        update(value.copy(favorites=songs,discoveries=if(liked)value.discoveries else (value.discoveries+song).distinctBy {it.mid},favoriteStates=value.favoriteStates+(song.mid to liked),favoriteUncertain=value.favoriteUncertain-song.mid))
        refreshRecommendation()
    }
    fun refreshFavorite(mid:String)=favoriteAction(mid,false)
    fun toggleFavorite(mid:String)=favoriteAction(mid,true)
    private fun favoriteAction(mid:String,toggle:Boolean) {
        val account=session?:run{feedback("请先连接 QQ 音乐账号。");return}
        val song=track(mid)?:return
        if(favoriteJobs[mid]?.isActive==true)return
        val token=generation
        val desired=!(value.favoriteStates[mid]?:value.favorites.any {it.mid==mid})
        val refreshOnly=!toggle || mid in value.favoriteUncertain
        val current={token==generation && session?.cookieHeader==account.cookieHeader && QqSessionManager.active?.userId==account.userId}
        favoriteJobs[mid]=scope.launch {
            update(value.copy(favoriteBusy=value.favoriteBusy+mid))
            try {
                val gateway=QqAccountClient.favoriteGateway(account)
                if(refreshOnly){val actual=gateway.read(mid);if(current()){applyFavorite(song,actual);if(toggle)feedback("QQ 收藏状态已刷新。")}}
                else {
                    val result=FavoriteOperation.set(song,desired,gateway,current)
                    if(current()){
                        result.actual?.let {applyFavorite(song,it)}
                        if(!result.confirmed)update(value.copy(favoriteUncertain=value.favoriteUncertain+mid))
                        feedback(result.message)
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){if(current())feedback("QQ 收藏暂未完成，请更新授权或稍后刷新；未假报成功。")} finally {
                if(token==generation)update(value.copy(favoriteBusy=value.favoriteBusy-mid))
            }
        }
    }

    fun activate(context: Context, account: QqAccountSession?) {
        app = context.applicationContext
        session = account
        if(value.listeningTimeMs==0L)update(value.copy(listeningTimeMs=ListeningTimeStore.totalMs(context)))
        val key = account?.let { CatalogueStore.scope(it.userId) }.orEmpty()
        if (key != value.accountScope) {
            generation++
            syncJob?.cancel(); genreJob?.cancel(); discoveryJob?.cancel();enrichmentJob?.cancel();automaticEnriched=false
            favoriteJobs.values.forEach {it.cancel()};favoriteJobs.clear();favoriteOverrides.clear();favoriteOverrideTimes.clear()
            pendingSeeds.clear(); discoveryRetryAt.clear(); automaticSeeds.clear(); forcedGenres.clear(); supplementOnly.clear(); activeDiscoveryMid = ""
            priority.clear(); recommendationAnchor = null;surpriseId=null
            val saved = if (key.isBlank()) emptyList() else CatalogueStore.songs(context, key)
            discoveryPool = if (key.isBlank()) DiscoveryPool() else CatalogueStore.discovery(context, key)
            playlistDiscoveryPool = if (key.isBlank()) PlaylistDiscoveryPool() else CatalogueStore.playlistDiscovery(context, key)
            playlistDiscoveryRequested = false
            val discoveries = (discoveryPool.tracks + playlistDiscoveryPool.tracks).distinctBy { it.mid }
            val genres = (saved + discoveries).mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
            val preferences=context.getSharedPreferences("qq_intent_$key",Context.MODE_PRIVATE)
            val intent=if(key.isBlank())ListeningIntent() else ListeningIntent(preferences.getStringSet("wanted",emptySet()).orEmpty().toSet(),
                preferences.getStringSet("excluded",emptySet()).orEmpty().toSet(),preferences.getBoolean("all",false)).normalized()
            update(LibraryState(accountScope = key, favorites = saved, discoveries = discoveries, genres = genres,
                discoveryPlaylists = playlistDiscoveryPool.sources(),
                intent=intent,
                scoreListening=if(key.isBlank())emptyList() else CatalogueStore.scoreSignals(context,key),
                scoreEpoch=if(key.isBlank())0 else CatalogueStore.scoreEpoch(context,key),listeningTimeMs=ListeningTimeStore.totalMs(context),
                manualScores=if(key.isBlank())emptyMap() else CatalogueStore.manualScores(context,key),
                directionHistory=if(key.isBlank())emptyList() else CatalogueStore.directionHistory(context,key).map {
                    if(it.state=="PREPARING")it.copy(state="UNCONFIRMED",detail="上次播放服务已中断，未确认目标播放。") else it},
                discoverySources = discoveryPool.sources(), listening = if(key.isBlank())emptyList() else StyleRecommendations.signals(CatalogueStore.events(context,key)),
                message = if (key.isBlank()) "连接 QQ 音乐，让收藏变成懂你的推荐。" else "QQ 音乐已连接，正在准备你的收藏。"))
        }
        if (account != null) {
            if (value.favorites.isEmpty() || System.currentTimeMillis() - CatalogueStore.syncedAt(context, key) > 6 * 60 * 60 * 1000L) syncFavorites()
            else { analyze(); warmDiscovery();enrichGenres(automatic=true) }
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
                    update(value.copy(favorites = applyFavoriteOverrides(loaded), genres = value.genres + cachedGenres,
                        message = "已同步 ${loaded.size}${page.total?.let { " / $it" }.orEmpty()} 首收藏"))
                    if (!page.hasMore) { complete = page.total == null || page.nextOffset >= page.total; break }
                    if (page.nextOffset <= offset) throw QqConnectionException("曲库分页未前进，已停止本轮同步。")
                    offset = page.nextOffset
                    delay(350)
                }
                if (token == generation) {
                    val retained = applyFavoriteOverrides(if (complete) accumulator.tracks else (accumulator.tracks + previous).distinctBy { it.mid })
                    CatalogueStore.save(context, key, retained, complete)
                    update(value.copy(favorites = retained, message = if (complete) "收藏已同步，曲风资料自动补齐中。" else "已保存本轮收藏，部分收藏尚未读取。"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) {
                    val retained = applyFavoriteOverrides((accumulator.tracks + previous).distinctBy { it.mid })
                    if (retained.isNotEmpty()) CatalogueStore.save(context, key, retained, false)
                    update(value.copy(favorites = retained, message = (e as? QqConnectionException)?.publicReason ?: "收藏同步暂未完成，已有资料已保留。"))
                }
            } finally {
                if (token == generation) { update(value.copy(syncing = false)); analyze(); warmDiscovery();enrichGenres(automatic=true) }
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
                update(withPlaylistCandidates(context, playlist, tracks).copy(
                    openedPlaylist = playlist, playlistTracks = tracks,
                    message = "已载入「${playlist.name}」的首批歌曲。"))
                refreshRecommendation()
                analyze()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) notice("歌单内容暂时不可用。") }
        }
    }

    fun closePlaylist() = update(value.copy(openedPlaylist = null, playlistTracks = emptyList()))
    fun enrichGenres(mid:String?=null,automatic:Boolean=false) {
        val context=app?:return
        if(value.accountScope.isBlank() || enrichmentJob?.isActive==true)return
        if(!context.getSharedPreferences("resonance_settings",Context.MODE_PRIVATE).getBoolean("catalogue_external_tags",true)) {
            if(!automatic)feedback("请在“我的”开启在线补全。");return
        }
        val now=System.currentTimeMillis()
        val tracks = when {
            mid != null -> value.allTracks
            automatic -> {
                val recentlySkipped = value.listening.takeLast(4).asReversed().filter {it.category == "fast"}
                    .mapNotNull {track(it.mediaId.removePrefix("qq:"))}
                recentlySkipped + if (automaticEnriched) emptyList() else value.favorites
            }
            else -> value.favorites
        }
        val candidates=CatalogueEnrichmentPlan.candidates(tracks,value.genres,mid,automatic,now)
        if(candidates.isEmpty()){if(!automatic)feedback("暂无需要补查的单标签歌曲。");return}
        if(automatic)automaticEnriched=true
        val token=generation
        enrichmentJob=scope.launch {
            update(value.copy(enriching=true,enrichedProgress=0,enrichmentTotal=candidates.size))
            var failures=0
            try {
                for(song in candidates) {
                    if(token!=generation || !context.getSharedPreferences("resonance_settings",Context.MODE_PRIVATE).getBoolean("catalogue_external_tags",true))break
                    var failed=false
                    val extra=try {OnlineCatalogueGenreSources.supplementary(song)}catch(e:CancellationException){throw e}catch(_:Exception){failed=true;null}
                    if(token!=generation)break
                    if(track(song.mid)?.let(CatalogueCodec::identity)!=CatalogueCodec.identity(song))continue
                    val old=value.genres[song.mid]?:CatalogueGenre(song.mid,emptySet(),"pending","",0,0)
                    val result=GenreEnrichment.merge(old,song,extra,System.currentTimeMillis(),failed)
                    CatalogueStore.saveGenre(context,result)
                    update(value.copy(genres=value.genres+(song.mid to result),enrichedProgress=value.enrichedProgress+1))
                    refreshRecommendation()
                    failures=if(failed)failures+1 else 0
                    if(failures>=3){feedback("补充来源连续不可用，已暂停；已有标签保留。");break}
                    delay(1100)
                }
            }finally{if(token==generation)update(value.copy(enriching=false))}
        }
    }

    fun retryMissing(useSupplementOnly: Boolean = false) {
        val context = app ?: return
        if (value.accountScope.isBlank()) return
        if (useSupplementOnly && !context.getSharedPreferences("resonance_settings",Context.MODE_PRIVATE).getBoolean("catalogue_external_tags",true)) {
            notice("请先在“我的”开启在线补全。"); return
        }
        val missing = value.favorites.filter { value.genres[it.mid]?.labels.isNullOrEmpty() }
        val changed = missing.associate { song ->
            if (useSupplementOnly) supplementOnly.add(song.mid) else supplementOnly.remove(song.mid)
            forcedGenres.add(song.mid); priority.add(song.mid)
            val old = value.genres[song.mid] ?: CatalogueGenre(song.mid, emptySet(), "pending", "等待查询", 0, 0)
            val retry = GenreLookupPolicy.retry(old)
            CatalogueStore.saveGenre(context, retry)
            song.mid to retry
        }
        update(value.copy(genres = value.genres + changed, retryScheduled = missing.isNotEmpty(), message = "已安排重试 ${missing.size} 首，已取得的曲风保留。"))
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
                    // An in-flight initial query can finish after the user schedules a retry.
                    val fulfilled = forcedGenres.filter { value.genres[it]?.labels?.isNotEmpty() == true }.toSet()
                    forcedGenres.removeAll(fulfilled); supplementOnly.removeAll(fulfilled)
                    val candidates = (priority.mapNotNull(::track) + value.allTracks).distinctBy { it.mid }
                    val song = candidates.firstOrNull {
                        it.mid in forcedGenres || (it.mid !in processed && (value.genres[it.mid]?.retryAt ?: 0L) <= now)
                    } ?: break
                    forcedGenres.remove(song.mid)
                    priority.remove(song.mid)
                    processed.add(song.mid)
                    update(value.copy(activeGenreMid = song.mid))
                    try {
                        val onlySupplement = supplementOnly.remove(song.mid)
                        val fresh = genreLookup.fetch(song,now,onlySupplement,context.getSharedPreferences("resonance_settings",Context.MODE_PRIVATE)
                            .getBoolean("catalogue_external_tags",true))
                        val result = GenreLookupPolicy.preserveKnown(value.genres[song.mid],fresh)
                        if (token != generation) break
                        CatalogueStore.saveGenre(context, result)
                        update(value.copy(genres = value.genres + (song.mid to result)))
                        refreshRecommendation()
                        errors = if(fresh.status==GenreLookupStatus.RETRY)errors+1 else 0
                        if(errors>=3){notice("资料来源暂不可用，分析已暂停；可稍后重试。");break}
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
            } finally { if (token == generation) { update(value.copy(analyzing = false, activeGenreMid = "", retryScheduled=false)); warmDiscovery();enrichGenres(automatic=true) } }
        }
    }

    fun styleTracks(includeUnknown: Boolean = false): List<StyleTrack> = value.allTracks.mapNotNull { song ->
        val genre = value.genres[song.mid]
        if (!includeUnknown && genre?.labels.isNullOrEmpty()) return@mapNotNull null
        StyleTrack("qq:${song.mid}", song.name, song.artist, genre?.recordingId?.ifBlank { "qq:${song.mid}" } ?: "qq:${song.mid}", genre?.labels.orEmpty())
    }
    fun signals() = value.listening
    fun clearScores() {
        val context=app?:return
        if(value.accountScope.isBlank())return
        val epoch=CatalogueStore.clearScores(context,value.accountScope)
        recommendationAnchor=null;surpriseId=null
        update(value.copy(scoreListening=emptyList(),scoreEpoch=epoch,recommendation=null,manualScores=emptyMap()))
        feedback("标签分数已清零，将从新的听歌反馈重新积累。")
    }
    fun refreshListening() {
        val context = app ?: return
        update(value.copy(listening=StyleRecommendations.signals(CatalogueStore.events(context,value.accountScope)),
            scoreListening=CatalogueStore.scoreSignals(context,value.accountScope)))
        refreshRecommendation()
    }
    fun clearTagScore(label: String) {
        val context = app ?: return
        val account = value.accountScope
        if (account.isBlank() || value.genreScores().none { it.label == label }) return
        CatalogueStore.clearTagScore(context, account, label)
        update(value.copy(scoreListening = CatalogueStore.scoreSignals(context, account),
            manualScores = CatalogueStore.manualScores(context, account)))
        refreshRecommendation()
        feedback("已清零「${genreName(label)}」的分数。")
    }
    fun songFeedback(mid:String,selected:Set<String>,more:Boolean) {
        val context=app?:return
        if(value.accountScope.isBlank())return
        val labels=selected.intersect(value.genres[mid]?.labels.orEmpty())
        if(labels.isEmpty()){feedback("这首歌的曲风仍待补充，暂不能按标签调整。");return}
        val scores=value.manualScores.toMutableMap()
        labels.forEach {tag->scores[tag]=((scores[tag]?:0.0)+if(more)2.0 else -2.0).coerceIn(-6.0,6.0)}
        CatalogueStore.saveManualScores(context,value.accountScope,scores)
        update(value.copy(manualScores=scores))
        val anchor=PlaybackStateBus.value.mid.takeIf {track(it)!=null}?:mid
        similar(anchor)
        feedback(if(more)"已提高所选曲风的推荐倾向。" else "已降低所选曲风的推荐倾向。")
    }
    fun similar(mid: String): StylePlan {
        recommendationAnchor = mid
        surpriseId=null
        val plan = similarPlan(mid)
        update(value.copy(recommendation = plan))
        enqueueDiscovery(mid)
        return plan
    }
    fun direction(mid: String, range: PlaybackRange): StylePlan {
        recommendationAnchor = null
        val plan = planDirection(value, styleTracks(true), "qq:$mid", range)
        prioritizeGenres((signals().takeLast(4).asReversed().map {it.mediaId} + plan.picks.take(3).map {it.track.mediaId})
            .map {it.removePrefix("qq:")})
        enrichGenres(automatic = true)
        update(value.copy(recommendation = plan))
        return plan
    }
    fun track(mid: String) = value.allTracks.firstOrNull { it.mid == mid }
    private fun prioritizeGenres(mids: List<String>) {
        val now = System.currentTimeMillis()
        val due = mids.filter {mid -> (value.genres[mid]?.retryAt ?: 0L) <= now}
        val newest = (due + priority).distinct().take(100)
        priority.clear()
        priority.addAll(newest)
        analyze()
    }
    fun prioritize(mid: String) {
        prioritizeGenres(listOf(mid))
        enqueueDiscovery(mid)
    }
    private fun similarPlan(mid: String):StylePlan {
        val tracks=styleTracks(true)
        val favorites=value.favorites.map {"qq:${it.mid}"}.toSet()
        val base=StyleRecommendations.similar("qq:$mid",tracks,signals(),favorites,discoveryPool.related(mid).map {"qq:$it"}.toSet(),value.intent,value.scoreSignals,value.manualScores)
        val plan=SurpriseRecommendations.compose(base,tracks,signals(),favorites,"qq:$mid",
            app?.getSharedPreferences("resonance_settings",Context.MODE_PRIVATE)?.getBoolean("qq_surprise_enabled",true)==true,surpriseId,intent=value.intent)
        surpriseId=plan.picks.firstOrNull {it.surprise}?.track?.mediaId
        return plan
    }
    fun refreshRecommendationPreferences(){surpriseId=null;refreshRecommendation()}
    fun surprise(currentMid:String):StylePlan {
        recommendationAnchor=null;surpriseId=null
        val plan=SurpriseRecommendations.compose(StylePlan("惊喜一首","从未知曲风歌曲中随机抽取，不推断曲风。"),
            styleTracks(true),signals(),value.favorites.map {"qq:${it.mid}"}.toSet(),"qq:$currentMid",true,intent=value.intent)
        update(value.copy(recommendation=plan,message=if(plan.picks.isEmpty())"暂无可抽取的未知曲风歌曲。" else "抽到一首未知曲风歌曲。"))
        return plan
    }
    fun setIntent(intent:ListeningIntent) {
        val context=app?:return
        if(value.accountScope.isBlank())return
        val next=intent.normalized()
        context.getSharedPreferences("qq_intent_${value.accountScope}",Context.MODE_PRIVATE).edit()
            .putStringSet("wanted",next.wanted).putStringSet("excluded",next.excluded).putBoolean("all",next.matchAll).apply()
        update(value.copy(intent=next))
        val anchor=PlaybackStateBus.value.mid.takeIf {track(it)!=null} ?: recommendationAnchor ?: value.favorites.firstOrNull()?.mid
        if(anchor!=null)similar(anchor) else refreshRecommendationPreferences()
    }
    private fun refreshRecommendation() {
        recommendationAnchor?.let { mid ->
            val plan = similarPlan(mid)
            update(value.copy(recommendation = plan))
        }
    }
    fun warmDiscovery(manual: Boolean = false) {
        if (value.accountScope.isBlank() || session == null) return
        val now = System.currentTimeMillis()
        val budget = if (manual) 6 else (6 - automaticSeeds.size).coerceAtLeast(0)
        // A fresh installation initially takes two seeds; after genre analysis it can spread across styles.
        val limit = if (!manual && value.taggedCount == 0) minOf(2, budget) else budget
        val seeds = value.favorites.filter { !discoveryPool.fresh(it.mid, now) &&
            (discoveryRetryAt[it.mid] ?: 0) <= now && it.mid !in pendingSeeds && it.mid != activeDiscoveryMid && (manual || it.mid !in automaticSeeds) }
        DiscoverySeedPolicy.select(seeds, value.genres, value.scoreSignals, limit).forEach { seed ->
            if (!manual) automaticSeeds.add(seed.mid)
            enqueueDiscovery(seed.mid)
        }
        if (manual) {
            playlistDiscoveryRequested = true
            drainDiscovery()
        }
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
        if (discoveryJob?.isActive == true || (pendingSeeds.isEmpty() && !playlistDiscoveryRequested)) return
        val context = app ?: return
        val token = generation
        discoveryJob = scope.launch {
            update(value.copy(discovering = true, discoveryMessage = ""))
            var hadFailures = false
            try {
                while (token == generation && (pendingSeeds.isNotEmpty() || playlistDiscoveryRequested)) {
                    if (playlistDiscoveryRequested) {
                        playlistDiscoveryRequested = false
                        if (!readDiscoveryPlaylists(context, token)) hadFailures = true
                        continue
                    }
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
                            discoverySources=discoveryPool.sources(),
                            discoveryMessage = "收藏外候选已更新，曲风资料会自动补齐。"))
                        refreshRecommendation(); analyze()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (token != generation) return@launch
                        discoveryRetryAt[mid] = System.currentTimeMillis() + 15 * 60 * 1000L
                        hadFailures = true
                    } finally { if (token == generation) activeDiscoveryMid = "" }
                    delay(1200)
                }
            } finally {
                if (token == generation) update(value.copy(discovering = false, discoveryMessage = when {
                    hadFailures -> "部分新歌候选读取失败，已有候选保留，可稍后更新。"
                    else -> value.discoveryMessage.ifBlank { "候选资料已缓存，暂无需要更新的来源。" }
                }))
            }
        }
    }

    private suspend fun readDiscoveryPlaylists(context: Context, token: Long): Boolean {
        val account = session ?: return false
        val playlists = try {
            value.playlists.ifEmpty { QqAccountClient.playlists(account) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (token != generation) return false
        if (playlists != value.playlists) update(value.copy(playlists = playlists))

        var successful = true
        for (playlist in playlistDiscoveryPool.nextPlaylists(playlists, System.currentTimeMillis())) {
            if (token != generation) return false
            try {
                val tracks = QqAccountClient.tracks(account, playlist.id).take(50)
                if (token != generation) return false
                update(withPlaylistCandidates(context, playlist, tracks).copy(
                    discoveryMessage = "QQ 歌单候选已更新，曲风资料会自动补齐。"))
                refreshRecommendation()
                analyze()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                successful = false
            }
            delay(1200)
        }
        return successful
    }

    private fun withPlaylistCandidates(context: Context, playlist: QqCataloguePlaylist, tracks: List<QqCatalogueTrack>): LibraryState {
        playlistDiscoveryPool = playlistDiscoveryPool.add(PlaylistDiscoveryBatch(playlist, System.currentTimeMillis(), tracks))
        CatalogueStore.savePlaylistDiscovery(context, value.accountScope, playlistDiscoveryPool)
        val cached = tracks.mapNotNull { song -> CatalogueStore.genre(context, song.mid)?.let { song.mid to it } }.toMap()
        // Retain previously queued songs for this session even after their metadata cache batch is replaced.
        val sources = value.discoveryPlaylists + tracks.associate { it.mid to playlist.name }
        return value.copy(discoveries = (tracks + value.discoveries).distinctBy { it.mid },
            genres = value.genres + cached, discoveryPlaylists = sources)
    }
}
