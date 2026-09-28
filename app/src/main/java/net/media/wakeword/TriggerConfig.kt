package net.media.wakeword

import android.content.Context
import android.media.MediaRecorder

enum class RuleMode(val label: String) {
    STREAK("Streak: N raw ticks in a row ≥ cutoff"),
    MOVAVG("Moving average ≥ cutoff"),
    K_OF_M("K of last M ticks ≥ cutoff"),
}

enum class DetectAction(val label: String) {
    ALARM("Full alarm (max volume, even on silent)"),
    BEEP("Short beep + vibrate"),
    COUNT("Count only (silent)"),
}

enum class MicSource(val label: String, val source: Int) {
    VOICE_RECOGNITION("VOICE_RECOGNITION (no AGC/NS, default)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    UNPROCESSED("UNPROCESSED (raw, if the phone supports it)", MediaRecorder.AudioSource.UNPROCESSED),
    MIC("MIC (device default processing)", MediaRecorder.AudioSource.MIC),
}

data class ModelOption(val asset: String, val label: String)

val MODELS = listOf(
    ModelOption("v3.tflite", "v3: hey_find_my_phone.tflite (current, what live_mic.py uses)"),
    ModelOption("v4.tflite", "v4 candidate"),
    ModelOption("v2.tflite", "v2 backup"),
)

/** Detection knobs. Immutable: the UI publishes a new instance, the audio thread picks it up. */
data class TriggerConfig(
    val mode: RuleMode = RuleMode.STREAK,
    val cutoff: Float = 0.99f,
    val streakN: Int = 10,
    val avgWindow: Int = 15,
    val k: Int = 8,
    val m: Int = 12,
    val cooldownTicks: Int = 25,
    val action: DetectAction = DetectAction.ALARM,
    val ringSeconds: Int = 30,
) {
    fun buildRule(): DecisionRule = when (mode) {
        RuleMode.STREAK -> StreakDecisionRule(cutoff, streakN)
        RuleMode.MOVAVG -> MovingAverageDecisionRule(cutoff, avgWindow, cooldownTicks)
        RuleMode.K_OF_M -> KOfMDecisionRule(cutoff, k.coerceAtMost(m), m, cooldownTicks)
    }

    fun summary(): String = when (mode) {
        RuleMode.STREAK -> "streak $streakN @ ${"%.3f".format(cutoff)}"
        RuleMode.MOVAVG -> "avg $avgWindow @ ${"%.3f".format(cutoff)}, cd $cooldownTicks"
        RuleMode.K_OF_M -> "$k of $m @ ${"%.3f".format(cutoff)}, cd $cooldownTicks"
    }

    companion object {
        /** test/sweep_decision_rule.py winner: hardneg_fire=1.0%, recall=94.9%. */
        val SWEEP_BEST = TriggerConfig()
        /** Sweep's close 2nd: hardneg_fire=0.8%, recall=90.5%. */
        val SWEEP_2ND = TriggerConfig(mode = RuleMode.MOVAVG, cutoff = 0.995f, avgWindow = 15, cooldownTicks = 25)
        /** Not sweep-validated: fires more easily, expect more false triggers. */
        val LOOSE = TriggerConfig(mode = RuleMode.STREAK, cutoff = 0.90f, streakN = 5)
    }
}

/** Session-level options that need a mic/model restart to change. */
data class SessionConfig(
    val modelAsset: String = MODELS.first().asset,
    val mic: MicSource = MicSource.VOICE_RECOGNITION,
)

object Prefs {
    private const val NAME = "wakeword"

    fun loadTrigger(ctx: Context): TriggerConfig {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val d = TriggerConfig()
        return TriggerConfig(
            mode = enumOr(p.getString("mode", null), d.mode),
            cutoff = p.getFloat("cutoff", d.cutoff),
            streakN = p.getInt("streakN", d.streakN),
            avgWindow = p.getInt("avgWindow", d.avgWindow),
            k = p.getInt("k", d.k),
            m = p.getInt("m", d.m),
            cooldownTicks = p.getInt("cooldown", d.cooldownTicks),
            action = enumOr(p.getString("action", null), d.action),
            ringSeconds = p.getInt("ringSeconds", d.ringSeconds),
        )
    }

    fun saveTrigger(ctx: Context, c: TriggerConfig) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("mode", c.mode.name).putFloat("cutoff", c.cutoff)
            .putInt("streakN", c.streakN).putInt("avgWindow", c.avgWindow)
            .putInt("k", c.k).putInt("m", c.m).putInt("cooldown", c.cooldownTicks)
            .putString("action", c.action.name).putInt("ringSeconds", c.ringSeconds)
            .apply()
    }

    fun loadSession(ctx: Context): SessionConfig {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val d = SessionConfig()
        val model = p.getString("model", null)?.takeIf { a -> MODELS.any { it.asset == a } } ?: d.modelAsset
        return SessionConfig(model, enumOr(p.getString("mic", null), d.mic))
    }

    fun saveSession(ctx: Context, s: SessionConfig) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("model", s.modelAsset).putString("mic", s.mic.name).apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
