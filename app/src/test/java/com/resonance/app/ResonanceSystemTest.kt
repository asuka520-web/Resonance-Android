package com.resonance.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class ResonanceSystemTest {
    @Test
    fun visibleBoardAndThumbnailsStayWithinSharedBudget() {
        val budget = SandBudget()
        budget.place("board", 2060, 1)
        repeat(4) { budget.place(it, 230, 0) }
        assertEquals(2060, budget.granted("board"))
        assertEquals(2100, budget.granted("board") + (0 until 4).sumOf { budget.granted(it) })
        budget.place("player", 2060, 3)
        assertEquals(2060, budget.granted("player"))
        assertEquals(40, budget.granted("board"))
        assertEquals(0, budget.granted(0))
        budget.place("board", 0, 1)
        budget.place("player", 0, 3)
        assertEquals(230, budget.granted(0))
    }

    @Test
    fun intentControlsNeverPutOneTagOnBothSides() {
        val input = ListeningIntent(wanted = setOf("pop"), excluded = setOf("rock"), matchAll = true)
        val neutral = input.withTagPosition("pop", .2f)
        assertEquals(0f, neutral.tagPosition("pop"), .001f)
        val avoided = neutral.withTagPosition("pop", -.8f)
        assertEquals(setOf("rock", "pop"), avoided.excluded)
        val wanted = avoided.withTagPosition("pop", 1f)
        assertEquals(setOf("pop"), wanted.wanted)
        assertEquals(setOf("rock"), wanted.excluded)
        assertTrue(wanted.matchAll)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResonanceLegacyWidthsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h820dp-xhdpi")
    fun legacyFitsAt360() = verify()

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h891dp-xhdpi")
    fun legacyFitsAt412() = verify()

    private fun verify() {
        listOf("共鸣 · 旧版监听与记录", "进入 QQ 账号播放器（接入验证）", "当前歌曲").forEach { label ->
            val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertTrue("Text overflow in $label", layouts.all { !it.didOverflowHeight && !it.isLineEllipsized(0) })
        }
    }
}
