package dev.wristline.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodeTest {
    @Test
    fun threePickersMakeSixDigits() {
        assertEquals("012300", pairingCode(listOf(1, 23, 0)))
        assertEquals("999999", pairingCode(listOf(99, 99, 99)))
        assertEquals("000000", pairingCode(listOf(0, 0, 0)))
    }

    @Test
    fun onePickerShowsTwoDigits() {
        assertEquals("07", pairingCode(listOf(7)))
        assertEquals("42", pairingCode(listOf(42)))
    }
}
