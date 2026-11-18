package com.resonance.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GenreTilePresentationTest {
    @Test fun englishGenreWrapsOnlyAtWordBoundaries() {
        assertEquals("contemporary\nr&b", genreTitleLines("contemporary r&b", 12, String::length))
        assertEquals("contempora…\nr&b", genreTitleLines("contemporary r&b", 11, String::length))
        assertEquals("blue-\neyed so…", genreTitleLines("blue-eyed soul", 8, String::length))
    }
}
