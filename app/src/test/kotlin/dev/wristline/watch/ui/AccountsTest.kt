package dev.wristline.watch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.wristline.watch.data.Account
import dev.wristline.watch.data.AccountBook
import dev.wristline.watch.data.Mark
import dev.wristline.watch.data.MarkType
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import dev.wristline.watch.data.UsageWindow
import dev.wristline.watch.data.move
import dev.wristline.watch.data.seen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    /** WCAG 2 contrast ratio. */
    private fun contrast(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)

    @Test
    fun everyMarkColorKeepsItsGlyphLegible() {
        assertEquals(6, MarkColor.entries.size)
        for (color in MarkColor.entries) {
            assertTrue("$color ${contrast(color.glyph, color.disc)}", contrast(color.glyph, color.disc) >= 4.5f)
            // Not the limit card's yellow or red, which mean 80% and 95%.
            assertNotEquals(Status.Attention, color.disc)
            assertNotEquals(WristlineColors.error, color.disc)
        }
        // White by default, for an unknown id too.
        assertEquals(MarkColor.WHITE, markColor(null))
        assertEquals(MarkColor.WHITE, markColor("teal"))
        assertEquals(MarkColor.BLUE, markColor("blue"))
    }

    @Test
    fun thePickerOffersEmojiLettersAndDigitsOnce() {
        assertEquals(6 + 26 + 9, PICKER_MARKS.size)
        assertEquals(PICKER_MARKS.size, PICKER_MARKS.map { it.value }.distinct().size)
        assertEquals(listOf(MarkType.EMOJI, MarkType.LETTER, MarkType.DIGIT), PICKER_MARKS.map { it.type }.distinct())
        // Each tab offers its own, emoji first; together all of them.
        assertEquals(listOf(MarkTab.EMOJI, MarkTab.LETTERS, MarkTab.DIGITS), MarkTab.entries)
        assertEquals(PICKER_MARKS, MarkTab.entries.flatMap { it.marks })
        assertEquals(listOf(6, 26, 9), MarkTab.entries.map { it.marks.size })
    }

    @Test
    fun thePickerOpensOnThePickedMarksTab() {
        assertEquals(MarkTab.EMOJI, tabOf(null))
        assertEquals(MarkTab.EMOJI, tabOf(Mark(MarkType.EMOJI, "\u2B50")))
        assertEquals(MarkTab.LETTERS, tabOf(Mark(MarkType.LETTER, "W")))
        assertEquals(MarkTab.DIGITS, tabOf(Mark(MarkType.DIGIT, "2")))
        // A type this version does not know: emoji.
        assertEquals(MarkTab.EMOJI, tabOf(Mark("shape", "x")))
    }

    @Test
    fun theAccountsScreenListsProvidersWithMoreThanOneInTheirOrder() {
        val at = "2026-09-29T00:00:00Z"
        val work = Account("w", "Work", primary = true)
        val pro = Account("p", "Pro")
        val me = Account("me", "me@gmail.com")
        val usage = listOf(
            Usage(ProviderId.CODEX, at, listOf(UsageWindow("primary", 1.0)), pro),
            Usage(ProviderId.CODEX, at, listOf(UsageWindow("primary", 1.0)), work),
            Usage(ProviderId.CLAUDE_CODE, at, listOf(UsageWindow("5h", 1.0)), me),
        )
        val sessions = listOf(Session("codex:1", ProviderId.CODEX, status = SessionStatus.IDLE, lastActivity = at, account = pro))
        // Claude has one account: not listed. A remembered account not shown now: not listed either.
        val book = AccountBook().seen(listOf(ProviderId.CODEX to Account("old", "Old")), 0)
            .seen(usage.map { it.provider to it.account!! }, 1)
        assertEquals(listOf(ProviderId.CODEX to listOf("codex:w", "codex:p")), accountGroups(book, usage, sessions))
        val moved = book.move("codex:p", -1, listOf("codex:w", "codex:p"))
        assertEquals(listOf(ProviderId.CODEX to listOf("codex:p", "codex:w")), accountGroups(moved, usage, sessions))
    }
}
