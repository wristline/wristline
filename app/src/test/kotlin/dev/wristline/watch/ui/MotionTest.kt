package dev.wristline.watch.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.wear.compose.material3.MotionScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    private fun damping(spec: FiniteAnimationSpec<Float>): Float = (spec as SpringSpec<*>).dampingRatio

    private fun MotionScheme.all(): List<FiniteAnimationSpec<Float>> = listOf(
        defaultSpatialSpec(), fastSpatialSpec(), slowSpatialSpec(),
        defaultEffectsSpec(), fastEffectsSpec(), slowEffectsSpec(),
    )

    @Test
    fun touchSpringsOvershootALittle() {
        val motion = wristlineMotion(reduceMotion = false)
        // Under 1 a spring overshoots; well above 0.5 it settles after a single small swing.
        for (spec in listOf(motion.defaultSpatialSpec<Float>(), motion.fastSpatialSpec(), motion.slowSpatialSpec())) {
            assertTrue(damping(spec) < 1f)
            assertTrue(damping(spec) >= 0.7f)
        }
    }

    @Test
    fun reducedMotionAndDataMotionNeverOvershoot() {
        assertSame(CalmMotion, wristlineMotion(reduceMotion = true))
        for (spec in CalmMotion.all()) assertTrue(damping(spec) >= 1f)
    }

    @Test
    fun themeMotionIsTheSameObjectEveryTime() {
        // A new scheme on every recomposition would recompose everything reading the theme.
        assertSame(wristlineMotion(reduceMotion = false), wristlineMotion(reduceMotion = false))
    }

    @Test
    fun pressGoesDownAndComesBack() {
        val press = PressInteraction.Press(Offset.Zero)
        assertEquals(1f, pressTarget(press, enabled = true))
        assertEquals(0f, pressTarget(PressInteraction.Release(press), enabled = true))
        assertEquals(0f, pressTarget(PressInteraction.Cancel(press), enabled = true))
    }

    @Test
    fun disabledIgnoresPressesButStillLetsGo() {
        val press = PressInteraction.Press(Offset.Zero)
        assertNull(pressTarget(press, enabled = false))
        // A card expanded by the tap that pressed it still springs back.
        assertEquals(0f, pressTarget(PressInteraction.Release(press), enabled = false))
        assertEquals(0f, pressTarget(PressInteraction.Cancel(press), enabled = false))
    }

    @Test
    fun otherInteractionsLeaveThePressAlone() {
        assertNull(pressTarget(FocusInteraction.Focus(), enabled = true))
    }

    @Test
    fun pressedScaleRunsFromOneToPressed() {
        assertEquals(1f, pressedScale(0f), 0f)
        assertEquals(PRESSED_SCALE, pressedScale(1f), 1e-6f)
        assertEquals(0.96f, PRESSED_SCALE, 0f)
        // Springing back past 0 the card swells a hair past its size.
        assertTrue(pressedScale(-0.05f) > 1f)
    }
}
