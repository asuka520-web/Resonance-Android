package com.resonance.app

import android.content.Context

/** Process state only; feature provenance and the new, explicit control opt-in are persisted separately. */
object StyleSession {
    const val AUTO_CONTROL = "style_auto_control_enabled"
    var plan: StylePlan? = null
        private set
    private val listeners = mutableSetOf<(StylePlan?) -> Unit>()
    fun subscribe(listener: (StylePlan?) -> Unit) { listeners.add(listener); listener(plan) }
    fun unsubscribe(listener: (StylePlan?) -> Unit) { listeners.remove(listener) }
    fun show(value: StylePlan) { plan = value; listeners.toList().forEach { it(value) } }

    fun similar(context: Context, mediaId: String) {
        show(StyleRecommendations.similar(mediaId, TrackFeatureStore.tracks(context), TrackFeatureStore.events(context)))
    }

    fun changeDirection(context: Context, mediaId: String, automaticTrigger: Boolean) {
        val next = StyleRecommendations.changeDirection(mediaId, TrackFeatureStore.tracks(context), TrackFeatureStore.events(context))
        show(next)
        if (automaticTrigger && context.getSharedPreferences("resonance_settings", Context.MODE_PRIVATE)
                .getBoolean(AUTO_CONTROL, false)) {
            if (!QqPlayback.isPlaying) {
                show(next.copy(explanation = next.explanation + "\n未自动播放：QQ 音乐当前已暂停，不恢复播放。"))
                return
            }
            val target = next.picks.firstOrNull { QqPlayback.snapshot().canRequest(it.track.mediaId) }
            if (target != null) QqPlayback.request(context, target.track.mediaId)
            else show(next.copy(explanation = next.explanation + "\n未自动播放：缺少符合条件的候选或 QQ 音乐未公开指定播放能力。"))
        }
    }
}
