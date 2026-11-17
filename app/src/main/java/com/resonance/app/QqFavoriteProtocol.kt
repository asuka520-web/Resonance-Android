package com.resonance.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** zzc/ag-1 interoperability based on qmweb-sign (MIT), Copyright (c) 2024 Jixun Wu.
 * Full notice: third_party/qmweb-sign-LICENSE.txt. Constants are public protocol parameters, not user secrets. */
object QqFavoriteProtocol {
    private val requestKey=intArrayOf(189,48,95,16,208,255,116,182,239,84,218,184,53,181,225,207).map {it.toByte()}.toByteArray()
    private val responseMask=intArrayOf(122,63,140,29,94,155,47,10,108,77,126,139,31,58,92,157,14,43,111,74,129)
    fun sign(body:String):String {
        val digest=MessageDigest.getInstance("SHA-1").digest(body.toByteArray(Charsets.UTF_8))
        val hex=digest.joinToString(""){"%02X".format(it.toInt() and 255)}
        fun pick(indices:List<Int>)=indices.joinToString(""){hex[it].toString()}
        val mask=intArrayOf(89,39,179,150,218,82,58,252,177,52,186,123,120,64,242,133,143,161,121,179)
        val middle=Base64.getEncoder().encodeToString(ByteArray(20){(digest[it].toInt().and(255) xor mask[it]).toByte()})
            .replace(Regex("[\\/+=]"),"")
        return ("zzc"+pick(listOf(23,14,6,36,16,7,19))+middle+pick(listOf(16,1,32,12,19,27,8,5))).lowercase(java.util.Locale.ROOT)
    }
    fun encrypt(body:String,iv:ByteArray):String {
        require(iv.size==12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(requestKey,"AES"),GCMParameterSpec(128,iv))
        return Base64.getEncoder().encodeToString(iv+cipher.doFinal(body.toByteArray(Charsets.UTF_8)))
    }
    fun decode(bytes:ByteArray)=String(ByteArray(bytes.size){(bytes[it].toInt() xor responseMask[it%responseMask.size]).toByte()},Charsets.UTF_8)
    fun envelope(session:QqAccountSession,module:String,method:String,param:JSONObject):JSONObject {
        val cookies=session.cookieHeader.split(';').mapNotNull {val p=it.trim().split('=',limit=2);if(p.size==2)p[0] to p[1] else null}.toMap()
        fun token(vararg names:String):Int {
            val key=names.firstNotNullOfOrNull {cookies[it]?.takeIf(String::isNotBlank)}.orEmpty()
            var hash=5381;key.forEach {hash=hash*33+it.code};return hash and 0x7fffffff
        }
        val comm=JSONObject().put("uin",session.userId).put("authst",session.playbackKey).put("format","json").put("ct",24).put("cv",4747474)
            .put("platform","yqq.json").put("g_tk",token("skey","qqmusic_key","qm_keyst"))
            .put("g_tk_new_20200303",token("qqmusic_key","p_skey","skey","p_lskey","lskey","qm_keyst"))
            .put("inCharset","utf-8").put("outCharset","utf-8").put("notice",0).put("needNewCode",1)
        return JSONObject().put("comm",comm).put("req_0",JSONObject().put("module",module).put("method",method).put("param",param))
    }
    fun data(raw:String,write:Boolean=false):JSONObject {
        val root=JSONObject(raw)
        fun zero(obj:JSONObject,key:String,required:Boolean=true) {
            if((required && !obj.has(key)) || (obj.has(key) && obj.opt(key)?.toString()!="0"))throw QqConnectionException("QQ 未确认${if(write)"收藏修改" else "收藏状态"}，请稍后刷新。")
        }
        zero(root,"code")
        val block=root.optJSONObject("req_0")?:throw QqConnectionException("QQ 收藏响应不完整。")
        zero(block,"code")
        val result=block.optJSONObject("data")?:throw QqConnectionException("QQ 收藏资料缺失。")
        zero(result,"code",false);zero(result,"subcode",false)
        if(write)zero(result,"retCode")
        return result
    }
    fun status(raw:String,mid:String):Boolean {
        val value=data(raw).optJSONObject("m_fan")?.opt(mid)
        return value as? Boolean?:throw QqConnectionException("QQ 未返回这首歌的明确收藏状态。")
    }
    fun identity(raw:String,song:QqCatalogueTrack):Pair<Long,Int> {
        val info=data(raw).optJSONObject("track_info")?:throw QqConnectionException("歌曲身份未核实，未修改收藏。")
        val id=info.optString("id").toLongOrNull()
        val type=info.opt("type") as? Number
        if(info.optString("mid")!=song.mid || id==null || id<=0 || type==null || type.toInt()<0 || type.toDouble()!=type.toInt().toDouble() ||
            (song.songId.isNotBlank() && song.songId!=id.toString()))throw QqConnectionException("歌曲身份不匹配，未修改收藏。")
        return id to type.toInt()
    }
    fun writeParameters(id:Long,type:Int)=JSONObject().put("dirId",201).put("v_songInfo",JSONArray().put(JSONObject().put("songId",id).put("songType",type)))
}

interface QqFavoriteGateway {
    suspend fun read(mid:String):Boolean
    suspend fun identity(song:QqCatalogueTrack):Pair<Long,Int>
    suspend fun write(id:Long,type:Int,liked:Boolean)
}
data class FavoriteOutcome(val confirmed:Boolean,val actual:Boolean?,val message:String)
object FavoriteOperation {
    suspend fun set(song:QqCatalogueTrack,desired:Boolean,gateway:QqFavoriteGateway,current:()->Boolean):FavoriteOutcome {
        fun check(){if(!current())throw kotlinx.coroutines.CancellationException("Account changed")}
        check();val before=gateway.read(song.mid);check()
        if(before==desired)return FavoriteOutcome(true,before,if(desired)"已在 QQ 我喜欢中" else "已取消 QQ 收藏")
        val identity=gateway.identity(song);check()
        try {gateway.write(identity.first,identity.second,desired)}
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){
            check()
            val actual=try {gateway.read(song.mid)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null};check()
            return FavoriteOutcome(actual==desired,actual,if(actual==desired)"QQ 收藏状态已确认" else "收藏结果尚未确认，请刷新状态；不会自动重复提交。")
        }
        check()
        repeat(3){attempt ->
            val actual=try {gateway.read(song.mid)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){null}
            check()
            if(actual==desired)return FavoriteOutcome(true,actual,if(desired)"已收藏到 QQ 我喜欢" else "已取消 QQ 收藏")
            if(attempt<2)kotlinx.coroutines.delay(400)
        }
        return FavoriteOutcome(false,null,"QQ 尚未确认变更，请刷新收藏状态。")
    }
}
