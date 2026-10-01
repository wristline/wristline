package dev.wristline.watch.ui

import dev.wristline.watch.R
import dev.wristline.watch.data.Decision
import dev.wristline.watch.data.Option
import dev.wristline.watch.data.PERMISSION_QUESTION
import dev.wristline.watch.data.PendingRequest
import dev.wristline.watch.data.Question
import dev.wristline.watch.data.RequestKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanApprovalTest {
    // As the bridge sends them, for either tool.
    private val decisions = listOf(Decision.ALLOW, Decision.ALWAYS, Decision.DENY, Decision.DEFER).map { Option(it, it) }

    private fun permission(tool: String, kind: String = RequestKind.PERMISSION) = PendingRequest(
        "r1", "claude-code:1", kind, tool,
        listOf(Question(PERMISSION_QUESTION, text = "plan", options = decisions)),
        createdAt = "2026-10-01T12:00:00Z",
    )

    private fun labels(request: PendingRequest): List<Int?> {
        val plan = request.isPlanApproval()
        return shownOptions(request, request.questions.single()).map { decisionLabelRes(plan, it.id) }
    }

    @Test
    fun planApprovalUsesTheTerminalsWordsAndOrder() {
        val request = permission("ExitPlanMode")
        assertTrue(request.isPlanApproval())
        assertEquals(
            listOf(Decision.ALWAYS, Decision.ALLOW, Decision.DENY, Decision.DEFER),
            shownOptions(request, request.questions.single()).map { it.id },
        )
        assertEquals(
            listOf(R.string.decision_plan_auto_accept, R.string.decision_plan_approve, R.string.decision_plan_change, R.string.decision_defer),
            labels(request),
        )
    }

    @Test
    fun otherToolsKeepTheirLabelsAndOrder() {
        val request = permission("Bash")
        assertFalse(request.isPlanApproval())
        assertEquals(decisions, shownOptions(request, request.questions.single()))
        assertEquals(
            listOf(R.string.decision_allow, R.string.decision_always, R.string.decision_deny, R.string.decision_defer),
            labels(request),
        )
    }

    @Test
    fun planApprovalButtonsAreAllNeutral() {
        assertEquals(
            listOf(OptionStyle.NEUTRAL, OptionStyle.NEUTRAL, OptionStyle.NEUTRAL, OptionStyle.NEUTRAL),
            decisions.map { optionStyle(plan = true, it.id) },
        )
    }

    @Test
    fun otherToolsKeepTheirColours() {
        assertEquals(
            listOf(OptionStyle.ALLOW, OptionStyle.TONAL, OptionStyle.DENY, OptionStyle.NEUTRAL),
            decisions.map { optionStyle(plan = false, it.id) },
        )
    }

    @Test
    fun onlyAPermissionIsAPlanApproval() {
        assertFalse(permission("ExitPlanMode", RequestKind.QUESTION).isPlanApproval())
    }

    @Test
    fun anUnknownOptionKeepsTheBridgesLabelAndGoesLast() {
        val request = permission("ExitPlanMode").let {
            it.copy(questions = listOf(it.questions.single().copy(options = listOf(Option("later", "Later")) + decisions)))
        }
        assertEquals("later", shownOptions(request, request.questions.single()).last().id)
        assertNull(decisionLabelRes(plan = true, "later"))
    }
}
