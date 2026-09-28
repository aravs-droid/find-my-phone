package net.media.wakeword

/** Process-wide snapshot the service writes (audio thread) and the UI polls. */
object WakeState {
    @Volatile var listening = false
    @Volatile var ringing = false
    @Volatile var score = 0f
    @Volatile var peak = 0f
    @Volatile var ticks = 0L
    @Volatile var fires = 0
    @Volatile var lastFireAtMs = 0L
    @Volatile var progress = ""
    @Volatile var error: String? = null
    @Volatile var session = SessionConfig()

    /** Live trigger settings. The UI replaces the instance; the engine swaps rules on change. */
    @Volatile var trigger = TriggerConfig()

    /** Last ~12s of ticks for the graph. Written by the audio thread only. */
    const val HISTORY = 400
    val rawHistory = FloatArray(HISTORY)
    val levelHistory = FloatArray(HISTORY)
    val fireHistory = BooleanArray(HISTORY)

    /** Recent detections, newest first: "12:03:41  peak 0.998". */
    val fireLog = ArrayDeque<String>()

    fun record(raw: Float, level: Float, fired: Boolean) {
        val i = (ticks % HISTORY).toInt()
        rawHistory[i] = raw
        levelHistory[i] = level
        fireHistory[i] = fired
        ticks++
        score = raw
        if (raw > peak) peak = raw
    }

    fun resetSession() {
        score = 0f; peak = 0f; ticks = 0L; fires = 0; lastFireAtMs = 0L; progress = ""; error = null
        rawHistory.fill(0f); levelHistory.fill(0f); fireHistory.fill(false)
        synchronized(fireLog) { fireLog.clear() }
    }
}
