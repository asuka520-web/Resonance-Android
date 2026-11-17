package com.resonance.app

import android.content.Context
import java.io.File
import org.json.JSONArray

/** Wall-clock audible time, independent of feedback classification, seeks and history retention. */
class ListeningTimeCounter(initialMs:Long=0) {
    var totalMs=initialMs.coerceAtLeast(0)
        private set
    private var lastAt:Long?=null
    private var wasPlaying=false
    fun tick(now:Long,playing:Boolean):Long {
        val elapsed=lastAt?.let {now-it}?:0
        if(wasPlaying && elapsed in 1..10_000L)totalMs+=elapsed
        lastAt=now;wasPlaying=playing
        return totalMs
    }
}

object ListeningTimeStore {
    private fun preferences(context:Context)=context.getSharedPreferences("resonance_listening_time",Context.MODE_PRIVATE)
    fun retainedTime(rows:JSONArray):Long=(0 until rows.length()).sumOf {index ->
        val row=rows.optJSONObject(index)
        val heard=row?.optLong("playedMs")?.coerceAtLeast(0)?:0
        val duration=row?.optLong("durationMs")?.coerceAtLeast(0)?:0
        heard.coerceAtMost(duration)
    }
    @Synchronized fun totalMs(context:Context):Long {
        val prefs=preferences(context)
        if(!prefs.getBoolean("initialized",false)) {
            val files=File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty()
            val recovered=files.filter {it.name.startsWith("qq_library_") && it.extension=="xml"}.sumOf {file ->
                val raw=context.getSharedPreferences(file.nameWithoutExtension,Context.MODE_PRIVATE).getString("playerEvents","[]")
                retainedTime(runCatching {JSONArray(raw)}.getOrDefault(JSONArray()))
            }
            prefs.edit().putLong("totalMs",recovered).putBoolean("initialized",true).apply()
        }
        return prefs.getLong("totalMs",0).coerceAtLeast(0)
    }
    fun save(context:Context,totalMs:Long) {
        preferences(context).edit().putLong("totalMs",totalMs.coerceAtLeast(0)).putBoolean("initialized",true).apply()
    }
}
