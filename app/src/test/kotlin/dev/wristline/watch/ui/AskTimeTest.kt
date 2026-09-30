package dev.wristline.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AskTimeTest {
    @Test
    fun secondsWithOneDecimalRounded() {
        assertEquals("1.4", askSeconds(1_389))
        assertEquals("2.9", askSeconds(2_926))
        assertEquals("1.5", askSeconds(1_500))
        assertEquals("1.0", askSeconds(950))
        assertEquals("0.0", askSeconds(0))
        assertEquals("90.0", askSeconds(90_000))
    }
}
