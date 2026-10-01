package dev.wristline.watch

import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.Haptic
import dev.wristline.watch.data.Option
import dev.wristline.watch.data.PERMISSION_QUESTION
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Question
import dev.wristline.watch.data.RequestKind
import dev.wristline.watch.data.Sent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionTest {
    private val allOptions = listOf(Decision.ALLOW, Decision.ALWAYS, Decision.DENY, Decision.DEFER)

    private fun permission(text: String, options: List<String> = allOptions, multi: Boolean = false) = PendingRequest(
        id = "req-1",
        sessionId = "s1",
        kind = RequestKind.PERMISSION,
        title = "Bash",
        questions = listOf(Question(PERMISSION_QUESTION, text = text, multi = multi, options = options.map { Option(it, it) })),
        createdAt = "2026-09-30T00:00:00Z",
    )

    @Test
    fun oneLineCommandOffersAllowAndDenyButNeverAlways() {
        assertEquals(listOf(Decision.ALLOW, Decision.DENY), notificationDecisions(permission("npm test && git push")))
    }

    @Test
    fun allowNeedsTheWholeCommandOnOneLine() {
        val deny = listOf(Decision.DENY)
        assertEquals(deny, notificationDecisions(permission("cd app\nrm -rf build")))
        assertEquals(deny, notificationDecisions(permission("echo a\r\necho b")))
        // Longer than the notification shows, or clipped by the bridge already.
        assertEquals(deny, notificationDecisions(permission("x".repeat(NOTIFY_TEXT_MAX + 1))))
        assertEquals(deny, notificationDecisions(permission("rm -rf build && npm run b…")))
        assertEquals(deny, notificationDecisions(permission("  ")))
        // Exactly the limit is still whole.
        assertEquals(listOf(Decision.ALLOW, Decision.DENY), notificationDecisions(permission("y".repeat(NOTIFY_TEXT_MAX))))
    }

    @Test
    fun onlyTheOptionsTheRequestHas() {
        assertEquals(listOf(Decision.DENY), notificationDecisions(permission("ls", options = listOf(Decision.DENY, Decision.DEFER))))
        assertEquals(listOf(Decision.ALLOW), notificationDecisions(permission("ls", options = listOf(Decision.ALLOW))))
        assertEquals(emptyList<String>(), notificationDecisions(permission("ls", options = listOf(Decision.ALWAYS, Decision.DEFER))))
        assertEquals(emptyList<String>(), notificationDecisions(permission("ls", multi = true)))
    }

    @Test
    fun questionsHaveNoActions() {
        val question = permission("Which one?").copy(kind = RequestKind.QUESTION)
        assertEquals(emptyList<String>(), notificationDecisions(question))
        assertEquals(emptyList<String>(), notificationDecisions(permission("ls").copy(questions = emptyList())))
    }

    @Test
    fun clipKeepsShortTextAndMarksCutText() {
        assertEquals("abc", clipText("abc", 3))
        assertEquals("ab…", clipText("abcd", 3))
        assertEquals(NOTIFY_TEXT_MAX, clipText("z".repeat(1500), NOTIFY_TEXT_MAX).length)
        // A surrogate pair at the cut is dropped whole rather than split.
        assertEquals("a…", clipText("a😀bc", 3))
    }

    @Test
    fun outcomeOfAnAnswer() {
        assertEquals(ActionOutcome(Haptic.CONFIRM, reposted = false), actionOutcome(Decision.ALLOW, Sent.Ok))
        assertEquals(ActionOutcome(Haptic.REJECT, reposted = false), actionOutcome(Decision.DENY, Sent.Ok))
        // Answered elsewhere: the notification is gone, nothing to retry.
        assertEquals(ActionOutcome(Haptic.ERROR, reposted = false), actionOutcome(Decision.ALLOW, Sent.Refused("already_resolved")))
        for (sent in listOf(Sent.Unreachable, Sent.Refused("unauthorized"), Sent.Refused("http_500"))) {
            val outcome = actionOutcome(Decision.DENY, sent)
            assertEquals(Haptic.ERROR, outcome.haptic)
            assertTrue(sent.toString(), outcome.reposted)
        }
        assertFalse(actionOutcome(Decision.ALLOW, Sent.Ok).reposted)
    }
}
