package net.media.wakeword

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service owning the mic, the engine and the ringer for one listening
 * session. Engine state (frontend + interpreter) lives exactly as long as the mic does.
 */
@SuppressLint("MissingPermission")  // RECORD_AUDIO is checked by MainActivity before start
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ringer: Ringer
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false
    private var audioThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ringer = Ringer(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_RING -> {
                stopRinging()
                return START_NOT_STICKY
            }
        }

        val notification = listeningNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_LISTENING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_LISTENING, notification)
        }
        if (!running) startListening()
        return START_NOT_STICKY
    }

    private fun startListening() {
        running = true
        WakeState.resetSession()
        WakeState.listening = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeword:listening")
            .apply { acquire() }

        val session = WakeState.session
        audioThread = Thread({ audioLoop(session) }, "wakeword-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun audioLoop(session: SessionConfig) {
        val minBuf = AudioRecord.getMinBufferSize(
            WakeWordEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                session.mic.source,
                WakeWordEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, WakeWordEngine.SAMPLE_RATE / 5 * 2),  // >= 200ms
            )
        } catch (e: Exception) {
            fail("Mic init failed: ${e.message}"); return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); fail("Mic unavailable with ${session.mic.name} (in use, or unsupported?)"); return
        }

        val engine = try {
            WakeWordEngine(
                this, session.modelAsset,
                config = { WakeState.trigger },
                onTick = { score, rule, fired ->
                    WakeState.record(score, rule.level, fired)
                    WakeState.progress = rule.progress()
                    if (fired && !WakeState.ringing) main.post { onWakeWord() }
                },
            )
        } catch (e: Exception) {
            record.release(); fail("Model/frontend init failed: ${e.message}"); return
        }

        val buf = ShortArray(WakeWordEngine.CHUNK_SAMPLES)
        try {
            record.startRecording()
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) engine.feed(buf, n)
                else if (n < 0) { fail("Mic read error $n"); break }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            engine.close()
        }
    }

    private fun onWakeWord() {
        WakeState.fires++
        WakeState.lastFireAtMs = System.currentTimeMillis()
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(WakeState.fireLog) {
            WakeState.fireLog.addFirst("$time  score ${"%.3f".format(WakeState.score)}  ${WakeState.trigger.summary()}")
            while (WakeState.fireLog.size > 8) WakeState.fireLog.removeLast()
        }

        val cfg = WakeState.trigger
        when (cfg.action) {
            DetectAction.COUNT -> Unit
            DetectAction.BEEP -> ringer.beep()
            DetectAction.ALARM -> {
                WakeState.ringing = true
                getSystemService(NotificationManager::class.java).notify(NOTIF_FOUND, foundNotification())
                try {
                    ringer.start(cfg.ringSeconds * 1000L) { stopRinging() }
                } catch (e: Exception) {
                    Log.e(TAG, "ringer failed", e)
                    WakeState.ringing = false
                }
            }
        }
    }

    private fun stopRinging() {
        ringer.stop()
        WakeState.ringing = false
        getSystemService(NotificationManager::class.java).cancel(NOTIF_FOUND)
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        WakeState.error = msg
        main.post { stopListening(); stopSelf() }
    }

    private fun stopListening() {
        running = false
        audioThread?.join(1000)
        audioThread = null
        stopRinging()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        WakeState.listening = false
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    // ---- notifications ------------------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTENING, "Listening", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FOUND, "Phone found", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun servicePending(action: String, req: Int) = PendingIntent.getService(
        this, req, Intent(this, WakeWordService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openAppPending() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun listeningNotification(): Notification =
        Notification.Builder(this, CH_LISTENING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Listening for \"hey find my phone\"")
            .setContentText("On-device only. Nothing leaves the phone.")
            .setOngoing(true)
            .setContentIntent(openAppPending())
            .addAction(Notification.Action.Builder(null, "Stop listening", servicePending(ACTION_STOP, 1)).build())
            .build()

    private fun foundNotification(): Notification =
        Notification.Builder(this, CH_FOUND)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Here I am!")
            .setContentText("Wake word detected. Tap Stop to silence.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openAppPending())
            .setDeleteIntent(servicePending(ACTION_STOP_RING, 3))
            .addAction(Notification.Action.Builder(null, "Stop", servicePending(ACTION_STOP_RING, 2)).build())
            .build()

    companion object {
        private const val TAG = "WakeWordService"
        const val ACTION_START = "net.media.wakeword.START"
        const val ACTION_STOP = "net.media.wakeword.STOP"
        const val ACTION_STOP_RING = "net.media.wakeword.STOP_RING"
        private const val CH_LISTENING = "listening"
        private const val CH_FOUND = "found"
        private const val NOTIF_LISTENING = 1
        private const val NOTIF_FOUND = 2

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, WakeWordService::class.java).setAction(action))
        }
    }
}
