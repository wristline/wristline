package dev.wristline.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AskModelLabelTest {
    @Test
    fun claudeModelIdReadsLikeTheOtherNames() {
        assertEquals("Haiku 5.5", askModelLabel("haiku-5-5"))
        assertEquals("Sonnet 4.5", askModelLabel("sonnet-4-5"))
    }

    @Test
    fun otherNamesAreUnchanged() {
        assertEquals("Haiku 4.5", askModelLabel("Haiku 4.5"))
        assertEquals("Fable 5.1", askModelLabel("Fable 5.1"))
        assertEquals("gpt-5.3-codex", askModelLabel("gpt-5.3-codex"))
        assertEquals("haiku", askModelLabel("haiku"))
        assertEquals("claude-haiku-5-5", askModelLabel("claude-haiku-5-5"))
        assertEquals("haiku-5-5-20261001", askModelLabel("haiku-5-5-20261001"))
    }
}
