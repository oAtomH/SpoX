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
    }

    private lateinit var engine: Engine
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var focus: AudioFocusRequest? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
            engine.stopLive()
            finish()
            return START_NOT_STICKY
        }
        if (!engine.isLive) engine.startLive(engine.prefs.getString("key", "").orEmpty())
        SpoxNotification.update(this)
        return START_STICKY
    }

    private fun finish() {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine.now.listeners -= changed
        engine.stateListeners -= ended
        release()
        super.onDestroy()
    }

    private val ended: (org.json.JSONObject?) -> Unit = { m -> if (m == null) main.post { finish() } }

    private val changed: () -> Unit = { main.post { locks() } }

    // keep the phone awake and on full Wi-Fi only while music plays (battery)
    private fun locks() {
        if (engine.now.playing) {
            runCatching { if (wake?.isHeld != true) wake?.acquire(6 * 60 * 60 * 1000L) }
            runCatching { if (wifi?.isHeld != true) wifi?.acquire() }
            if (focus == null) {
                val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener { c -> if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) engine.command("pause") }
                    .build()
                getSystemService(AudioManager::class.java).requestAudioFocus(f)
                focus = f
            }
        } else release()
    }

    private fun release() {
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }
}
