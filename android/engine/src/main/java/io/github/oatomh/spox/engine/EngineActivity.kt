package io.github.oatomh.spox.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The engine's own screen: set up, start/stop, and the log (for reports). Music keeps playing
 *  when this screen is closed: the engine runs in the background with its own notification. */
class EngineActivity : Activity() {
    private lateinit var engine: Engine
    private lateinit var out: TextView
    private lateinit var scroller: ScrollView
    private lateinit var keyBox: EditText
    private lateinit var liveBtn: Button
    private val refresh: () -> Unit = { runOnUiThread { show() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = Engine.get(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 10, 11))
            setPadding(32, 96, 32, 32)
        }
        fun button(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { f() }
            box.addView(this)
        }
        keyBox = EditText(this).apply {
            hint = "Your Soloist API key (stays on this phone)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(engine.prefs.getString("key", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            box.addView(this)
        }
        liveBtn = button("Start the engine") {
            if (engine.isLive) EngineService.stop(this) else {
                engine.prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
                if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
                EngineService.start(this)
            }
            liveBtn.postDelayed({ show() }, 300)
        }
        button("Set up again (re-download)") { kotlin.concurrent.thread { engine.setUp() } }
        button("Copy report") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SpoX Engine report", engine.reportText()))
            engine.log("(report copied: paste it to Claude)")
        }
        out = TextView(this).apply {
            setTextColor(Color.rgb(91, 227, 142))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroller = ScrollView(this).apply { addView(out) }
        box.addView(scroller, LinearLayout.LayoutParams(-1, -1))
        setContentView(box)
        if (engine.prefs.getString("key", "").isNullOrEmpty()) engine.log("Paste your Soloist API key, tap Start, then pick \"SpoX Phone\" in Spotify Connect once.")
    }

    override fun onStart() { super.onStart(); engine.logListeners += refresh; show() }
    override fun onStop() { engine.logListeners -= refresh; super.onStop() }

    private fun show() {
        liveBtn.text = if (engine.isLive) "Stop the engine" else "Start the engine"
        out.text = engine.reportText().takeLast(60000)
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
