package net.media.wakeword

/** WAKE_WORD_INTEGRATION.md section 4. One tick = one model call = 30ms of audio. */
interface DecisionRule {
    /** Returns true on the tick the wake event should fire. */
    fun onTick(score: Float): Boolean

    /** The value this rule compares against its cutoff (for the graph). */
    val level: Float

    /** One-line live progress, e.g. "streak 4/10". */
    fun progress(): String
}

/** Streak: [n] raw ticks in a row >= [cutoff]; re-arms only after dropping below it. */
class StreakDecisionRule(private val cutoff: Float, private val n: Int) : DecisionRule {
    private var consec = 0
    private var armed = true
    override var level = 0f
        private set

    override fun onTick(score: Float): Boolean {
        level = score
        if (score >= cutoff) {
            consec++
        } else {
            consec = 0
            armed = true
        }
        if (consec >= n && armed) {
            armed = false
            return true
        }
        return false
    }

    override fun progress() = "streak ${minOf(consec, n)}/$n" + if (!armed) "  (waiting to re-arm)" else ""
}

/** Moving average over [windowSize] ticks, then [cooldownTicks] of silence after a fire. */
class MovingAverageDecisionRule(
    private val cutoff: Float,
    private val windowSize: Int,
    private val cooldownTicks: Int,
) : DecisionRule {
    private val window = ArrayDeque<Float>()
    private var cooldown = 0
    override var level = 0f
        private set

    override fun onTick(score: Float): Boolean {
        window.addLast(score)
        if (window.size > windowSize) window.removeFirst()
        val avg = window.average().toFloat()
        level = avg

        if (cooldown > 0) {
            cooldown--
            return false
        }
        if (avg >= cutoff) {
            cooldown = cooldownTicks
            return true
        }
        return false
    }

    override fun progress() =
        "avg(${window.size}) = ${"%.3f".format(level)}" + if (cooldown > 0) "  cooldown $cooldown" else ""
}

/**
 * At least [k] of the last [m] raw ticks >= [cutoff]. Like streak, but tolerates a few
 * dips mid-phrase; then [cooldownTicks] of silence after a fire.
 */
class KOfMDecisionRule(
    private val cutoff: Float,
    private val k: Int,
    private val m: Int,
    private val cooldownTicks: Int,
) : DecisionRule {
    private val hits = ArrayDeque<Boolean>()
    private var count = 0
    private var cooldown = 0
    override var level = 0f
        private set

    override fun onTick(score: Float): Boolean {
        val hit = score >= cutoff
        hits.addLast(hit)
        if (hit) count++
        if (hits.size > m && hits.removeFirst()) count--
        level = score

        if (cooldown > 0) {
            cooldown--
            return false
        }
        if (count >= k) {
            cooldown = cooldownTicks
            return true
        }
        return false
    }

    override fun progress() = "$count/$k hits in last ${hits.size}/$m" +
        if (cooldown > 0) "  cooldown $cooldown" else ""
}
