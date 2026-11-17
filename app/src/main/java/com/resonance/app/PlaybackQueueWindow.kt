package com.resonance.app

/** Extends queue references near its tail; this never fetches audio for the whole library. */
object PlaybackQueueWindow {
    fun following(queue: List<String>, library: List<String>, currentIndex: Int): List<String> {
        if (queue.isEmpty() || queue.size - currentIndex > 6) return emptyList()
        val last = library.indexOf(queue.last())
        if (last < 0) return emptyList()
        val existing = queue.toSet()
        return library.drop(last + 1).filter { it !in existing }.take(50)
    }
}
