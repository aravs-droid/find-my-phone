package net.media.wakeword

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

/**
 * Mic PCM -> frontend -> feature_queue -> model -> decision rule.
 *
 * Mirrors test/live_mic.py: audio is fed in 160-sample chunks, advancing by
 * samples_read; features are consumed 3 rows at a time, non-overlapping. Frontend and
 * interpreter both carry streaming state, so one engine lives for a whole session.
 * Not thread-safe: call [feed] from the audio thread only.
 */
class WakeWordEngine(
    context: Context,
    modelAsset: String,
    /** Read every tick; publishing a new instance swaps the rule live (no restart). */
    private val config: () -> TriggerConfig,
    private val onTick: (score: Float, rule: DecisionRule, fired: Boolean) -> Unit,
) : AutoCloseable {

    private val frontend = MicroFrontend()
    private val interpreter = Interpreter(loadModel(context, modelAsset))

    private var ruleConfig = config()
    private var rule = ruleConfig.buildRule()

    private val inScale: Float
    private val inZero: Int
    private val outScale: Float
    private val outZero: Int

    // Leftover samples not yet consumed by the frontend (live_mic.py's `buf`).
    private val pending = ShortArray(CHUNK_SAMPLES * 8)
    private var pendingLen = 0

    private val featureRow = FloatArray(MicroFrontend.NUM_FEATURES)
    private val featureQueue = ArrayList<FloatArray>()

    private val inputBuffer =
        ByteBuffer.allocateDirect(STRIDE * MicroFrontend.NUM_FEATURES).order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())

    init {
        val inQ = interpreter.getInputTensor(0).quantizationParams()
        val outQ = interpreter.getOutputTensor(0).quantizationParams()
        inScale = inQ.scale
        inZero = inQ.zeroPoint
        outScale = outQ.scale
        outZero = outQ.zeroPoint
    }

    fun feed(samples: ShortArray, count: Int) {
        var src = 0
        while (src < count) {
            val n = minOf(count - src, pending.size - pendingLen)
            System.arraycopy(samples, src, pending, pendingLen, n)
            pendingLen += n
            src += n
            if (!drainAudio()) break
        }
        drainFeatures()
    }

    /** Returns false if the frontend stopped consuming (guard against spinning). */
    private fun drainAudio(): Boolean {
        var start = 0
        while (pendingLen - start >= CHUNK_SAMPLES) {
            val r = frontend.process(pending, start, CHUNK_SAMPLES, featureRow)
            if (r.samplesRead <= 0) break  // never spin forever
            start += r.samplesRead
            if (r.hasFeatures) featureQueue.add(featureRow.copyOf())
        }
        if (start > 0) {
            System.arraycopy(pending, start, pending, 0, pendingLen - start)
            pendingLen -= start
        }
        return pendingLen < pending.size
    }

    private fun drainFeatures() {
        while (featureQueue.size >= STRIDE) {
            inputBuffer.rewind()
            for (i in 0 until STRIDE) {
                for (v in featureQueue[i]) {
                    // round() is half-to-even, matching np.round in test/live_mic.py.
                    val q = (round(v / inScale).toInt() + inZero).coerceIn(-128, 127)
                    inputBuffer.put(q.toByte())
                }
            }
            featureQueue.subList(0, STRIDE).clear()
            inputBuffer.rewind()

            outputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            val raw = outputBuffer.get().toInt() and 0xFF  // uint8 output (pitfall #6)
            val score = (raw - outZero) * outScale

            val c = config()
            if (c !== ruleConfig) {
                ruleConfig = c
                rule = c.buildRule()
            }
            onTick(score, rule, rule.onTick(score))
        }
    }

    override fun close() {
        interpreter.close()
        frontend.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 160  // 10ms
        const val STRIDE = 3           // model eats 3 frames (30ms) per inference

        private fun loadModel(context: Context, asset: String): ByteBuffer {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }
}
