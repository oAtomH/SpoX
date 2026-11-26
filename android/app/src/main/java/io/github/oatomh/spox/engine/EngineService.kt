package io.github.oatomh.spox.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import io.github.oatomh.spox.Player
import io.github.oatomh.spox.SpoxNotification

/**
 * Keeps SpoX Engine alive in the background like a real music app (a foreground service), so
 * Android doesn't freeze Soloist. The player you see (notification, lock screen, Now Bar) is
 * SpoX's own, shared with the Spotify-app mode; this service only keeps things running:
 * wake + Wi-Fi locks while music plays, and audio focus (calls and other apps pause it).
 */
class EngineService : Service() {
    companion object {
        private const val STOP = "stop"
        fun start(c: Context) = c.startForegroundService(Intent(c, EngineService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, EngineService::class.java).setAction(STOP))

        /** What closing SpoX (swiping it away) does: "paused" = quit once the music is paused
         *  (right away if it is), "now" = quit at once, "keep" = keep running like before. */
        fun onClose(c: Context) = c.getSharedPreferences("spox", 0).getString("on_close", "paused") ?: "paused"
        fun cycleOnClose(c: Context) {
            val next = when (onClose(c)) { "paused" -> "now"; "now" -> "keep"; else -> "paused" }
            c.getSharedPreferences("spox", 0).edit().putString("on_close", next).apply()
        }

        /** SpoX was swiped away: quit when the music stops (see [onClose]). Opening it again clears it. */
        @Volatile var closed = false
    }

    private var stopping = false
    private var restarts = ArrayList<Long>()

    private lateinit var engine: Engine
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var focus: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        io.github.oatomh.spox.CrashLog.install(this)
        engine = Engine.get(this)
        Player.init(this)
        engine.now.listeners += changed
        engine.stateListeners += ended
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spox:engine").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "spox:engine").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(SpoxNotification.ID, SpoxNotification.placeholder(this, "Engine starting... pick \"SpoX Phone\" in Spotify Connect once"))
        if (intent?.action == STOP) {
            quit()
            return START_NOT_STICKY
        }
        closed = false
        main.removeCallbacks(idleQuit)
        stopping = false
        if (!engine.isLive) engine.startLive(engine.prefs.getString("key", "").orEmpty())
        // startForeground just put the plain "starting" card up: put the real player back over it
        SpoxNotification.refresh(this)
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 45_000)
        return START_STICKY
    }

    private fun finish() {
        release()
        main.removeCallbacks(refresh)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Stops Soloist and SpoX's player (nothing of SpoX keeps running). */
    private fun quit() {
        stopping = true
        engine.stopLive()
        finish()
        runCatching { getSystemService(android.app.NotificationManager::class.java).cancel(SpoxNotification.ID) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        when (onClose(this)) {
            "keep" -> {}
            "now" -> quit()
            else -> {
                closed = true
                if (!engine.now.playing) quit()
            }
        }
    }

    // paused for a while after SpoX was closed: quit (a short pause, like a call, keeps it)
    private val idleQuit = Runnable { if (closed && !engine.now.playing) quit() }

    // the lock screen / Now Bar player is posted again now and then, so Android doesn't drop it
    private val refresh = object : Runnable {
        override fun run() {
            SpoxNotification.refresh(this@EngineService)
            main.postDelayed(this, 45_000)
        }
    }

    override fun onDestroy() {
        engine.now.listeners -= changed
        engine.stateListeners -= ended
        release()
        super.onDestroy()
    }

    // Soloist stopped. Meant to: done. By itself (it crashed): start it again, up to 3 times in 10 min.
    private val ended: (org.json.JSONObject?) -> Unit = { m ->
        if (m == null) main.post {
            val now = System.currentTimeMillis()
            restarts.removeAll { now - it > 10 * 60_000 }
            val want = getSharedPreferences("spox", 0).getBoolean("engine_on", false) && !closed
            if (!stopping && want && restarts.size < 3) {
                restarts += now
                engine.log("Soloist stopped by itself: starting it again")
                main.postDelayed({ if (!stopping && !engine.isLive) engine.startLive(engine.prefs.getString("key", "").orEmpty()) }, 2500)
            } else finish()
        }
    }

    private val changed: () -> Unit = { main.post { locks() } }

    // keep the phone awake and on full Wi-Fi only while music plays (battery)
    private fun locks() {
        if (engine.now.playing) {
            runCatching { if (wake?.isHeld != true) wake?.acquire(6 * 60 * 60 * 1000L) }
            runCatching { if (wifi?.isHeld != true) wifi?.acquire() }
            pausedForCall = false
            if (focus == null) {
                val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { c ->
                        when (c) {
                            // another app plays music / a video: stop for good
                            // only when the phone itself plays: a pause sent while another device
                            // (SpoX on the PC) plays would pause that one
                            AudioManager.AUDIOFOCUS_LOSS -> { pausedForCall = false; if (engine.now.active) engine.command("pause") }
                            // a call, a voice note, navigation...: pause, and carry on after
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (engine.now.playing && engine.now.active) { pausedForCall = true; engine.command("pause") } }
                            AudioManager.AUDIOFOCUS_GAIN -> { if (pausedForCall) { pausedForCall = false; if (getSharedPreferences("spox", 0).getBoolean("resume_after_call", true)) engine.command("play") } }
                        }
                    }
                    .build()
                getSystemService(AudioManager::class.java).requestAudioFocus(f)
                focus = f
            }
            if (!noisyOn) {
                // headphones / Bluetooth / car disconnect: pause straight away (like every music app)
                runCatching { registerReceiver(noisy, android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); noisyOn = true }
            }
        } else {
            // paused by a call: keep the focus so the music comes back after it
            if (pausedForCall) {
                runCatching { wake?.release() }
                runCatching { wifi?.release() }
                return
            }
            release()
            if (closed) { main.removeCallbacks(idleQuit); main.postDelayed(idleQuit, 3 * 60_000) }
        }
    }

    @Volatile private var pausedForCall = false
    private var noisyOn = false
    private val noisy = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && engine.now.active && c.getSharedPreferences("spox", 0).getBoolean("pause_noisy", true)) {
                pausedForCall = false
                engine.hush()
                io.github.oatomh.spox.PlayerView.say(c, "Paused: headphones disconnected")
            }
        }
    }

    private fun release() {
        if (noisyOn) { runCatching { unregisterReceiver(noisy) }; noisyOn = false }
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }
}
