package net.media.wakeword

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var scoreText: TextView
    private lateinit var graph: ScoreGraphView
    private lateinit var progress: TextView
    private lateinit var stats: TextView
    private lateinit var log: TextView
    private lateinit var toggle: Button
    private lateinit var stopRing: Button
    private lateinit var sessionHint: TextView
    private val sessionControls = mutableListOf<View>()

    /** Re-sync every trigger control from WakeState.trigger (after a preset / on start). */
    private val refreshers = mutableListOf<() -> Unit>()
    private var updating = false
    private var pendingStart = false

    private val poll = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WakeState.listening) {
            WakeState.trigger = Prefs.loadTrigger(this)
            WakeState.session = Prefs.loadSession(this)
        }
        setContentView(buildUi())
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    // ---- UI ------------------------------------------------------------------------

    private fun buildUi(): View {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        root.addView(text("Hey Find My Phone", 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        root.addView(text("On-device wake word test build", 13f, muted = true))

        status = text("", 16f).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(status)

        scoreText = text("", 48f).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        root.addView(scoreText)

        graph = ScoreGraphView(this)
        root.addView(graph, LinearLayout.LayoutParams(-1, dp(140)))

        progress = text("", 14f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0) }
        root.addView(progress)
        stats = text("", 13f, muted = true).apply { typeface = Typeface.MONOSPACE }
        root.addView(stats)

        toggle = Button(this).apply { textSize = 18f; setOnClickListener { onToggle() } }
        root.addView(toggle, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(12) })

        stopRing = Button(this).apply {
            text = "Stop ringing"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            setOnClickListener { WakeWordService.send(this@MainActivity, WakeWordService.ACTION_STOP_RING) }
        }
        root.addView(stopRing, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(8) })

        // --- trigger ---
        root.addView(header("Trigger", "changes apply instantly, even while listening"))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(presetButton("Sweep best", TriggerConfig.SWEEP_BEST), LinearLayout.LayoutParams(0, -2, 1f))
            addView(presetButton("Sweep 2nd", TriggerConfig.SWEEP_2ND), LinearLayout.LayoutParams(0, -2, 1f))
            addView(presetButton("Loose", TriggerConfig.LOOSE), LinearLayout.LayoutParams(0, -2, 1f))
        })

        root.addView(label("Rule"))
        root.addView(spinner(RuleMode.entries.map { it.label },
            get = { WakeState.trigger.mode.ordinal },
            set = { i -> edit { copy(mode = RuleMode.entries[i]) } }))

        root.addView(slider("Cutoff", 500, 999,
            get = { (WakeState.trigger.cutoff * 1000).toInt() },
            set = { v -> edit { copy(cutoff = v / 1000f) } },
            fmt = { v -> "%.3f".format(v / 1000f) }))

        root.addView(slider("Streak length N", 1, 50,
            get = { WakeState.trigger.streakN },
            set = { v -> edit { copy(streakN = v) } },
            fmt = ::ticksFmt, visibleIn = setOf(RuleMode.STREAK)))

        root.addView(slider("Average window", 1, 50,
            get = { WakeState.trigger.avgWindow },
            set = { v -> edit { copy(avgWindow = v) } },
            fmt = ::ticksFmt, visibleIn = setOf(RuleMode.MOVAVG)))

        root.addView(slider("K (hits needed)", 1, 50,
            get = { WakeState.trigger.k },
            set = { v -> edit { copy(k = v, m = maxOf(m, v)) } },
            fmt = { "$it ticks" }, visibleIn = setOf(RuleMode.K_OF_M)))

        root.addView(slider("M (window)", 1, 50,
            get = { WakeState.trigger.m },
            set = { v -> edit { copy(m = v, k = minOf(k, v)) } },
            fmt = ::ticksFmt, visibleIn = setOf(RuleMode.K_OF_M)))

        root.addView(slider("Cooldown after fire", 0, 100,
            get = { WakeState.trigger.cooldownTicks },
            set = { v -> edit { copy(cooldownTicks = v) } },
            fmt = ::ticksFmt, visibleIn = setOf(RuleMode.MOVAVG, RuleMode.K_OF_M)))

        root.addView(label("On detection"))
        root.addView(spinner(DetectAction.entries.map { it.label },
            get = { WakeState.trigger.action.ordinal },
            set = { i -> edit { copy(action = DetectAction.entries[i]) } }))

        root.addView(slider("Ring duration", 5, 60,
            get = { WakeState.trigger.ringSeconds },
            set = { v -> edit { copy(ringSeconds = v) } },
            fmt = { "$it s" }, visibleIf = { WakeState.trigger.action == DetectAction.ALARM }))

        // --- session ---
        root.addView(header("Session", "model and mic are fixed while listening"))
        sessionHint = text("Stop listening to change these.", 12f, muted = true)
        root.addView(sessionHint)

        root.addView(label("Model"))
        root.addView(spinner(MODELS.map { it.label },
            get = { MODELS.indexOfFirst { it.asset == WakeState.session.modelAsset }.coerceAtLeast(0) },
            set = { i -> editSession { copy(modelAsset = MODELS[i].asset) } }).also { sessionControls += it })

        root.addView(label("Mic source"))
        root.addView(spinner(MicSource.entries.map { it.label },
            get = { WakeState.session.mic.ordinal },
            set = { i -> editSession { copy(mic = MicSource.entries[i]) } }).also { sessionControls += it })
        root.addView(text(unprocessedSupport(), 12f, muted = true))

        // --- log ---
        root.addView(header("Detections", "newest first"))
        log = text("", 13f).apply { typeface = Typeface.MONOSPACE }
        root.addView(log)

        root.addView(text(
            "Only the two sweep presets are validated (test/sweep_decision_rule.py, on the " +
                "v3 model). Anything else is exploration: judge it by live misses and false fires.",
            12f, muted = true
        ).apply { setPadding(0, dp(16), 0, 0) })

        return ScrollView(this).apply { addView(root) }
    }

    private fun ticksFmt(v: Int) = "$v ticks (${v * 30} ms)"

    private fun edit(f: TriggerConfig.() -> TriggerConfig) {
        if (updating) return
        WakeState.trigger = WakeState.trigger.f()
        Prefs.saveTrigger(this, WakeState.trigger)
        refreshAll()
    }

    private fun editSession(f: SessionConfig.() -> SessionConfig) {
        if (updating || WakeState.listening) return
        WakeState.session = WakeState.session.f()
        Prefs.saveSession(this, WakeState.session)
    }

    private fun refreshAll() {
        updating = true
        refreshers.forEach { it() }
        updating = false
    }

    private fun presetButton(name: String, preset: TriggerConfig) = Button(this).apply {
        text = name
        textSize = 12f
        setOnClickListener {
            // Presets set the rule knobs but keep the user's detection action / ring time.
            val cur = WakeState.trigger
            WakeState.trigger = preset.copy(action = cur.action, ringSeconds = cur.ringSeconds)
            Prefs.saveTrigger(this@MainActivity, WakeState.trigger)
            refreshAll()
        }
    }

    private fun spinner(items: List<String>, get: () -> Int, set: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != get()) set(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        refreshers += { if (selectedItemPosition != get()) setSelection(get()) }
    }

    /** Label + value, then [−] seekbar [+] for fine steps. */
    private fun slider(
        name: String, min: Int, max: Int,
        get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String,
        visibleIn: Set<RuleMode>? = null, visibleIf: (() -> Boolean)? = null,
    ): View {
        val title = text("", 14f)
        val bar = SeekBar(this).apply { this.max = max - min }
        fun apply(v: Int) = set(v.coerceIn(min, max))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) apply(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        fun step(label: String, d: Int) = Button(this).apply {
            text = label
            textSize = 18f
            setOnClickListener { apply(get() + d) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(step("−", -1), LinearLayout.LayoutParams(dp(52), dp(48)))
            addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(step("+", +1), LinearLayout.LayoutParams(dp(52), dp(48)))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(title)
            addView(row)
        }
        refreshers += {
            val v = get()
            title.text = "$name: ${fmt(v)}"
            bar.progress = v - min
            val visible = (visibleIn?.contains(WakeState.trigger.mode) ?: true) && (visibleIf?.invoke() ?: true)
            box.visibility = if (visible) View.VISIBLE else View.GONE
        }
        return box
    }

    private fun header(title: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(24), 0, dp(4))
        addView(text(title, 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(text(sub, 12f, muted = true))
    }

    private fun label(s: String) = text(s, 14f).apply { setPadding(0, dp(8), 0, 0) }

    private fun text(s: String, size: Float, muted: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (muted) setTextColor(Color.parseColor("#757575"))
    }

    private fun unprocessedSupport(): String {
        val p = getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (p == "true") "This phone reports true UNPROCESSED support."
        else "This phone does not report UNPROCESSED support (it may fall back to processed audio)."
    }

    // ---- start / stop --------------------------------------------------------------

    private fun onToggle() {
        if (WakeState.listening) {
            WakeWordService.send(this, WakeWordService.ACTION_STOP)
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startListening()
        else {
            pendingStart = true
            requestPermissions(needed.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!pendingStart) return
        pendingStart = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            WakeState.error = "Microphone permission is required."
        }
    }

    private fun startListening() {
        WakeState.error = null
        WakeWordService.start(this)
    }

    // ---- render --------------------------------------------------------------------

    private fun render() {
        val s = WakeState
        val err = s.error
        status.text = when {
            err != null -> "⚠ $err"
            s.ringing -> "🔔 Wake word detected: ringing"
            s.listening -> "● Listening · ${MODELS.first { it.asset == s.session.modelAsset }.asset} · ${s.session.mic.name}"
            else -> "Stopped"
        }
        status.setTextColor(
            when {
                err != null || s.ringing -> Color.parseColor("#C62828")
                s.listening -> Color.parseColor("#2E7D32")
                else -> Color.GRAY
            }
        )
        scoreText.text = "%.3f".format(s.score)
        graph.invalidate()
        progress.text = if (s.listening) "${s.trigger.summary()}  →  ${s.progress}" else s.trigger.summary()
        val last = if (s.lastFireAtMs == 0L) "never"
        else "${(System.currentTimeMillis() - s.lastFireAtMs) / 1000}s ago"
        stats.text = "peak ${"%.3f".format(s.peak)} · ${s.ticks * 30 / 1000}s scored · " +
            "fires ${s.fires} · last $last"

        log.text = synchronized(s.fireLog) { s.fireLog.joinToString("\n") }.ifEmpty { "none yet" }

        toggle.text = if (s.listening) "Stop listening" else "Start listening"
        stopRing.visibility = if (s.ringing) View.VISIBLE else View.GONE
        sessionControls.forEach { it.isEnabled = !s.listening }
        sessionHint.visibility = if (s.listening) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
