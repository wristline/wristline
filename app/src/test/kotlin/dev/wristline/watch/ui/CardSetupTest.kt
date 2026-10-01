package dev.wristline.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardSetupTest {
    @Test
    fun variantsGoFromFullestToShortest() {
        assertEquals(listOf("Fable 5.1 · medium", "Fable 5.1", "Fable"), setupVariants("Fable 5.1", "medium"))
        assertEquals(listOf("Fable 5.1", "Fable"), setupVariants("Fable 5.1", null))
        // One word: nothing shorter to fall back to.
        assertEquals(listOf("gpt-5.3-codex · high", "gpt-5.3-codex"), setupVariants("gpt-5.3-codex", "high"))
        assertEquals(listOf("high"), setupVariants(null, "high"))
        assertEquals(listOf("high"), setupVariants(" ", "high"))
        assertEquals(emptyList<String>(), setupVariants(null, null))
    }

    // Widths in px: a 160 line, a 6 gap, the title kept at 80 or more, the setup at 70 or more.
    private fun choice(title: Int, vararg setup: Int) = setupChoice(title, setup.toList(), 160, 6, 80, 70)

    @Test
    fun theFullestThatLeavesTheTitleItsShareShows() {
        // Both fit whole.
        assertEquals(0 to 60, choice(50, 60, 40, 30))
        // A short title keeps all its width; the setup takes the rest.
        assertEquals(0 to 100, choice(50, 100, 40, 30))
        // A long title keeps 80: 74 left, so the model alone.
        assertEquals(1 to 40, choice(200, 100, 40, 30))
        assertEquals(2 to 30, choice(200, 100, 90, 30))
    }

    @Test
    fun theShortestShrinksNoFurtherThanItsMinimum() {
        // Even the shortest is wider than the 74 left: cut to that.
        assertEquals(1 to 74, choice(200, 120, 100))
        // On a narrow line it keeps 70, the title taking less than 80.
        assertEquals(0 to 70, setupChoice(200, listOf(100), 120, 6, 80, 70))
        // Under 70 of its own it keeps its width.
        assertEquals(0 to 50, setupChoice(200, listOf(50), 100, 6, 80, 70))
    }

    @Test
    fun nothingWithoutVariants() {
        assertNull(choice(100))
    }
}
