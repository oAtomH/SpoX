package io.github.oatomh.spox.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

/**
 * Keeps SpoX Engine alive in the background like a real music app: a foreground service with a
 * media session, so Android doesn't freeze Soloist, the phone's media controls / lock screen /
 * headphones work, and the SpoX app can show and control what's playing.
 */
class EngineService : Service() {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        private const val CHANNEL = "engine"
        private const val ID = 7
        fun start(c: Context) = c.startForegroundService(Intent(c, EngineService::class.java).setAction(START))
        fun stop(c: Context) = c.startService(Intent(c, EngineService::class.java).setAction(STOP))
    }

    private lateinit var engine: Engine
    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var focus: AudioFocusRequest? = null

    // what's playing (from Soloist's messages)
    private var title = ""; private var artist = ""; private var album = ""; private var uri = ""
    private var duration = 0L; private var cover = ""; private var art: Bitmap? = null; private var artFor = ""
    private var status = "stopped"; private var posMs = 0.0; private var posAt = 0L; private var speed = 1.0
    private var shuffle = false; private var repeat = "off"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = Engine.get(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SpoX Engine", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        session = MediaSession(this, "SpoX").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = engine.command("play")
                override fun onPause() = engine.command("pause")
                override fun onSkipToNext() = engine.command("skip_next")
                override fun onSkipToPrevious() = engine.command("skip_prev")
                override fun onSeekTo(pos: Long) = engine.command("seek", mapOf("position_ms" to pos))
                override fun onStop() = engine.command("pause")
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        "shuffle" -> engine.command("set_shuffle", mapOf("enabled" to !shuffle))
                        "repeat" -> engine.command("set_repeat_context", mapOf("enabled" to (repeat == "off")))
                    }
                }
            })
            isActive = true
        }
        engine.stateListeners += listener
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spox:engine").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "spox:engine").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification())
        when (intent?.action) {
            STOP -> { engine.stopLive(); finish(); return START_NOT_STICKY }
            "play" -> engine.command("play")
            "pause" -> engine.command("pause")
            "next" -> engine.command("skip_next")
            "prev" -> engine.command("skip_prev")
            else -> {
                val key = engine.prefs.getString("key", "").orEmpty()
                if (!engine.isLive) engine.startLive(key)
            }
        }
        return START_STICKY
    }

    private fun finish() {
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine.stateListeners -= listener
        release()
        session?.release()
        super.onDestroy()
    }

    private fun release() {
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    // ---- Soloist's messages -> media session + notification ---------------------------------

    private val listener: (JSONObject?) -> Unit = { m -> if (m == null) { status = "stopped"; update(); finishLater() } else apply(m) }

    private fun finishLater() { android.os.Handler(mainLooper).post { finish() } }

    private fun name(o: JSONObject?): String = o?.optJSONObject("decorations")?.optJSONObject("identity")?.optString("name").orEmpty()

    private fun item(o: JSONObject?) {
        if (o == null) return
        uri = o.optString("uri")
        title = name(o)
        val d = o.optJSONObject("decorations")
        artist = d?.optJSONArray("creators")?.let { a -> (0 until a.length()).map { name(a.optJSONObject(it)?.optJSONObject("entity")) }.filter { it.isNotEmpty() }.joinToString(", ") }.orEmpty()
        album = name(d?.optJSONObject("parent")?.optJSONObject("entity"))
        duration = d?.optJSONObject("playback")?.optLong("duration_ms") ?: 0
        val covers = d?.optJSONObject("visual_identity")?.optJSONArray("cover")
        cover = covers?.let { c ->
            val list = (0 until c.length()).mapNotNull { c.optJSONObject(it) }
            (list.firstOrNull { it.optString("size") == "large" } ?: list.firstOrNull { it.optString("size") == "default" } ?: list.firstOrNull())?.optString("url")
        }.orEmpty()
    }

    private fun pos(p: JSONObject?) {
        if (p == null) return
        posMs = p.optDouble("position_ms", posMs)
        posAt = p.optLong("timestamp_ms", System.currentTimeMillis())
        speed = p.optDouble("speed", 1.0)
    }

    private fun now(): Long {
        var p = posMs
        if (status == "playing" && posAt > 0) p += (System.currentTimeMillis() - posAt) * speed
        if (duration > 0) p = p.coerceAtMost(duration.toDouble())
        return p.toLong().coerceAtLeast(0)
    }

    private fun apply(m: JSONObject) {
        when (m.optString("type")) {
            "playback_state" -> {
                status = m.optString("status", status)
                item(m.optJSONObject("item"))
                pos(m.optJSONObject("position"))
                m.optJSONObject("options")?.let { shuffle = it.optBoolean("shuffle", shuffle); repeat = it.optString("repeat", repeat) }
            }
            "track_changed" -> { item(m.optJSONObject("item")); posMs = 0.0; posAt = System.currentTimeMillis() }
            "playback_changed" -> { val p = now(); posMs = p.toDouble(); posAt = System.currentTimeMillis(); status = m.optString("status", status) }
            "position_sync" -> pos(m.optJSONObject("position"))
            "options_changed" -> m.optJSONObject("options")?.let { shuffle = it.optBoolean("shuffle", shuffle); repeat = it.optString("repeat", repeat) }
            else -> return
        }
        update()
    }

    private val playing get() = status == "playing" || status == "buffering"

    private fun update() {
        val s = session ?: return
        // keep the phone awake and on Wi-Fi only while music plays (battery)
        if (playing) {
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
        } else {
            runCatching { wake?.release() }
            runCatching { wifi?.release() }
            focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
            focus = null
        }
        if (cover.isNotEmpty() && cover != artFor) {
            artFor = cover
            val want = cover
            thread {
                val b = runCatching { URL(want).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
                if (want == artFor) { art = b; android.os.Handler(mainLooper).post { update() } }
            }
        }
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, uri)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            .build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(when (status) { "playing" -> PlaybackState.STATE_PLAYING; "buffering" -> PlaybackState.STATE_BUFFERING; "paused" -> PlaybackState.STATE_PAUSED; else -> PlaybackState.STATE_STOPPED },
                now(), if (playing) speed.toFloat() else 0f, SystemClock.elapsedRealtime())
            .addCustomAction(PlaybackState.CustomAction.Builder("shuffle", if (shuffle) "Shuffle on" else "Shuffle off", android.R.drawable.ic_menu_rotate).build())
            .addCustomAction(PlaybackState.CustomAction.Builder("repeat", if (repeat != "off") "Repeat on" else "Repeat off", android.R.drawable.ic_menu_revert).build())
            .build())
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification()) }
    }

    private fun act(a: String, icon: Int, label: String) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), label,
        PendingIntent.getService(this, a.hashCode(), Intent(this, EngineService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, EngineActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_spox)
            .setContentTitle(title.ifEmpty { "SpoX Engine" })
            .setContentText(artist.ifEmpty { if (engine.isLive) "Ready: pick \"SpoX Phone\" in Spotify Connect" else "Starting..." })
            .setLargeIcon(art)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act("prev", android.R.drawable.ic_media_previous, "Previous"))
            .addAction(if (playing) act("pause", android.R.drawable.ic_media_pause, "Pause") else act("play", android.R.drawable.ic_media_play, "Play"))
            .addAction(act("next", android.R.drawable.ic_media_next, "Next"))
        session?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2)) }
        return b.build()
    }
}
