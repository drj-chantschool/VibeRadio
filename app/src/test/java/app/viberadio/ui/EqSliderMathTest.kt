package app.viberadio.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EqSliderMathTest {

    private val range = -12f..12f

    @Test
    fun `fraction runs bottom to top and stays on the slider`() {
        assertEquals(0f, EqSliderMath.fraction(-12f, range), 0f)
        assertEquals(0.5f, EqSliderMath.fraction(0f, range), 0f)
        assertEquals(1f, EqSliderMath.fraction(12f, range), 0f)
        assertEquals(1f, EqSliderMath.fraction(20f, range), 0f)
    }

    @Test
    fun `dragging up boosts and down cuts, in proportion to the travel`() {
        // 240 px for 24 dB: 10 px per dB.
        assertEquals(3f, EqSliderMath.dragged(0f, -30f, 240f, range), 0f)
        assertEquals(-4.5f, EqSliderMath.dragged(0f, 45f, 240f, range), 0f)
        assertEquals(1f, EqSliderMath.dragged(2f, 10f, 240f, range), 0f)
    }

    @Test
    fun `drags snap to tenths and stop at the ends`() {
        assertEquals(0.3f, EqSliderMath.dragged(0f, -3.2f, 240f, range), 0f)
        assertEquals(12f, EqSliderMath.dragged(10f, -500f, 240f, range), 0f)
        assertEquals(-12f, EqSliderMath.dragged(-10f, 500f, 240f, range), 0f)
    }

    @Test
    fun `back to the middle is flat, not minus zero`() {
        val db = EqSliderMath.dragged(0.3f, 3f, 240f, range)
        assertEquals(0f, db, 0f)
        assertEquals(0, db.compareTo(0f))
        assertEquals(0, EqSliderMath.snap(-0.04f, range).compareTo(0f))
    }

    @Test
    fun `no travel leaves the value where it was`() {
        assertEquals(2.5f, EqSliderMath.dragged(2.5f, 100f, 0f, range), 0f)
    }
}
