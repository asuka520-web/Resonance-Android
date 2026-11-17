package com.resonance.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ListeningStatsTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Test fun scoreResetIsPersistentAndAccountScopedWithoutErasingHistoryOrDuration() {
        val scope="reset-test"
        context.getSharedPreferences("qq_library_$scope",0).edit().clear().commit()
        CatalogueStore.record(context,scope,"old",120000,120000,"completed","user")
        CatalogueStore.record(context,"other-test","old",120000,120000,"completed","user")
        ListeningTimeStore.save(context,600000)
        assertEquals(1,CatalogueStore.scoreEvents(context,scope).length())
        assertEquals(1L,CatalogueStore.clearScores(context,scope))
        assertEquals(0,CatalogueStore.scoreEvents(context,scope).length())
        assertEquals(1,CatalogueStore.events(context,scope).length())
        assertTrue(CatalogueStore.scoreEvents(context,"other-test").length()>0)
        assertEquals(600000L,ListeningTimeStore.totalMs(context))
        CatalogueStore.record(context,scope,"new",5000,120000,"fast","user")
        assertEquals(2,CatalogueStore.events(context,scope).length())
        val scoring=StyleRecommendations.signals(CatalogueStore.scoreEvents(context,scope))
        assertEquals(listOf(ListeningSignal("qq:new","fast")),scoring)
        val tracks=listOf(StyleTrack("qq:old","old","a","old",setOf("pop")),StyleTrack("qq:new","new","b","new",setOf("jazz")))
        val scores=GenrePreferenceScores.calculate(tracks,scoring).associateBy {it.label}
        assertEquals(0.0,scores.getValue("pop").combined,0.001)
        assertEquals(-3.0,scores.getValue("jazz").combined,0.001)
    }
    @Test fun activeFeedbackPersistsPerAccountAndClearScoresRemovesIt() {
        CatalogueStore.saveManualScores(context,"active",mapOf("pop" to 4.0))
        assertEquals(4.0,CatalogueStore.manualScores(context,"active").getValue("pop"),0.0)
        CatalogueStore.clearScores(context,"active")
        assertTrue(CatalogueStore.manualScores(context,"active").isEmpty())
    }
    @Test fun legacyHistoryFeedsScoresUntilExplicitResetAndNewScoresStayBounded() {
        val scope="legacy-scores"
        val prefs=context.getSharedPreferences("qq_library_$scope",0)
        val rows=JSONArray().put(JSONObject().put("mediaId","qq:old").put("category","completed"))
        prefs.edit().clear().putString("playerEvents",rows.toString()).commit()
        assertEquals(1,CatalogueStore.scoreEvents(context,scope).length())
        CatalogueStore.clearScores(context,scope)
        repeat(505){CatalogueStore.record(context,scope,"s$it",1000,200000,"fast","user")}
        assertEquals(500,CatalogueStore.events(context,scope).length())
        assertEquals(500,CatalogueStore.scoreEvents(context,scope).length())
        assertEquals("qq:s5",CatalogueStore.scoreEvents(context,scope).getJSONObject(0).getString("mediaId"))
    }
    @Test fun listeningCounterCountsRealIntervalsAndPausesWithoutDuplicateTicksOrSeekInflation() {
        val counter=ListeningTimeCounter(1000)
        counter.tick(0,true)
        for(now in 1000L..60000L step 1000)counter.tick(now,true)
        assertEquals(61000L,counter.totalMs)
        counter.tick(60000,false);counter.tick(120000,false)
        assertEquals(61000L,counter.totalMs)
        counter.tick(120000,true);counter.tick(121000,true);counter.tick(121000,true)
        assertEquals(62000L,counter.totalMs)
        // No media position enters the counter: seeks cannot change elapsed listening time.
        counter.tick(200000,true)
        assertEquals(62000L,counter.totalMs)
        counter.tick(201000,false);assertEquals(63000L,counter.totalMs)
    }
    @Test fun timeSurvivesReopenAndHistoryMigrationRunsOnlyOnce() {
        val prefs=context.getSharedPreferences("resonance_listening_time",0)
        prefs.edit().clear().commit()
        val rows=JSONArray().put(JSONObject().put("playedMs",120000).put("durationMs",200000))
        context.getSharedPreferences("qq_library_time-migration",0).edit().putString("playerEvents",rows.toString()).commit()
        val initial=ListeningTimeStore.totalMs(context)
        assertTrue(initial>=120000)
        ListeningTimeStore.save(context,initial+60000)
        assertEquals(initial+60000,ListeningTimeStore.totalMs(context))
        assertEquals(initial+60000,ListeningTimeCounter(ListeningTimeStore.totalMs(context)).totalMs)
    }
    @Test fun migrationClampsInvalidAndOverlongHistoricalIntervals() {
        val rows=JSONArray().put(JSONObject().put("playedMs",4000).put("durationMs",3000))
            .put(JSONObject().put("playedMs",-9).put("durationMs",2000)).put(JSONObject().put("playedMs",2000))
        assertEquals(3000L,ListeningTimeStore.retainedTime(rows))
    }
    @Test fun clearedScoresChangeRankingWhileHistoryRetainsFamiliarity() {
        val tracks=listOf(StyleTrack("qq:anchor","anchor","a","anchor",setOf("pop")),
            StyleTrack("qq:a","a","b","a",setOf("pop","rock")),StyleTrack("qq:z","z","c","z",setOf("pop","soul")),
            StyleTrack("qq:liked","liked","d","liked",setOf("soul")))
        val history=listOf(ListeningSignal("qq:liked","completed"))
        val familiar=tracks.map {it.mediaId}.toSet()
        assertEquals("qq:z",StyleRecommendations.similar("qq:anchor",tracks,history,familiar).picks.first().track.mediaId)
        val reset=StyleRecommendations.similar("qq:anchor",tracks,history,familiar,scoreEvents=emptyList())
        assertEquals("qq:a",reset.picks.first().track.mediaId)
        assertTrue(reset.picks.none {it.unfamiliar})
    }
    @Test fun resettingDirectionStartsFourFreshSkipsWithoutLosingActualListening() {
        val tracker=DirectListeningTracker()
        tracker.transition("a",200000,TrackTransition.OTHER,0)
        tracker.tick(0,true);tracker.tick(1000,true)
        tracker.transition("b",200000,TrackTransition.USER,1000)
        assertEquals(1,tracker.fastSkips)
        tracker.tick(1000,true);tracker.tick(2000,true)
        tracker.resetDirection()
        val event=tracker.transition("c",200000,TrackTransition.USER,2000)!!
        assertEquals(1000L,event.heardMs);assertEquals(1,tracker.fastSkips);assertFalse(event.changeDirection)
    }
}
