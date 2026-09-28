package net.media.wakeword

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * "Ring at full volume even if muted": plays the alarm tone on the ALARM stream (which
 * silent/vibrate ringer modes don't mute), raises that stream to max for the duration,
 * and restores the user's volume afterwards. Stands in for the app's RingtoneHelper.
 */
class Ringer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var autoStop: Runnable? = null

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun start(autoStopMs: Long = 30_000, onStopped: () -> Unit) {
        if (player != null) return
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(
            AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
        )

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(context, uri)
            isLooping = true
            prepare()
            start()
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        autoStop = Runnable { stop(); onStopped() }.also { handler.postDelayed(it, autoStopMs) }
    }

    /** Quick confirmation for tuning sessions: normal volume, no volume override. */
    fun beep() {
        runCatching {
            android.media.ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                .apply { startTone(android.media.ToneGenerator.TONE_PROP_ACK, 300) }
                .also { tg -> handler.postDelayed({ tg.release() }, 500) }
        }
        vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun stop() {
        autoStop?.let(handler::removeCallbacks)
        autoStop = null
        player?.run { runCatching { stop() }; release() }
        player = null
        vibrator.cancel()
        if (savedVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
    }
}
