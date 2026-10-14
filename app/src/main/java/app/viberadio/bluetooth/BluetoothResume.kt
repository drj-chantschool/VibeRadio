package app.viberadio.bluetooth

/**
 * What to do when a Bluetooth audio device connects. Pure; [BluetoothConnectReceiver] does the
 * Android side. Resume means resume: if nothing has ever played there is nothing to resume.
 */
object BluetoothResume {

    sealed interface Action {
        data object None : Action

        /** Carry on with what's loaded in the player (a paused song or episode). */
        data object Play : Action

        /** Nothing loaded (e.g. the app was closed): tune the channel that played last. */
        data class Tune(val channelId: String) : Action
    }

    /**
     * @param playing the player already means to play, so a connection changes nothing.
     * @param resumable the player still holds a queue that hasn't ended.
     * @param lastChannelId the channel that played last, across restarts.
     */
    fun decide(enabled: Boolean, playing: Boolean, resumable: Boolean, lastChannelId: String?): Action = when {
        !enabled || playing -> Action.None
        resumable -> Action.Play
        lastChannelId != null -> Action.Tune(lastChannelId)
        else -> Action.None
    }

    /**
     * Whether an audio output's address belongs to the Bluetooth device that connected. Android
     * can mask an address down to its last two bytes ("XX:XX:XX:XX:AB:CD"), so masked bytes match
     * anything; the last two bytes must always be present and equal.
     */
    fun sameDevice(outputAddress: String?, deviceAddress: String?): Boolean {
        val a = outputAddress?.trim()?.uppercase().orEmpty()
        val b = deviceAddress?.trim()?.uppercase().orEmpty()
        if (a.length != ADDRESS_LENGTH || b.length != ADDRESS_LENGTH) return false
        if (a.takeLast(VISIBLE_TAIL).contains(MASK) || b.takeLast(VISIBLE_TAIL).contains(MASK)) return false
        return a.indices.all { a[it] == b[it] || a[it] == MASK || b[it] == MASK }
    }

    /** "AA:BB:CC:DD:EE:FF". */
    private const val ADDRESS_LENGTH = 17

    /** The last two bytes, "EE:FF", which Android never masks. */
    private const val VISIBLE_TAIL = 5
    private const val MASK = 'X'
}
