package dev.wristline.watch.data

import android.os.VibrationEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    private val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    private val tick = VibrationEffect.Composition.PRIMITIVE_TICK
    private val rise = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    private val fall = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL

    private fun ids(haptic: Haptic) = hapticPattern(haptic).primitives.map { it.id }

    @Test
    fun eachMeaningHasItsOwnShape() {
        assertEquals(listOf(click, click), ids(Haptic.ATTENTION))
        assertEquals(rise, ids(Haptic.DONE).first())
        assertEquals(listOf(tick, tick, tick), ids(Haptic.ERROR))
        assertEquals(listOf(click), ids(Haptic.CONFIRM))
        assertEquals(listOf(fall), ids(Haptic.REJECT))
        assertEquals(listOf(tick), ids(Haptic.SEGMENT))
        // Felt alone, no two meanings are the same.
        val patterns = Haptic.entries.map { hapticPattern(it).primitives }
        assertEquals(patterns.size, patterns.toSet().size)
    }

    @Test
    fun theSegmentTickIsLighterThanTheConfirmingClick() {
        assertTrue(hapticPattern(Haptic.SEGMENT).primitives.single().scale < hapticPattern(Haptic.CONFIRM).primitives.single().scale)
    }

    @Test
    fun primitivesAreValid() {
        for (haptic in Haptic.entries) {
            val primitives = hapticPattern(haptic).primitives
            assertTrue(haptic.name, primitives.isNotEmpty())
            for (p in primitives) {
                assertTrue(haptic.name, p.scale > 0f && p.scale <= 1f)
                assertTrue(haptic.name, p.delayMs >= 0)
            }
            assertEquals(haptic.name, 0, primitives.first().delayMs)
        }
    }

    @Test
    fun fallbacks() {
        assertEquals(HapticFallback.Predefined(VibrationEffect.EFFECT_DOUBLE_CLICK), hapticPattern(Haptic.ATTENTION).fallback)
        assertEquals(HapticFallback.Predefined(VibrationEffect.EFFECT_CLICK), hapticPattern(Haptic.CONFIRM).fallback)
        assertEquals(HapticFallback.Predefined(VibrationEffect.EFFECT_TICK), hapticPattern(Haptic.SEGMENT).fallback)
        // Three short pulses at once: off for 0 ms, then on and off, ending on.
        val error = hapticPattern(Haptic.ERROR).fallback as HapticFallback.Waveform
        assertEquals(0L, error.timings.first())
        assertEquals(0, error.timings.size % 2)
        val on = error.timings.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(3, on.size)
        assertTrue(on.all { it in 1..50 })
    }

    @Test
    fun composedOnlyWhenEveryPrimitiveIsSupported() {
        val all = HAPTIC_PRIMITIVES.toSet()
        assertEquals(setOf(click, tick, rise, fall), all)
        for (haptic in Haptic.entries) {
            assertTrue(hapticPattern(haptic).playsComposed(all))
            assertFalse(hapticPattern(haptic).playsComposed(emptySet()))
        }
        // A vibrator without the rise still clicks for a request, but a finished task falls back.
        val noRise = all - rise
        assertTrue(hapticPattern(Haptic.ATTENTION).playsComposed(noRise))
        assertFalse(hapticPattern(Haptic.DONE).playsComposed(noRise))
    }

    @Test
    fun sentPromptConfirmsOrErrs() {
        assertEquals(Haptic.CONFIRM, sentHaptic(Sent.Ok))
        assertEquals(Haptic.ERROR, sentHaptic(Sent.Unreachable))
        assertEquals(Haptic.ERROR, sentHaptic(Sent.Refused("busy")))
    }

    private fun event(status: String, error: String? = null) =
        ServerEvent.AskChanged("ask-1", ProviderId.CLAUDE_CODE, status, text = "42".takeIf { status == AskStatus.DONE }, error = error)

    @Test
    fun anAnswerRisesAndAFailureErrsOnceForARunningAsk() {
        assertEquals(Haptic.DONE, askHaptic(AskStatus.RUNNING, event(AskStatus.DONE)))
        assertEquals(Haptic.ERROR, askHaptic(AskStatus.RUNNING, event(AskStatus.ERROR, "timeout")))
        // The user's own cancel (also on leaving the screen) stays quiet.
        assertNull(askHaptic(AskStatus.RUNNING, event(AskStatus.ERROR, "cancelled")))
        assertNull(askHaptic(AskStatus.RUNNING, event(AskStatus.RUNNING)))
        // A repeated event, or one for an ask the watch did not know (after a reconnect).
        assertNull(askHaptic(AskStatus.DONE, event(AskStatus.DONE)))
        assertNull(askHaptic(AskStatus.ERROR, event(AskStatus.ERROR, "timeout")))
        assertNull(askHaptic(null, event(AskStatus.DONE)))
    }
}
