package dev.wristline.watch.ui

import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.Haptic
import dev.wristline.watch.data.Option
import dev.wristline.watch.data.PERMISSION_QUESTION
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Question
import dev.wristline.watch.data.RequestKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnsweredTest {
    private val decisions = listOf(Decision.ALLOW, Decision.ALWAYS, Decision.DENY, Decision.DEFER).map { Option(it, it) }
    private val permission = PendingRequest(
        "r1", "claude-code:1", RequestKind.PERMISSION, "Bash",
        listOf(Question(PERMISSION_QUESTION, text = "npm test", options = decisions)),
        createdAt = "2026-09-29T12:58:00Z",
    )
    private val question = PendingRequest(
        "r2", "claude-code:1", RequestKind.QUESTION, "Which?",
        listOf(Question("q1", text = "Pick one", options = listOf(Option(Decision.ALLOW, "allow"), Option("b", "B")))),
        createdAt = "2026-09-29T12:58:00Z",
    )

    private fun permissionAnswer(id: String) = answeredAs(permission, mapOf(PERMISSION_QUESTION to listOf(id)))

    @Test
    fun permissionDecisions() {
        assertEquals(Answered.ALLOWED, permissionAnswer(Decision.ALLOW))
        assertEquals(Answered.ALLOWED, permissionAnswer(Decision.ALWAYS))
        assertEquals(Answered.DENIED, permissionAnswer(Decision.DENY))
        // Left to the PC: nothing to confirm.
        assertEquals(Answered.OTHER, permissionAnswer(Decision.DEFER))
        assertEquals(Answered.OTHER, answeredAs(permission, emptyMap()))
    }

    @Test
    fun aPermissionWithoutTheDecisionIdUsesItsOnlyQuestion() {
        val other = permission.copy(questions = listOf(permission.questions[0].copy(id = "x")))
        assertEquals(Answered.DENIED, answeredAs(other, mapOf("x" to listOf(Decision.DENY))))
        assertEquals(Answered.OTHER, answeredAs(permission.copy(questions = emptyList()), emptyMap()))
    }

    @Test
    fun questionsAreJustSent() {
        // Even an option that happens to be called "allow".
        assertEquals(Answered.OTHER, answeredAs(question, mapOf("q1" to listOf(Decision.ALLOW))))
    }

    @Test
    fun oneHapticPerAnswer() {
        // The success dialog plays its own confirming haptic; another here would make two.
        assertNull(answeredHaptic(Answered.ALLOWED))
        assertEquals(Haptic.REJECT, answeredHaptic(Answered.DENIED))
        assertEquals(Haptic.CONFIRM, answeredHaptic(Answered.OTHER))
    }
}
