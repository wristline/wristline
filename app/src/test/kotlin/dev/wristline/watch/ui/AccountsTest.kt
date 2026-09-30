package dev.wristline.watch.ui

import dev.wristline.watch.data.Account
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    private val at = "2026-09-29T00:00:00Z"
    private val me = Account("acc-me", "me@gmail.com")
    private val school = Account("acc-school", "school", estimated = true)
    private val codex = Account("chatgpt-1", "me@gmail.com")

    private fun session(id: String, provider: String, account: Account?) =
        Session(id, provider, status = SessionStatus.IDLE, lastActivity = at, account = account)

    private fun usage(provider: String, account: Account?) = Usage(provider, at, account = account)

    @Test
    fun accountShortDropsDomainAndTruncates() {
        assertEquals("me", accountShort(me))
        assertEquals("~school", accountShort(school))
        assertEquals("averyver", accountShort(Account("x", "averyveryverylongname@example.com")))
        assertEquals("~" + "a".repeat(8), accountShort(Account("x", "a".repeat(20), estimated = true)))
        assertEquals("~sisolab", accountShort(Account("x", "sisolab.sswu@gmail.com", estimated = true)))
    }

    @Test
    fun labelsHiddenWithoutAccountsOrWithOnePerProvider() {
        assertFalse(showAccountLabels(emptyList(), emptyList()))
        assertFalse(showAccountLabels(listOf(session("claude-code:1", ProviderId.CLAUDE_CODE, null)), emptyList()))
        // One account per provider, even across two providers, is not worth a label.
        assertFalse(
            showAccountLabels(
                listOf(session("claude-code:1", ProviderId.CLAUDE_CODE, me), session("codex:2", ProviderId.CODEX, codex)),
                listOf(usage(ProviderId.CLAUDE_CODE, me), usage(ProviderId.CODEX, codex)),
            ),
        )
        // The same id seen many times is still one account.
        assertFalse(
            showAccountLabels(
                listOf(session("claude-code:1", ProviderId.CLAUDE_CODE, me), session("claude-code:2", ProviderId.CLAUDE_CODE, me)),
                listOf(usage(ProviderId.CLAUDE_CODE, me.copy(label = "other", estimated = true))),
            ),
        )
    }

    @Test
    fun labelsShownWhenOneProviderHasTwoAccountIds() {
        assertTrue(
            showAccountLabels(
                listOf(session("claude-code:1", ProviderId.CLAUDE_CODE, me), session("claude-code:2", ProviderId.CLAUDE_CODE, school)),
                emptyList(),
            ),
        )
        // Sessions and usage are pooled: one id from each side counts.
        assertTrue(
            showAccountLabels(
                listOf(session("claude-code:1", ProviderId.CLAUDE_CODE, me)),
                listOf(usage(ProviderId.CLAUDE_CODE, school)),
            ),
        )
        assertTrue(showAccountLabels(emptyList(), listOf(usage(ProviderId.CODEX, codex), usage(ProviderId.CODEX, Account("chatgpt-2", "x")))))
    }
}
