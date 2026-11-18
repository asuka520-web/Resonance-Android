package com.resonance.app

/** Family relations guide comparisons. They never add tags to the catalogue or scores. */
internal object GenreEvidence {
    private val families = mapOf(
        "alternative r&b" to setOf("r&b"),
        "contemporary r&b" to setOf("r&b"),
        "neo soul" to setOf("soul", "r&b"),
        "blue-eyed soul" to setOf("soul"),
        "alternative rock" to setOf("rock"),
        "indie rock" to setOf("rock"),
        "pop rock" to setOf("rock", "pop"),
        "synth-pop" to setOf("electronic", "pop"),
        "electronica" to setOf("electronic"),
        "house" to setOf("electronic", "dance"),
        "dubstep" to setOf("electronic"),
        "uk garage" to setOf("electronic", "dance")
    )
    private val generic = setOf("miscellaneous", "other", "unknown", "world music", "其他", "未知")

    fun comparable(labels: Set<String>): Set<String> = labels.map(CatalogueCodec::normalizeGenre)
        .filter {it.isNotBlank() && it !in generic}.toSet()

    fun withFamilies(labels: Set<String>): Set<String> = comparable(labels).flatMap {label ->
        families[label].orEmpty() + label
    }.toSet()

    fun overlaps(labels: Set<String>, avoided: Set<String>): Boolean =
        avoided.isNotEmpty() && withFamilies(labels).any {it in avoided}

    fun affinity(left: Set<String>, right: Set<String>): Double {
        val exactLeft = comparable(left)
        val exactRight = comparable(right)
        if (exactLeft.isEmpty() || exactRight.isEmpty()) return 0.0
        val exact = exactLeft.intersect(exactRight).size.toDouble() / exactLeft.union(exactRight).size
        val leftFamilies = withFamilies(exactLeft)
        val rightFamilies = withFamilies(exactRight)
        val family = leftFamilies.intersect(rightFamilies).size.toDouble() / leftFamilies.union(rightFamilies).size
        return maxOf(exact, family * .65)
    }
}

internal fun recentDirectionTargets(history: List<DirectionSwitch>, tracks: List<StyleTrack>): Set<String> {
    val byId = tracks.associateBy {it.mediaId}
    val recent = history.takeLast(20).mapNotNull {row ->
        row.targetMid.takeIf(String::isNotBlank)?.let {mid ->
            val id = "qq:$mid"
            id to (row.targetRecordingId.ifBlank {byId[id]?.recordingId.orEmpty()})
        }
    }
    val recordings = recent.map {it.second}.filter(String::isNotBlank).toSet()
    return recent.map {it.first}.toSet() + tracks.filter {it.recordingId in recordings}.map {it.mediaId}
}
