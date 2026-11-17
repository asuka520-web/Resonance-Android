package com.resonance.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QqFavoriteProtocolTest {
    private val song=QqCatalogueTrack("mid","mid","歌曲","歌手","专辑",200000,"123")
    private class Gateway:QqFavoriteGateway {
        var liked=false;var writes=0;var identities=0;var reads=0;var reject=false;var reportChange=true
        override suspend fun read(mid:String):Boolean {reads++;return liked}
        override suspend fun identity(song:QqCatalogueTrack):Pair<Long,Int>{identities++;return 123L to 0}
        override suspend fun write(id:Long,type:Int,liked:Boolean){writes++;if(reject)throw IllegalStateException();if(reportChange)this.liked=liked}
    }
    @Test fun fixedCryptoVectorsMatchIndependentNodeCryptoImplementation() {
        assertEquals("zzcf8e26805gyafigxmxjehoe02mvsjjgtwzw6f1a05f9",QqFavoriteProtocol.sign("{}"))
        assertEquals("AAECAwQFBgcICQoLKs2V92Pw+1OrNuP0r6CU/4k9",QqFavoriteProtocol.encrypt("{}",ByteArray(12){it.toByte()}))
    }
    @Test fun responseCodesAndBooleanAreRequiredRatherThanTreatingMissingAsFalse() {
        val response="""{"code":0,"req_0":{"code":0,"data":{"m_fan":{"mid":true},"retCode":0}}}"""
        assertTrue(QqFavoriteProtocol.status(response,"mid"));QqFavoriteProtocol.data(response,true)
        listOf(response.replace("\"code\":0","\"code\":80105"),response.replace("\"retCode\":0","\"retCode\":1"),response.replace(",\"retCode\":0","" )).forEach {
            assertThrows(QqConnectionException::class.java){QqFavoriteProtocol.data(it,true)}
        }
        assertThrows(QqConnectionException::class.java){QqFavoriteProtocol.status(response,"absent")}
        assertThrows(QqConnectionException::class.java){QqFavoriteProtocol.status(response.replace("true","1"),"mid")}
    }
    @Test fun identityMustMatchFreshMidNumericIdAndRealSongType() {
        val raw="""{"code":0,"req_0":{"code":0,"data":{"track_info":{"mid":"mid","id":123,"type":0}}}}"""
        assertEquals(123L to 0,QqFavoriteProtocol.identity(raw,song))
        listOf(raw.replace("\"mid\":\"mid\"","\"mid\":\"other\""),raw.replace("123","456"),raw.replace("\"type\":0","\"type\":0.5")).forEach {
            assertThrows(QqConnectionException::class.java){QqFavoriteProtocol.identity(it,song)}
        }
        assertEquals(201,QqFavoriteProtocol.writeParameters(123,0).getInt("dirId"))
    }
    @Test fun syncRequiresReadBackAndAlreadyDesiredIsIdempotent()=runBlocking {
        val gateway=Gateway();val first=FavoriteOperation.set(song,true,gateway){true}
        assertTrue(first.confirmed);assertEquals(true,first.actual);assertEquals(1,gateway.writes)
        FavoriteOperation.set(song,true,gateway){true};assertEquals(1,gateway.writes);assertEquals(1,gateway.identities)
        val removed=FavoriteOperation.set(song,false,gateway){true};assertTrue(removed.confirmed);assertEquals(false,removed.actual);assertEquals(2,gateway.writes)
    }
    @Test fun uncertainWritesAreNotRetriedOrReportedSuccessful()=runBlocking {
        val gateway=Gateway().apply {reject=true}
        val failed=FavoriteOperation.set(song,true,gateway){true}
        assertFalse(failed.confirmed);assertEquals(false,failed.actual);assertEquals(1,gateway.writes)
        val delayed=Gateway().apply {reportChange=false}
        assertFalse(FavoriteOperation.set(song,true,delayed){true}.confirmed);assertEquals(1,delayed.writes);assertEquals(4,delayed.reads)
    }
    @Test fun accountChangeBeforeWriteCancelsOperation()=runBlocking {
        val gateway=Gateway()
        try {FavoriteOperation.set(song,true,gateway){gateway.identities==0};fail("Should cancel")}
        catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals(0,gateway.writes)
    }
}
