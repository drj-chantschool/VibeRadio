package app.viberadio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnobMathTest {

    private val eps = 0.01f

    @Test
    fun `detents span the sweep evenly`() {
        assertEquals(KnobMath.START, KnobMath.detentAngle(0, 4), eps)
        assertEquals(KnobMath.END, KnobMath.detentAngle(3, 4), eps)
        assertEquals(-45f, KnobMath.detentAngle(1, 4), eps)
    }

    @Test
    fun `a single channel sits at the top`() {
        assertEquals(0f, KnobMath.detentAngle(0, 1), eps)
        assertEquals(0, KnobMath.nearestDetent(100f, 1))
    }

    @Test
    fun `nearest detent rounds to the closest position`() {
        assertEquals(1, KnobMath.nearestDetent(-50f, 4))
        assertEquals(2, KnobMath.nearestDetent(40f, 4))
    }

    @Test
    fun `dead zone snaps to the ends`() {
        assertEquals(0, KnobMath.nearestDetent(-170f, 5))
        assertEquals(4, KnobMath.nearestDetent(170f, 5))
    }

    @Test
    fun `pointer angle is clockwise from twelve o'clock`() {
        assertEquals(0f, KnobMath.pointerAngle(50f, 0f, 50f, 50f), eps)
        assertEquals(90f, KnobMath.pointerAngle(100f, 50f, 50f, 50f), eps)
        assertEquals(-90f, KnobMath.pointerAngle(0f, 50f, 50f, 50f), eps)
    }

    @Test
    fun `pointer angle below the dial is clamped to the sweep`() {
        assertEquals(KnobMath.END, KnobMath.pointerAngle(51f, 100f, 50f, 50f), eps)
        assertEquals(KnobMath.START, KnobMath.pointerAngle(49f, 100f, 50f, 50f), eps)
    }

    @Test
    fun `centre hit test is a circle`() {
        assertTrue(KnobMath.isWithin(100f, 100f, 100f, 100f, 10f))
        assertTrue(KnobMath.isWithin(106f, 108f, 100f, 100f, 10f))
        assertFalse(KnobMath.isWithin(108f, 108f, 100f, 100f, 10f))
    }
}
