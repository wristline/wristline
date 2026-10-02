package dev.wristline.watch.ui

import dev.wristline.watch.data.PROGRESS_AGENTS
import dev.wristline.watch.data.ProviderId
import dev.wristline.watch.data.Session
import dev.wristline.watch.data.SessionStatus
import dev.wristline.watch.data.TaskProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardProgressTest {
    private fun session(status: String, progress: TaskProgress?) =
        Session("claude-code:1", ProviderId.CLAUDE_CODE, status = status, lastActivity = "2026-10-02T12:00:00Z", progress = progress)

    @Test
    fun runningSessionShowsItsTaskList() {
        val progress = cardProgress(session(SessionStatus.RUNNING, TaskProgress(3, 7)))
        assertEquals(TaskProgress(3, 7), progress)
        assertEquals("3/7", cardProgressText(progress!!))
    }

    @Test
    fun noneWhenNotRunningOrWithoutAList() {
        assertNull(cardProgress(session(SessionStatus.RUNNING, null)))
        assertNull(cardProgress(session(SessionStatus.RUNNING, TaskProgress(0, 0))))
        assertNull(cardProgress(session(SessionStatus.IDLE, TaskProgress(7, 7))))
        assertNull(cardProgress(session(SessionStatus.NEEDS_INPUT, TaskProgress(3, 7))))
    }

    @Test
    fun doneIsClampedToTheTotal() {
        assertEquals("7/7", cardProgressText(cardProgress(session(SessionStatus.RUNNING, TaskProgress(9, 7)))!!))
        assertEquals("0/7", cardProgressText(cardProgress(session(SessionStatus.RUNNING, TaskProgress(-1, 7)))!!))
    }

    @Test
    fun detailLineIsTheCountThenTheTaskInProgress() {
        assertEquals("3/7 · Run the tests", detailProgressText(TaskProgress(3, 7, current = "Run the tests")))
        assertEquals("3/7", detailProgressText(TaskProgress(3, 7)))
        assertEquals("3/7", detailProgressText(TaskProgress(3, 7, current = " ")))
        // Clamped as on the card, the task kept.
        assertEquals("7/7 · Ship", detailProgressText(cardProgress(session(SessionStatus.RUNNING, TaskProgress(9, 7, current = "Ship")))!!))
    }

    @Test
    fun subAgentCountIsLabelledAgents() {
        val agents = TaskProgress(1, 2, kind = PROGRESS_AGENTS)
        assertEquals("1/2 agents · Review the diff", detailProgressText(agents.copy(current = "Review the diff")))
        assertEquals("1/2 agents", detailProgressText(agents))
        // The card keeps the count alone.
        assertEquals("1/2", cardProgressText(cardProgress(session(SessionStatus.RUNNING, agents))!!))
    }
}
