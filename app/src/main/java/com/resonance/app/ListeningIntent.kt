package com.resonance.app

import androidx.media3.common.Player

/** Explicit session choices; unknown labels cannot prove that a constraint is satisfied. */
data class ListeningIntent(val wanted:Set<String> = emptySet(),val excluded:Set<String> = emptySet(),val matchAll:Boolean=false) {
    val active get()=wanted.isNotEmpty() || excluded.isNotEmpty()
    fun accepts(labels:Set<String>):Boolean {
        if(!active)return true
        if(labels.isEmpty() || labels.any {it in excluded})return false
        return wanted.isEmpty() || if(matchAll)labels.containsAll(wanted) else labels.any {it in wanted}
    }
    fun normalized():ListeningIntent {
        val avoided=excluded.map(CatalogueCodec::normalizeGenre).filter(String::isNotBlank).toSet()
        return copy(wanted=wanted.map(CatalogueCodec::normalizeGenre).filter {it.isNotBlank() && it !in avoided}.toSet(),excluded=avoided)
    }
}

enum class PlaybackMode(val label:String,val shortLabel:String) {
    SEQUENTIAL("顺序播放","顺序"),LOOP("列表循环","循环"),SHUFFLE("随机播放","随机"),SINGLE("单曲循环","单曲");

    fun next() = when (this) {
        SINGLE -> LOOP
        LOOP -> SHUFFLE
        SHUFFLE, SEQUENTIAL -> SINGLE
    }

    internal fun applyTo(player: Player) {
        player.shuffleModeEnabled = this == SHUFFLE
        player.repeatMode = when (this) {
            SINGLE -> Player.REPEAT_MODE_ONE
            LOOP, SHUFFLE -> Player.REPEAT_MODE_ALL
            SEQUENTIAL -> Player.REPEAT_MODE_OFF
        }
    }

    companion object {
        fun parse(value:String)=entries.firstOrNull {it.name==value}?:SEQUENTIAL
        fun fromPlayer(repeatMode:Int,shuffle:Boolean)=when {
            repeatMode==Player.REPEAT_MODE_ONE->SINGLE
            shuffle->SHUFFLE
            repeatMode==Player.REPEAT_MODE_ALL->LOOP
            else->SEQUENTIAL
        }
    }
}
