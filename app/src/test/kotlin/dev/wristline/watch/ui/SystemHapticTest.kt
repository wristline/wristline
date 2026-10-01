package dev.wristline.watch.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import dev.wristline.watch.data.Haptic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemHapticTest {
    @Test
    fun onScreenActionsUseTheSystemsHaptics() {
        assertEquals(HapticFeedbackType.Confirm, systemHaptic(Haptic.CONFIRM))
        assertEquals(HapticFeedbackType.Reject, systemHaptic(Haptic.REJECT))
        assertEquals(HapticFeedbackType.SegmentTick, systemHaptic(Haptic.SEGMENT))
    }

    @Test
    fun aFailureKeepsItsOwnPattern() {
        // Reject is the deny: a failure must not feel the same.
        assertNull(systemHaptic(Haptic.ERROR))
    }

    @Test
    fun eventsAreNotTouchHaptics() {
        // A request arriving or an answer coming back plays through Haptics.event, never here.
        assertNull(systemHaptic(Haptic.ATTENTION))
        assertNull(systemHaptic(Haptic.DONE))
    }
}
