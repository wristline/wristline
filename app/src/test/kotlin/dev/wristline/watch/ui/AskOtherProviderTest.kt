package dev.wristline.watch.ui

import dev.wristline.watch.data.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskOtherProviderTest {
    @Test
    fun theOtherProviderIsOfferedOnlyWhenInstalled() {
        val both = setOf(ProviderId.CLAUDE_CODE, ProviderId.CODEX)
        assertEquals(ProviderId.CODEX, otherAskProvider(ProviderId.CLAUDE_CODE, both))
        assertEquals(ProviderId.CLAUDE_CODE, otherAskProvider(ProviderId.CODEX, both))
        // Only the answering provider is installed: nothing to offer, so no button.
        assertNull(otherAskProvider(ProviderId.CLAUDE_CODE, setOf(ProviderId.CLAUDE_CODE)))
        assertNull(otherAskProvider(ProviderId.CODEX, setOf(ProviderId.CODEX)))
        // Only the other one is installed (the answer came before it was removed): still offered.
        assertEquals(ProviderId.CODEX, otherAskProvider(ProviderId.CLAUDE_CODE, setOf(ProviderId.CODEX)))
        // Not known yet, or neither installed: both, as Settings and the confirm dialog offer.
        assertEquals(ProviderId.CODEX, otherAskProvider(ProviderId.CLAUDE_CODE, null))
        assertEquals(ProviderId.CLAUDE_CODE, otherAskProvider(ProviderId.CODEX, emptySet()))
    }
}
