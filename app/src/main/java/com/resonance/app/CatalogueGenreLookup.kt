package com.resonance.app

import kotlinx.coroutines.CancellationException

data class SupplementaryGenres(val labels:Set<String>, val recordingId:String="", val reason:String="补充来源暂无可靠曲风资料")
interface CatalogueGenreSources {
    suspend fun qq(mid:String):Set<String>
    suspend fun supplementary(song:QqCatalogueTrack):SupplementaryGenres
}
object OnlineCatalogueGenreSources:CatalogueGenreSources {
    override suspend fun qq(mid:String)=QqAccountClient.songGenres(mid)
    override suspend fun supplementary(song:QqCatalogueTrack):SupplementaryGenres {
        val info=SongInfo("qq:${song.mid}",song.name,song.artist,song.album,"qq-catalogue")
        val match=AutomaticGenreMatcher.match(info,song.durationMs,MusicBrainzClient.searchAutomatic(info,song.durationMs))
        val recording=match.recording ?: return SupplementaryGenres(emptySet(),reason=match.reason)
        return SupplementaryGenres(MusicBrainzClient.genres(recording.id),recording.id,match.reason)
    }
}

class CatalogueGenreLookup(private val sources:CatalogueGenreSources=OnlineCatalogueGenreSources) {
    suspend fun fetch(song:QqCatalogueTrack,now:Long,onlySupplement:Boolean=false,supplements:Boolean=true):CatalogueGenre {
        val day=24*60*60*1000L
        fun result(labels:Set<String>,source:String,evidence:String,status:GenreLookupStatus,id:String="") =
            CatalogueGenre(song.mid,labels,source,evidence,now,now+when(status){
                GenreLookupStatus.READY->30*day
                GenreLookupStatus.UNAVAILABLE->7*day
                else->15*60*1000L
            },id,status,sourceLabels=if(labels.isEmpty())emptyMap() else mapOf(source to labels),metadataKey=CatalogueCodec.identity(song))
        fun retry(reason:String)=result(emptySet(),"pending",reason,GenreLookupStatus.RETRY)
        fun normalized(labels:Set<String>)=labels.map(CatalogueCodec::normalizeGenre).filter(String::isNotBlank).toSet()
        var qqFailed=false
        if(!onlySupplement) {
            val labels=try {normalized(sources.qq(song.mid))} catch(e:CancellationException){throw e}
                catch(_:Exception){qqFailed=true;emptySet()}
            if(labels.isNotEmpty())return result(labels,"qq-song-detail","QQ 单曲流派",GenreLookupStatus.READY)
        }
        if(!supplements) return if(onlySupplement || qqFailed)retry("在线补全已关闭，稍后可重试")
            else result(emptySet(),"qq-song-detail","QQ 无单曲曲风，在线补全已关闭",GenreLookupStatus.UNAVAILABLE)
        if(song.name.isBlank() || song.artist.isBlank() || song.durationMs<=0) return if(qqFailed)retry("QQ 暂不可用，歌曲资料不足以匹配补充来源")
            else result(emptySet(),"supplement-unavailable","缺少准确歌名、歌手或时长，无法可靠补齐",GenreLookupStatus.UNAVAILABLE)
        val extra=try {sources.supplementary(song)} catch(e:CancellationException){throw e}
            catch(_:Exception){return retry("补充来源暂不可用，稍后可重试")}
        val labels=normalized(extra.labels)
        if(labels.isNotEmpty() && MusicBrainzJson.validId(extra.recordingId))return result(labels,"musicbrainz-recording",extra.reason,GenreLookupStatus.READY,extra.recordingId)
        return if(qqFailed)retry("QQ 读取失败，补充来源也无可靠曲风")
            else result(emptySet(),"supplement-unavailable",extra.reason,GenreLookupStatus.UNAVAILABLE)
    }
}

object GenreEnrichment {
    fun merge(previous:CatalogueGenre,song:QqCatalogueTrack,extra:SupplementaryGenres?,now:Long,failed:Boolean=false):CatalogueGenre {
        val signature=CatalogueCodec.identity(song)
        val old=if(previous.metadataKey.isNotBlank() && previous.metadataKey!=signature)
            previous.copy(labels=previous.providers()["qq-song-detail"].orEmpty(),source="qq-song-detail",sourceLabels=emptyMap(),recordingId="") else previous
        val labels=extra?.labels.orEmpty().map(CatalogueCodec::normalizeGenre).filter(String::isNotBlank).toSet()
        if(!failed && labels.isNotEmpty() && MusicBrainzJson.validId(extra?.recordingId.orEmpty())) {
            val sources=old.providers()+mapOf("musicbrainz-recording" to labels)
            val union=sources.values.flatten().toSet()
            return old.copy(labels=union,source=sources.keys.sorted().joinToString("+"),sourceLabels=sources,
                recordingId=extra!!.recordingId,status=GenreLookupStatus.READY,enrichmentState="DONE",enrichRetryAt=now+30*24*60*60*1000L,
                enrichmentNote=if(union.size>old.labels.size)"已补充 ${union.size-old.labels.size} 个不同标签" else "补充曲库未增加不同标签",metadataKey=signature)
        }
        return old.copy(enrichmentState=if(failed)"RETRY" else "UNAVAILABLE",enrichRetryAt=now+(if(failed)15*60*1000L else 7*24*60*60*1000L),
            enrichmentNote=if(failed)"补充来源暂不可用，可稍后重试" else extra?.reason?:"补充来源暂无更细标签",metadataKey=signature,
            status=if(old.labels.isNotEmpty())GenreLookupStatus.READY else if(failed)GenreLookupStatus.RETRY else GenreLookupStatus.UNAVAILABLE)
    }
}
