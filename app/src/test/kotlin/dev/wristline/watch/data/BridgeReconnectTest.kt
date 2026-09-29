package dev.wristline.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeReconnectTest {
    @Test
    fun scheduleDoublesFromOneSecondAndCapsAtThirty() {
        val schedule = (0..7).map { reconnectDelayMs(it, jitter = 0.0) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), schedule)
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        assertEquals(800L, reconnectDelayMs(0, jitter = -1.0))
        assertEquals(1_200L, reconnectDelayMs(0, jitter = 1.0))
        assertEquals(24_000L, reconnectDelayMs(9, jitter = -1.0))
        assertEquals(36_000L, reconnectDelayMs(9, jitter = 1.0))
        for (attempt in 0..20) {
            for (jitter in listOf(-1.0, -0.5, 0.0, 0.5, 1.0)) {
                val base = reconnectDelayMs(attempt, 0.0)
                val delay = reconnectDelayMs(attempt, jitter)
                assertTrue("attempt $attempt jitter $jitter", delay in (base * 0.8).toLong()..(base * 1.2).toLong())
            }
        }
    }

    @Test
    fun outOfRangeInputsAreClamped() {
        assertEquals(1_000L, reconnectDelayMs(-3, jitter = 0.0))
        assertEquals(30_000L, reconnectDelayMs(Int.MAX_VALUE, jitter = 0.0))
        assertEquals(1_200L, reconnectDelayMs(0, jitter = 5.0))
        assertEquals(800L, reconnectDelayMs(0, jitter = -5.0))
    }
}
