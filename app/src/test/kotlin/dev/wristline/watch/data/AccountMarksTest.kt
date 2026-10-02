package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMarksTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    private fun codex(id: String, label: String, primary: Boolean = false) = ProviderId.CODEX to Account(id, label, primary = primary)

    private fun AccountBook.marks(vararg accounts: Pair<String, Account>) = accounts.map { (provider, account) -> look(provider, account).mark }

    @Test
    fun autoMarksAreTheLabelsLettersWithoutClashes() {
        val work = codex("w", "Work", primary = true)
        val pro = codex("p", "Pro")
        assertEquals(listOf("W", "P"), AccountBook().seen(listOf(pro, work), t0).marks(work, pro))
        // Pro takes P; Plus its next letter.
        val plus = codex("l", "Plus")
        assertEquals(listOf("P", "L"), AccountBook().seen(listOf(codex("p", "Pro", primary = true), plus), t0).marks(pro, plus))
        // Two logins of the same person: the local parts' J, then the domain's G.
        val school = codex("a", "jwpark12@sungshin.ac.kr", primary = true)
        val gmail = codex("b", "jwpark@gmail.com")
        assertEquals(listOf("J", "G"), AccountBook().seen(listOf(gmail, school), t0).marks(school, gmail))
        // No Latin letters: digits.
        val company = codex("c", "회사", primary = true)
        val personal = codex("d", "개인")
        assertEquals(listOf("1", "2"), AccountBook().seen(listOf(company, personal), t0).marks(company, personal))
    }

    @Test
    fun autoCandidatesAreUpperCaseLatinLettersThenDigits() {
        assertEquals(listOf("A", "C", "M", "E", "O", "R", "P"), autoCandidates("acme corp").take(7))
        // Accents dropped, Hangul and emoji skipped.
        assertEquals("E", autoCandidates("élan").first())
        assertEquals("W", autoCandidates("회사 work").first())
        assertEquals((1..9).map(Int::toString), autoCandidates("🏠 집"))
        // Every candidate taken: the last digit rather than nothing.
        assertEquals("9", autoMark("회사", (1..9).map(Int::toString).toSet()))
    }

    @Test
    fun marksStayWhenAnotherAccountLogsOut() {
        val pro = codex("a", "Pro", primary = true)
        val plus = codex("b", "Plus")
        val personal = codex("c", "Personal")
        val all = AccountBook().seen(listOf(pro, plus, personal), t0)
        assertEquals(listOf("P", "L", "E"), all.marks(pro, plus, personal))
        // Pro logged out: the others keep theirs, and P stays Pro's for its return.
        val later = all.seen(listOf(plus, personal), t0 + day)
        assertEquals(listOf("L", "E"), later.marks(plus, personal))
        val back = later.seen(listOf(pro, plus, personal), t0 + 2 * day)
        assertEquals(listOf("P", "L", "E"), back.marks(pro, plus, personal))
        // A newcomer avoids the remembered P.
        val pat = codex("d", "Pat")
        assertEquals("A", later.seen(listOf(plus, personal, pat), t0 + day).look(pat.first, pat.second).mark)
    }

    @Test
    fun accountsUnseenForNinetyDaysAreForgotten() {
        val pro = codex("a", "Pro", primary = true)
        val plus = codex("b", "Plus")
        val book = AccountBook().seen(listOf(pro, plus), t0)
            .let { it.move(accountKey(plus.first, plus.second), -1, it.ordered(ProviderId.CODEX)) }
        // Plus is seen every day; Pro not after the first.
        var later = book
        for (d in 1..91) later = later.seen(listOf(plus), t0 + d * day)
        assertEquals(setOf("codex:b"), later.accounts.keys)
        assertEquals(mapOf(ProviderId.CODEX to listOf("codex:b")), later.order)
        // Pro's P is free again.
        val pat = codex("d", "Pat")
        assertEquals("P", later.seen(listOf(plus, pat), t0 + 92 * day).look(pat.first, pat.second).mark)
        // At 90 days it was still remembered.
        assertTrue("codex:a" in book.seen(listOf(plus), t0 + 90 * day).accounts)
    }

    @Test
    fun seeingTheSameAccountsChangesNothing() {
        val accounts = listOf(codex("a", "Pro", primary = true), codex("b", "Plus"))
        val book = AccountBook().seen(accounts, t0)
        // Within a day not even the seen time moves: no write.
        assertEquals(book, book.seen(accounts, t0 + day / 2))
        assertEquals(AccountBook(), AccountBook().seen(emptyList(), t0))
    }

    @Test
    fun picksAndNicknamesWithResetToAuto() {
        val work = codex("w", "Work", primary = true)
        val pro = codex("p", "Pro")
        val key = accountKey(work.first, work.second)
        val book = AccountBook().seen(listOf(work, pro), t0)
        val home = book.edit(key) { it.copy(mark = Mark(MarkType.EMOJI, MARK_EMOJI[0]), color = "sky") }
        assertEquals(AccountLook(MARK_EMOJI[0], "sky", "Work"), home.look(work.first, work.second))
        // The picker dims what the other account shows.
        assertEquals(setOf("P"), home.shownByOthers(ProviderId.CODEX, key))
        // A nickname is the name everywhere, and the automatic mark follows it.
        val named = book.edit(key) { it.copy(nickname = "Company") }
        assertEquals(AccountLook("C", null, "Company"), named.look(work.first, work.second))
        // A blank nickname falls back to the label.
        assertEquals("Work", book.edit(key) { it.copy(nickname = " ") }.look(work.first, work.second).name)
        // Reset: the label's mark, white.
        assertEquals(AccountLook("W", null, "Work"), named.reset(key).look(work.first, work.second))
        // Unknown to the book yet: its label's first letter.
        assertEquals(AccountLook("N", null, "new"), book.look(ProviderId.CODEX, Account("x", "new")))
    }

    @Test
    fun resetAvoidsAMarkASiblingTookMeanwhile() {
        val work = codex("w", "Work", primary = true)
        val wife = codex("v", "Wife")
        val workKey = accountKey(work.first, work.second)
        val book = AccountBook().seen(listOf(work, wife), t0)
        assertEquals(listOf("W", "I"), book.marks(work, wife))
        // Work shows a house; Wife picks the W it no longer shows; Work back to auto must not clash.
        val swapped = book.edit(workKey) { it.copy(mark = Mark(MarkType.EMOJI, MARK_EMOJI[0])) }
            .edit(accountKey(wife.first, wife.second)) { it.copy(mark = Mark(MarkType.LETTER, "W")) }
        assertEquals(listOf("O", "W"), swapped.reset(workKey).marks(work, wife))
    }

    @Test
    fun orderIsTheUsersThenPrimaryThenFirstSeen() {
        val a = codex("a", "Alpha")
        val b = codex("b", "Beta", primary = true)
        val c = codex("c", "Gamma")
        // Seen together: the primary first, then by label.
        val book = AccountBook().seen(listOf(a, c, b), t0)
        assertEquals(listOf("codex:b", "codex:a", "codex:c"), book.ordered(ProviderId.CODEX))
        // Seen later, after the others, whatever its label.
        val d = codex("d", "Aardvark")
        val more = book.seen(listOf(a, b, c, d), t0 + day)
        assertEquals(listOf("codex:b", "codex:a", "codex:c", "codex:d"), more.ordered(ProviderId.CODEX))
        // The user's order wins; moving among the shown ones keeps the rest in place.
        val moved = more.move("codex:c", -1, listOf("codex:b", "codex:c"))
        assertEquals(listOf("codex:c", "codex:a", "codex:b", "codex:d"), moved.ordered(ProviderId.CODEX))
        assertEquals(listOf("codex:a", "codex:c", "codex:b", "codex:d"), moved.move("codex:a", -1, moved.ordered(ProviderId.CODEX)).ordered(ProviderId.CODEX))
        // Past either end: unchanged.
        assertEquals(moved, moved.move("codex:c", -1, moved.ordered(ProviderId.CODEX)))
        // A newcomer goes after the ordered ones.
        val e = codex("e", "Early", primary = true)
        assertEquals("codex:e", moved.seen(listOf(a, b, c, d, e), t0 + 2 * day).ordered(ProviderId.CODEX).last())
    }

    @Test
    fun theBookSurvivesItsJson() {
        val book = AccountBook().seen(listOf(codex("a", "Pro", primary = true), codex("b", "Plus")), t0)
            .edit("codex:b") { it.copy(mark = Mark(MarkType.DIGIT, "2"), color = "green", nickname = "Home") }
        val json = WireJson.encodeToString(AccountBook.serializer(), book)
        assertEquals(book, WireJson.decodeFromString(AccountBook.serializer(), json))
    }
}
