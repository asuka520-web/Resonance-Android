package com.resonance.app

import org.json.JSONObject

/** Controlled non-genre recording annotations. No album/artist tags and no acoustic-inference claim. */
object RecordingDescriptors {
    private val aliases=mapOf("happy" to "mood:bright","sad" to "mood:sad","melancholic" to "mood:sad",
        "melancholy" to "mood:sad","relaxing" to "mood:calm","calm" to "mood:calm","energetic" to "mood:energetic",
        "piano" to "instrument:piano","guitar" to "instrument:guitar","violin" to "instrument:violin",
        "saxophone" to "instrument:saxophone","instrumental" to "voice:instrumental")
    val allowed=aliases.values.toSet()
    fun parse(raw:String,expectedId:String):Set<String> {
        val obj=JSONObject(raw);require(obj.optString("id")==expectedId)
        val rows=obj.optJSONArray("tags")?:return emptySet()
        return (0 until rows.length()).mapNotNull {i->val tag=rows.optJSONObject(i)
            if((tag?.optInt("count")?:0)<2)null else aliases[tag?.optString("name")?.trim()?.lowercase(java.util.Locale.ROOT)]}.toSet()
    }
}
