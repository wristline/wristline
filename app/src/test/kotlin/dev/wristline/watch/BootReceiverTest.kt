package dev.wristline.watch

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun startsAfterBootAndUpdateWhenPairedAndMonitoring() {
        assertTrue(shouldStartAtBoot(paired = true, monitoring = true, action = Intent.ACTION_BOOT_COMPLETED))
        assertTrue(shouldStartAtBoot(paired = true, monitoring = true, action = Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun staysOffUnlessPairedAndMonitoring() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            assertFalse(shouldStartAtBoot(paired = false, monitoring = true, action = action))
            assertFalse(shouldStartAtBoot(paired = true, monitoring = false, action = action))
            assertFalse(shouldStartAtBoot(paired = false, monitoring = false, action = action))
        }
    }

    @Test
    fun ignoresOtherActions() {
        assertFalse(shouldStartAtBoot(paired = true, monitoring = true, action = Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertFalse(shouldStartAtBoot(paired = true, monitoring = true, action = "android.intent.action.PACKAGE_REPLACED"))
        assertFalse(shouldStartAtBoot(paired = true, monitoring = true, action = null))
    }
}
