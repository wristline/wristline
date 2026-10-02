package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProvidersTest {
    private fun health(vararg providers: Pair<String, String>) =
        Health("devbox", "0.1.0", API_VERSION, providers.map { (id, status) -> ProviderHealth(id, status) })

    @Test
    fun onlyInstalledProvidersAreAvailable() {
        // A provider the bridge lists twice (two homes) counts once.
        val both = health(ProviderId.CLAUDE_CODE to "ok", ProviderId.CODEX to "ok", ProviderId.CLAUDE_CODE to "ok")
        assertEquals(setOf(ProviderId.CLAUDE_CODE, ProviderId.CODEX), availableProviders(both))
        assertEquals(setOf(ProviderId.CODEX), availableProviders(health(ProviderId.CLAUDE_CODE to "not_found", ProviderId.CODEX to "ok")))
    }

    @Test
    fun quickAskOffersTheInstalledOnesElseBoth() {
        val both = listOf(ProviderId.CLAUDE_CODE, ProviderId.CODEX)
        assertEquals(both, askProviders(null))
        assertEquals(both, askProviders(setOf(ProviderId.CODEX, ProviderId.CLAUDE_CODE)))
        // Codex only: Claude is not offered.
        assertEquals(listOf(ProviderId.CODEX), askProviders(setOf(ProviderId.CODEX)))
        // Neither installed, or only one Quick Ask cannot use: both, as before the bridge said.
        assertEquals(both, askProviders(emptySet()))
        assertEquals(both, askProviders(setOf("gemini")))
    }
}
