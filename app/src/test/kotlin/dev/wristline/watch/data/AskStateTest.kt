package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskStateTest {
    private val at = "2026-09-29T00:00:00Z"

    private fun ask(id: String, status: String = AskStatus.RUNNING) = Ask(id, ProviderId.CLAUDE_CODE, "q$id", status, createdAt = at)

    private fun ids(list: List<Ask>) = list.map { it.id }

    @Test
    fun newAsksGoFirstAndSameIdReplaces() {
        var list = emptyList<Ask>().withAsk(ask("a"))
        list = list.withAsk(ask("b"))
        assertEquals(listOf("b", "a"), ids(list))
        list = list.withAsk(ask("a", AskStatus.DONE))
        assertEquals(listOf("b", "a"), ids(list))
        assertEquals(AskStatus.DONE, list[1].status)
    }

    @Test
    fun keepsAtMostTwentyDroppingTheOldest() {
        var list = emptyList<Ask>()
        repeat(ASK_KEEP + 1) { list = list.withAsk(ask("$it")) }
        assertEquals(ASK_KEEP, list.size)
        assertEquals("${ASK_KEEP}", list.first().id)
        assertEquals("1", list.last().id)
    }

    @Test
    fun doneEventFillsAnswerModelAndDuration() {
        val list = listOf(ask("a")).withAskEvent(
            ServerEvent.AskChanged("a", ProviderId.CLAUDE_CODE, AskStatus.DONE, text = "OK", model = "Haiku 4.5", durationMs = 1389),
        )
        val done = list.single()
        assertEquals(AskStatus.DONE, done.status)
        assertEquals("OK", done.answer)
        assertEquals("Haiku 4.5", done.model)
        assertEquals(1389L, done.durationMs)
        assertEquals("qa", done.question)
        assertNull(done.error)
    }

    @Test
    fun errorEventKeepsTheQuestionAndSetsTheError() {
        val list = listOf(ask("a")).withAskEvent(
            ServerEvent.AskChanged("a", ProviderId.CLAUDE_CODE, AskStatus.ERROR, durationMs = 0, error = "cancelled"),
        )
        assertEquals(AskStatus.ERROR, list.single().status)
        assertEquals("cancelled", list.single().error)
        assertEquals("qa", list.single().question)
    }

    @Test
    fun unknownAskIdBecomesAPlaceholder() {
        val list = listOf(ask("a")).withAskEvent(ServerEvent.AskChanged("z", ProviderId.CODEX, AskStatus.RUNNING))
        assertEquals(listOf("z", "a"), ids(list))
        val placeholder = list.first()
        assertEquals("", placeholder.question)
        assertEquals(ProviderId.CODEX, placeholder.provider)
        assertEquals(AskStatus.RUNNING, placeholder.status)
    }
}
