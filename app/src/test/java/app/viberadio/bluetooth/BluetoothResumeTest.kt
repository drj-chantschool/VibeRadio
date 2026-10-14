package app.viberadio.bluetooth

import app.viberadio.bluetooth.BluetoothResume.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothResumeTest {

    @Test
    fun `off does nothing`() {
        assertEquals(Action.None, BluetoothResume.decide(enabled = false, playing = false, resumable = true, lastChannelId = "breeze"))
    }

    @Test
    fun `already playing does nothing`() {
        assertEquals(Action.None, BluetoothResume.decide(enabled = true, playing = true, resumable = true, lastChannelId = "breeze"))
    }

    @Test
    fun `paused queue resumes in place`() {
        assertEquals(Action.Play, BluetoothResume.decide(enabled = true, playing = false, resumable = true, lastChannelId = "breeze"))
    }

    @Test
    fun `nothing loaded tunes the last channel`() {
        assertEquals(
            Action.Tune("podcasts"),
            BluetoothResume.decide(enabled = true, playing = false, resumable = false, lastChannelId = "podcasts"),
        )
    }

    @Test
    fun `never played means nothing to resume`() {
        assertEquals(Action.None, BluetoothResume.decide(enabled = true, playing = false, resumable = false, lastChannelId = null))
    }

    @Test
    fun `same address matches regardless of case and whitespace`() {
        assertTrue(BluetoothResume.sameDevice("aa:bb:cc:dd:ee:ff", " AA:BB:CC:DD:EE:FF "))
    }

    @Test
    fun `masked bytes match anything but the tail must agree`() {
        assertTrue(BluetoothResume.sameDevice("XX:XX:XX:XX:EE:FF", "AA:BB:CC:DD:EE:FF"))
        assertFalse(BluetoothResume.sameDevice("XX:XX:XX:XX:EE:F0", "AA:BB:CC:DD:EE:FF"))
        assertFalse(BluetoothResume.sameDevice("AA:BB:CC:DD:XX:XX", "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `different or missing addresses never match`() {
        assertFalse(BluetoothResume.sameDevice("11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF"))
        assertFalse(BluetoothResume.sameDevice(null, "AA:BB:CC:DD:EE:FF"))
        assertFalse(BluetoothResume.sameDevice("AA:BB:CC:DD:EE:FF", null))
        assertFalse(BluetoothResume.sameDevice("", ""))
    }
}
