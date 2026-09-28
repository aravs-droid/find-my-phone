package net.media.wakeword

/**
 * Stateful TFLite Micro frontend (native). Create once per listening session; the
 * native state carries noise-floor tracking and partial-window samples between calls.
 */
class MicroFrontend : AutoCloseable {
    private var handle: Long = nativeCreate().also {
        check(it != 0L) { "FrontendPopulateState failed" }
    }

    /** Result of one [process] call. [features] is only valid when [hasFeatures]. */
    class Result(val samplesRead: Int, val hasFeatures: Boolean)

    fun process(samples: ShortArray, offset: Int, count: Int, features: FloatArray): Result {
        val packed = nativeProcess(handle, samples, offset, count, features)
        return Result(samplesRead = packed and 0xFFFF, hasFeatures = (packed ushr 16) > 0)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        const val NUM_FEATURES = 40

        init {
            System.loadLibrary("microfrontend_jni")
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeProcess(
            handle: Long, samples: ShortArray, offset: Int, count: Int, out: FloatArray
        ): Int
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
